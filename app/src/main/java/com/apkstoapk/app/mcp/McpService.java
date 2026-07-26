package com.apkstoapk.app.mcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.apkstoapk.app.ui.MainActivity;
import com.apkstoapk.app.util.SimpleApkLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground host for local MCP HTTP server (127.0.0.1 only).
 * Protocol shell from mcptool_new; tools are ApksToApk-only.
 */
public class McpService extends Service {
    public static final String ACTION_START = "com.apkstoapk.app.mcp.START";
    public static final String ACTION_STOP = "com.apkstoapk.app.mcp.STOP";
    public static final String EXTRA_PORT = "com.apkstoapk.app.mcp.PORT";
    private static final String CHANNEL_ID = "apkstoapk_mcp";
    private static final int NOTIFICATION_ID = 3101;

    private static McpServer server;
    public static final int DEFAULT_PORT = 8800;
    private static final String PREFS = "mcp_service_prefs";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_LAST_PORT = "last_port";
    private static int currentPort = DEFAULT_PORT;
    private static String selfCheckText = "";
    private static final StringBuilder LOG = new StringBuilder();
    private static JsonArray HISTORY = new JsonArray();
    /** Optional UI sink (MainActivity sharedLogger / Log tab). */
    private static SimpleApkLogger uiLogger;

    /** 打开 App 时是否自动启动 MCP（默认 true）。 */
    public static boolean isAutoStartEnabled(android.content.Context context) {
        if (context == null) return true;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, true);
    }

    public static void setAutoStartEnabled(android.content.Context context, boolean enabled) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_START, enabled)
                .apply();
    }

    public static int getPreferredPort(android.content.Context context) {
        if (context == null) return DEFAULT_PORT;
        int p = context.getApplicationContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_LAST_PORT, DEFAULT_PORT);
        if (p < 1024 || p > 65535) return DEFAULT_PORT;
        return p;
    }

    public static void setPreferredPort(android.content.Context context, int port) {
        if (context == null) return;
        if (port < 1024 || port > 65535) port = DEFAULT_PORT;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_LAST_PORT, port)
                .apply();
        currentPort = port;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        int port = intent == null ? currentPort : intent.getIntExtra(EXTRA_PORT, currentPort);
        if (port < 1024 || port > 65535) {
            port = DEFAULT_PORT;
        }
        currentPort = port;
        setPreferredPort(this, port);
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            addLog("前台通知启动失败：" + e.getMessage());
            throw e;
        }
        startServer(port);
        startSelfCheck(port);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    private Notification buildNotification() {
        createNotificationChannelIfNeeded();
        PendingIntent activityIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("我的工具 MCP")
                .setContentText("http://127.0.0.1:" + currentPort + "/mcp")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(activityIntent)
                .setOngoing(true);
        return builder.build();
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(
                    new NotificationChannel(CHANNEL_ID, "ApksToApk MCP", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private synchronized void startServer(int port) {
        try {
            if (server != null && server.isRunning()) {
                if (server.getPort() == port) {
                    addLog("MCP 已运行： http://127.0.0.1:" + port + "/mcp");
                    return;
                }
                addLog("端口变化，切换到 " + port);
                server.stop();
                server = null;
            }
            server = new McpServer(this, port);
            server.start();
            addLog("MCP 启动： http://127.0.0.1:" + port + "/mcp");
        } catch (Exception e) {
            addLog("MCP 启动失败：" + e.getMessage());
        }
    }

    private synchronized void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
            selfCheckText = "服务已停止";
            addLog("MCP 已停止");
        } else {
            addLog("MCP 未运行");
        }
    }

    public static synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public static synchronized int getCurrentPort() {
        return currentPort;
    }

    public static synchronized String getEndpoint() {
        return "http://127.0.0.1:" + currentPort + "/mcp";
    }

    public static synchronized void setUiLogger(SimpleApkLogger logger) {
        // Avoid double-binding / double replay
        if (uiLogger == logger) return;
        uiLogger = logger;
        // Replay buffered MCP logs once so Log tab shows prior lines
        if (logger != null && LOG.length() > 0) {
            String dump = LOG.toString();
            for (String line : dump.split("\n")) {
                if (line == null || line.isEmpty()) continue;
                logger.raw(line.startsWith("[MCP] ") ? line : ("[MCP] " + line));
            }
        }
    }

    public static synchronized void addLog(String text) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = time + "  " + text;
        LOG.append(line).append('\n');
        JsonObject item = new JsonObject();
        item.addProperty("time", time);
        item.addProperty("message", text);
        HISTORY.add(item);
        while (HISTORY.size() > 200) {
            HISTORY.remove(0);
        }
        if (LOG.length() > 12000) {
            LOG.delete(0, LOG.length() - 12000);
        }
        SimpleApkLogger sink = uiLogger;
        if (sink != null) {
            sink.raw("[MCP] " + line);
        }
    }

    public static synchronized String getLogText() {
        return LOG.toString();
    }

    public static synchronized void clearLog() {
        LOG.setLength(0);
        HISTORY = new JsonArray();
        // 不清 selfCheckText：那是服务状态，不是日志
    }

    public static synchronized JsonArray getHistoryItems() {
        return HISTORY.deepCopy();
    }

    public static synchronized String getSelfCheckText() {
        return selfCheckText;
    }

    private void startSelfCheck(final int port) {
        selfCheckText = "等待自检";
        new Thread(() -> runSelfCheck(port), "apkstoapk-mcp-self-check").start();
    }

    private void runSelfCheck(int port) {
        try {
            Thread.sleep(300L);
        } catch (Exception ignored) {
        }
        boolean serviceRunning = isRunning();
        boolean portBound = false;
        String httpText = "未检测";
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            int code = connection.getResponseCode();
            portBound = true;
            readResponse(connection);
            httpText = "HTTP " + code + " /mcp OK";
        } catch (Exception e) {
            httpText = "HTTP 失败：" + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
        synchronized (McpService.class) {
            if (serviceRunning && portBound && httpText.startsWith("HTTP ")) {
                selfCheckText = "自检通过";
            } else {
                selfCheckText = (serviceRunning ? "服务已运行" : "服务未运行")
                        + " / " + (portBound ? "端口已监听" : "端口检测失败")
                        + " / " + httpText;
            }
        }
    }

    private String readResponse(HttpURLConnection connection) throws Exception {
        InputStream inputStream = connection.getInputStream();
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            while (true) {
                int read = inputStream.read(buffer);
                if (read < 0) {
                    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
                }
                outputStream.write(buffer, 0, read);
            }
        } finally {
            inputStream.close();
        }
    }
}