package com.apkstoapk.app.mcp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

/**
 * 1:1 port of mcptool_new MainActivity MCP/Shizuku/storage init + start flow.
 * UI only calls into this; no extra abstractions.
 */
public final class McpBootstrap {
    public static final int SHIZUKU_REQUEST_CODE = 10086;
    public static final int STORAGE_REQUEST_CODE = 10087;

    public interface StatusListener {
        void onStatusChanged();
    }

    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean pendingShizukuPermissionRequest;
    private boolean shizukuBound;
    private StatusListener statusListener;
    private final Shizuku.OnBinderReceivedListener binderReceivedListener;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener;

    public McpBootstrap(Activity activity) {
        this.activity = activity;
        // listeners must be created after activity is assigned (javac definite assignment)
        this.binderReceivedListener = () ->
                mainHandler.post(() -> {
                    try {
                        if (Shizuku.checkSelfPermission() == 0) {
                            pendingShizukuPermissionRequest = false;
                        } else if (pendingShizukuPermissionRequest) {
                            requestShizukuPermissionNow();
                        }
                    } catch (Throwable th) {
                        McpService.addLog("Shizuku 状态检查失败：" + th.getMessage());
                    }
                    notifyStatus();
                });
        this.permissionResultListener = (requestCode, grantResult) -> mainHandler.post(() -> {
            if (requestCode != SHIZUKU_REQUEST_CODE) return;
            if (grantResult == 0) {
                pendingShizukuPermissionRequest = false;
                Toast.makeText(McpBootstrap.this.activity, "Shizuku 授权成功", Toast.LENGTH_SHORT).show();
                McpService.addLog("Shizuku 授权成功");
            } else {
                pendingShizukuPermissionRequest = false;
                Toast.makeText(McpBootstrap.this.activity, "你拒绝了 Shizuku 授权", Toast.LENGTH_LONG).show();
                McpService.addLog("Shizuku 授权被拒绝");
            }
            notifyStatus();
        });
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * First-open init.
     * mcptool order: binder → callbacks → storage.
     * Extra for targetSdk 35 / modern Android:
     * - API 30+ file access must use MANAGE_EXTERNAL_STORAGE settings page
     * - POST_NOTIFICATIONS for FGS
     * - request Shizuku auth on first open (mcptool only did this on start click;
     *   user expects popup on first open when Shizuku is already running)
     */
    public void onCreate() {
        requestShizukuBinder("onCreate");
        bindShizukuCallbacks();
        requestStoragePermissionIfNeeded();
        requestNotificationPermissionIfNeeded();
        // first open: try Shizuku auth like start-click path
        pendingShizukuPermissionRequest = true;
        requestShizukuPermissionIfNeededDelayed();
        notifyStatus();
    }

    /** mcptool onResume: only updateStatus */
    public void onResume() {
        notifyStatus();
    }

    public void onDestroy() {
        if (shizukuBound) {
            try {
                Shizuku.removeBinderReceivedListener(binderReceivedListener);
                Shizuku.removeRequestPermissionResultListener(permissionResultListener);
            } catch (Throwable ignored) {
            }
            shizukuBound = false;
        }
        mainHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Exact port of mcptool startMcpService().
     * @return false if already running
     */
    public boolean startMcpService(int port) {
        return startMcpService(port, false);
    }

    /**
     * @param quiet true 时不弹 Toast（用于打开 App 自动启动）
     */
    public boolean startMcpService(int port, boolean quiet) {
        if (McpService.isRunning()) {
            if (!quiet) {
                Toast.makeText(activity, "MCP 服务已启动", Toast.LENGTH_SHORT).show();
            }
            notifyStatus();
            return false;
        }
        pendingShizukuPermissionRequest = true;
        requestShizukuPermissionIfNeededDelayed();
        Intent intent = new Intent(activity, McpService.class);
        intent.setAction(McpService.ACTION_START);
        intent.putExtra(McpService.EXTRA_PORT, normalizePort(port));
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                activity.startForegroundService(intent);
            } else {
                activity.startService(intent);
            }
            if (!quiet) {
                Toast.makeText(activity, "已请求启动服务", Toast.LENGTH_SHORT).show();
            } else {
                McpService.addLog("自动启动 MCP：" + normalizePort(port));
            }
        } catch (Exception e) {
            Toast.makeText(activity, "启动服务失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            McpService.addLog("Activity 启动服务失败：" + e.getMessage());
        }
        scheduleStatusRefresh();
        return true;
    }

    public void stopMcpService() {
        Intent intent = new Intent(activity, McpService.class);
        intent.setAction(McpService.ACTION_STOP);
        try {
            activity.startService(intent);
            Toast.makeText(activity, "已请求停止服务", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(activity, "停止服务失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            McpService.addLog("Activity 停止服务失败：" + e.getMessage());
        }
        scheduleStatusRefresh();
    }

    public int normalizePort(int port) {
        if (port < 1024 || port > 65535) {
            Toast.makeText(activity, "端口无效，已回退到 8800", Toast.LENGTH_SHORT).show();
            return McpService.DEFAULT_PORT;
        }
        return port;
    }

    public int parsePort(String portText) {
        try {
            String text = portText == null ? "" : portText.trim();
            int port = Integer.parseInt(text.isEmpty()
                    ? String.valueOf(McpService.DEFAULT_PORT) : text);
            return normalizePort(port);
        } catch (Exception e) {
            Toast.makeText(activity, "端口解析失败，已回退到 8800", Toast.LENGTH_SHORT).show();
            return McpService.DEFAULT_PORT;
        }
    }

    public void openAllFilesPermissionPage() {
        if (hasFilePermission()) {
            Toast.makeText(activity, "文件权限已授权", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                McpService.addLog("已打开所有文件访问设置页");
                return;
            }
        } catch (Exception e) {
            McpService.addLog("打开所有文件访问设置失败：" + e.getMessage());
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            McpService.addLog("已打开应用设置页");
        } catch (Exception e) {
            McpService.addLog("打开应用设置页失败：" + e.getMessage());
        }
    }

    public boolean hasFilePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public String addressText() {
        return "http://127.0.0.1:" + McpService.getCurrentPort() + "/mcp";
    }

    public String logText() {
        String text = McpService.getLogText();
        return text == null || text.isEmpty() ? "暂无日志" : text;
    }

    public String shizukuStatusText() {
        try {
            if (!Shizuku.pingBinder()) {
                return "未运行";
            }
            if (Shizuku.checkSelfPermission() != 0) {
                return "已运行，未授权";
            }
            return "可用";
        } catch (Throwable t) {
            return "不可用：" + t.getMessage();
        }
    }

    /** Optional explicit auth button (mcptool only does this on start). */
    public void requestShizukuAuth() {
        pendingShizukuPermissionRequest = true;
        requestShizukuPermissionIfNeededDelayed();
    }

    public void onStoragePermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != STORAGE_REQUEST_CODE) return;
        boolean granted = grantResults != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(
                activity,
                granted ? "文件权限已授予" : "文件权限未授予；可继续使用所有文件访问或 Shizuku",
                granted ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG
        ).show();
        McpService.addLog(granted ? "文件权限已授予" : "文件权限未授予");
        scheduleStatusRefresh();
    }

    private void requestStoragePermissionIfNeeded() {
        // API 30+: runtime READ/WRITE no longer grants broad access under targetSdk 30+.
        // Open all-files settings so user sees a real permission UI (mcptool targetSdk=28
        // still got the old dialog; we must use MANAGE_EXTERNAL_STORAGE here).
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                McpService.addLog("已请求所有文件访问权限");
                openAllFilesPermissionPage();
            }
            return;
        }
        // API 23-29: same as mcptool — runtime READ+WRITE dialog
        if (Build.VERSION.SDK_INT < 23) return;
        if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        activity.requestPermissions(
                new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                },
                STORAGE_REQUEST_CODE
        );
        McpService.addLog("已请求文件读写权限");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        activity.requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                STORAGE_REQUEST_CODE + 1
        );
        McpService.addLog("已请求通知权限");
    }

    private void bindShizukuCallbacks() {
        if (shizukuBound) return;
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
            shizukuBound = true;
        } catch (Throwable th) {
            McpService.addLog("绑定 Shizuku 回调失败：" + th.getMessage());
        }
    }

    private void requestShizukuBinder(String source) {
        try {
            ShizukuProvider.requestBinderForNonProviderProcess(activity);
        } catch (Throwable th) {
            McpService.addLog("Shizuku 连接失败：" + th.getMessage());
        }
    }

    private void requestShizukuPermissionIfNeededDelayed() {
        requestShizukuBinder("start-click");
        // retry a few times: Shizuku binder may arrive after first open
        mainHandler.postDelayed(this::tryRequestShizukuPermission, 600L);
        mainHandler.postDelayed(this::tryRequestShizukuPermission, 1500L);
        mainHandler.postDelayed(this::tryRequestShizukuPermission, 3000L);
    }

    private void tryRequestShizukuPermission() {
        try {
            if (!pendingShizukuPermissionRequest) {
                return;
            }
            if (!Shizuku.pingBinder()) {
                McpService.addLog("Shizuku 未连接，等待服务…");
                scheduleStatusRefresh();
                return;
            }
            requestShizukuPermissionNow();
        } catch (Throwable th) {
            Toast.makeText(activity, "请求 Shizuku 权限失败：" + th.getMessage(), Toast.LENGTH_LONG).show();
            McpService.addLog("请求 Shizuku 权限失败：" + th.getMessage());
        }
        scheduleStatusRefresh();
    }

    private void requestShizukuPermissionNow() {
        try {
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.checkSelfPermission() == 0) {
                pendingShizukuPermissionRequest = false;
                scheduleStatusRefresh();
                return;
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
            Toast.makeText(activity, "已弹出 Shizuku 授权，请允许", Toast.LENGTH_SHORT).show();
            McpService.addLog("已请求 Shizuku 权限");
        } catch (Throwable th) {
            Toast.makeText(activity, "请求 Shizuku 权限失败：" + th.getMessage(), Toast.LENGTH_LONG).show();
            McpService.addLog("请求 Shizuku 权限失败：" + th.getMessage());
        }
    }

    private void scheduleStatusRefresh() {
        notifyStatus();
        mainHandler.postDelayed(this::notifyStatus, 200L);
        mainHandler.postDelayed(this::notifyStatus, 600L);
        mainHandler.postDelayed(this::notifyStatus, 1200L);
        mainHandler.postDelayed(this::notifyStatus, 2000L);
    }

    private void notifyStatus() {
        if (statusListener != null) {
            statusListener.onStatusChanged();
        }
    }

    public static Context appContext(Context context) {
        return context.getApplicationContext();
    }
}