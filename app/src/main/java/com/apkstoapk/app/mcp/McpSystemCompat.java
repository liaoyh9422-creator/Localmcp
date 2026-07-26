package com.apkstoapk.app.mcp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/** System helpers shared by MCP tools and Settings UI (battery keep-alive, Shizuku shell). */
public final class McpSystemCompat {
    private McpSystemCompat() {}

    public static boolean isBatteryUnrestricted(Context context) {
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                return powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static JsonObject batteryStatus(Context context) {
        JsonObject result = new JsonObject();
        boolean unrestricted = isBatteryUnrestricted(context);
        result.addProperty("package", context.getPackageName());
        result.addProperty("unrestricted", unrestricted);
        result.addProperty("policy", unrestricted ? "无限制 / 已忽略电池优化" : "可能受限 / 未忽略电池优化");
        result.addProperty("can_request_system_ignore", true);
        return result;
    }

    /** 打开系统“忽略电池优化”请求页；已无限制时不重复打开。 */
    public static JsonObject openBatteryOptimizationSettings(Context context) {
        JsonObject result = new JsonObject();
        boolean unrestricted = isBatteryUnrestricted(context);
        result.addProperty("unrestricted", unrestricted);
        if (unrestricted) {
            result.addProperty("ok", true);
            result.addProperty("opened", false);
            result.addProperty("message", "已忽略电池优化，无需再设置");
            return result;
        }
        String packageName = context.getPackageName();
        Exception last = null;
        // 优先直接请求忽略本应用
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            result.addProperty("ok", true);
            result.addProperty("opened", true);
            result.addProperty("action", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
            result.addProperty("message", "已打开系统电池优化请求页");
            return result;
        } catch (Exception e) {
            last = e;
        }
        // 回退到电池优化列表
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            result.addProperty("ok", true);
            result.addProperty("opened", true);
            result.addProperty("action", "IGNORE_BATTERY_OPTIMIZATION_SETTINGS");
            result.addProperty("message", "已打开电池优化设置列表，请手动找到本应用并设为无限制");
            return result;
        } catch (Exception e) {
            last = e;
        }
        result.addProperty("ok", false);
        result.addProperty("opened", false);
        result.addProperty("message", "无法打开电池优化设置："
                + (last == null ? "unknown" : last.getMessage()));
        return result;
    }

    static JsonObject shizukuStatus() {
        JsonObject result = new JsonObject();
        boolean running = false;
        boolean granted = false;
        String message;
        try {
            running = Shizuku.pingBinder();
            if (running) {
                granted = Shizuku.checkSelfPermission() == 0;
            }
        } catch (Throwable ignored) {
        }
        if (!running) {
            message = "Shizuku 未运行，请先打开 Shizuku 并启动服务";
        } else if (!granted) {
            message = "Shizuku 已运行，但本 APK 未授权，请先授权";
        } else {
            message = "Shizuku 可用";
        }
        result.addProperty("supported", true);
        result.addProperty("running", running);
        result.addProperty("granted", granted);
        result.addProperty("message", message);
        return result;
    }

    static JsonObject shizukuShell(JsonObject args) throws Exception {
        String cmd = args.has("cmd") ? args.get("cmd").getAsString() : "";
        if (cmd.length() == 0) {
            throw new Exception("缺少 cmd");
        }
        if (!Shizuku.pingBinder()) {
            throw new Exception("Shizuku 未运行，请先启动 Shizuku");
        }
        if (Shizuku.checkSelfPermission() != 0) {
            throw new Exception("Shizuku 未授权，请先授权");
        }
        File cwd = args.has("cwd") ? new File(args.get("cwd").getAsString()) : Environment.getExternalStorageDirectory();
        int timeout = args.has("timeout") ? args.get("timeout").getAsInt() : Integer.MAX_VALUE;
        String output = runShizukuShellCommand(cmd, cwd, timeout);
        JsonObject result = new JsonObject();
        result.addProperty("content", output);
        return result;
    }

    public static JsonObject batteryFix(Context context, JsonObject args) throws Exception {
        String mode = "shizuku";
        if (args != null && args.has("mode") && !args.get("mode").isJsonNull()) {
            mode = args.get("mode").getAsString();
        }
        if (mode == null || mode.isEmpty()) {
            mode = "shizuku";
        }
        JsonObject before = batteryStatus(context);
        JsonObject result = new JsonObject();
        if ("system".equalsIgnoreCase(mode)) {
            JsonObject opened = openBatteryOptimizationSettings(context);
            result.addProperty("mode", "system");
            result.add("before", before);
            result.add("after", batteryStatus(context));
            result.addProperty("ok", opened.has("ok") && opened.get("ok").getAsBoolean());
            result.addProperty("opened", opened.has("opened") && opened.get("opened").getAsBoolean());
            if (opened.has("message")) {
                result.addProperty("message", opened.get("message").getAsString());
            }
            if (opened.has("action")) {
                result.addProperty("action", opened.get("action").getAsString());
            }
            return result;
        }
        String shellResult = applyBatteryUnrestrictedByShizuku(context);
        JsonObject after = batteryStatus(context);
        result.addProperty("mode", "shizuku");
        result.add("before", before);
        result.add("after", after);
        result.addProperty("ok", after.has("unrestricted") && after.get("unrestricted").getAsBoolean());
        result.addProperty("result", shellResult);
        result.addProperty("message",
                after.get("unrestricted").getAsBoolean()
                        ? "已通过 Shizuku 尝试设为后台无限制"
                        : "已执行 Shizuku 保活命令，请返回后刷新状态确认");
        return result;
    }

    static String applyBatteryUnrestrictedByShizuku(Context context) throws Exception {
        if (!Shizuku.pingBinder()) {
            throw new Exception("Shizuku 未运行，请先启动 Shizuku");
        }
        if (Shizuku.checkSelfPermission() != 0) {
            throw new Exception("Shizuku 未授权，请先授权");
        }
        String packageName = context.getPackageName();
        String cmd = "cmd deviceidle whitelist +" + packageName
                + " 2>&1; cmd appops set " + packageName + " RUN_ANY_IN_BACKGROUND allow 2>&1;"
                + " cmd appops set " + packageName + " RUN_IN_BACKGROUND allow 2>&1;"
                + " cmd appops set " + packageName + " WAKE_LOCK allow 2>&1;"
                + " cmd appops set " + packageName + " START_FOREGROUND allow 2>&1;"
                + " cmd package set-stopped-state " + packageName + " false 2>&1;"
                + " dumpsys deviceidle whitelist | grep " + packageName + " 2>&1";
        return runShizukuShellCommand(cmd, Environment.getExternalStorageDirectory(), Integer.MAX_VALUE);
    }

    static String runShizukuShellCommand(String cmd, File cwd, int timeout) throws Exception {
        ShizukuExecResult execResult = runShizukuCommand(cmd, cwd, timeout);
        StringBuilder sb = new StringBuilder();
        sb.append("mode:Shizuku\n");
        sb.append("cmd:").append(cmd).append('\n');
        sb.append("cwd:").append(cwd == null ? "" : cwd.getAbsolutePath()).append('\n');
        sb.append("elapsed_ms:").append(execResult.elapsedMs).append('\n');
        sb.append("exit_code:").append(execResult.exited ? String.valueOf(execResult.exitCode) : "timeout").append('\n');
        sb.append("----- stdout -----\n");
        sb.append(execResult.stdout.length() == 0 ? "(空)" : execResult.stdout).append('\n');
        sb.append("----- stderr -----\n");
        sb.append(execResult.stderr.length() == 0 ? "(空)" : execResult.stderr);
        return sb.toString();
    }

    static String runShizukuCommandRaw(String cmd, File cwd, int timeout) throws Exception {
        ShizukuExecResult execResult = runShizukuCommand(cmd, cwd, timeout);
        if (!execResult.exited) {
            throw new Exception("Shizuku 命令超时");
        }
        if (execResult.exitCode != 0) {
            String message = execResult.stderr == null || execResult.stderr.length() == 0 ? execResult.stdout : execResult.stderr;
            throw new Exception("Shizuku 命令失败(exit=" + execResult.exitCode + ")：" + message);
        }
        return execResult.stdout;
    }

    private static ShizukuExecResult runShizukuCommand(String cmd, File cwd, int timeout) throws Exception {
        if (cmd == null || cmd.length() == 0) {
            return new ShizukuExecResult("", "", 0, true, 0L);
        }
        long startAt = System.currentTimeMillis();
        Process process = startShizukuProcess(cmd, cwd);
        String stdout = readStream(process.getInputStream(), process, timeout, startAt, true);
        String stderr = readStream(process.getErrorStream(), process, timeout, startAt, false);
        int exitCode = -1;
        boolean exited = false;
        while (System.currentTimeMillis() - startAt < timeout) {
            try {
                exitCode = process.exitValue();
                exited = true;
                break;
            } catch (RuntimeException ignored) {
                Thread.sleep(50L);
            }
        }
        if (!exited) {
            try { process.destroy(); } catch (Exception ignored) {}
        }
        return new ShizukuExecResult(stdout, stderr, exitCode, exited, System.currentTimeMillis() - startAt);
    }

    static Process startShizukuProcess(String cmd, File cwd) throws Exception {
        String absolutePath = (cwd == null || !cwd.exists() || !cwd.isDirectory()) ? null : cwd.getAbsolutePath();
        String[] command = {"sh", "-c", cmd};
        StringBuilder errors = new StringBuilder();
        try {
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            try {
                Method declaredMethod = shizukuClass.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                declaredMethod.setAccessible(true);
                Object result = declaredMethod.invoke(null, command, null, absolutePath);
                if (result instanceof Process) {
                    return (Process) result;
                }
                appendReflectError(errors, "Shizuku.newProcess", "返回类型不是 Process");
            } catch (Exception e) {
                appendReflectError(errors, "Shizuku.newProcess", e);
            }
            try {
                Method publicMethod = shizukuClass.getMethod("newProcess", String[].class, String[].class, String.class);
                Object result = publicMethod.invoke(null, command, null, absolutePath);
                if (result instanceof Process) {
                    return (Process) result;
                }
                appendReflectError(errors, "Shizuku.newProcess(public)", "返回类型不是 Process");
            } catch (Exception e) {
                appendReflectError(errors, "Shizuku.newProcess(public)", e);
            }
        } catch (Exception e) {
            appendReflectError(errors, "Shizuku class", e);
        }
        try {
            Class<?> remoteProcessClass = Class.forName("rikka.shizuku.ShizukuRemoteProcess");
            try {
                Constructor<?> declaredConstructor = remoteProcessClass.getDeclaredConstructor(String[].class, String[].class, String.class);
                declaredConstructor.setAccessible(true);
                Object result = declaredConstructor.newInstance(command, null, absolutePath);
                if (result instanceof Process) {
                    return (Process) result;
                }
                appendReflectError(errors, "ShizukuRemoteProcess", "返回类型不是 Process");
            } catch (Exception e) {
                appendReflectError(errors, "ShizukuRemoteProcess", e);
            }
            try {
                Constructor<?> constructor = remoteProcessClass.getConstructor(String[].class, String[].class, String.class);
                Object result = constructor.newInstance(command, null, absolutePath);
                if (result instanceof Process) {
                    return (Process) result;
                }
                appendReflectError(errors, "ShizukuRemoteProcess(public)", "返回类型不是 Process");
            } catch (Exception e) {
                appendReflectError(errors, "ShizukuRemoteProcess(public)", e);
            }
        } catch (Exception e) {
            appendReflectError(errors, "ShizukuRemoteProcess class", e);
        }
        throw new Exception("Shizuku shell 启动失败：" + errors);
    }

    private static String readStream(InputStream inputStream, Process process, int timeout, long startAt, boolean swallow) throws Exception {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            while (true) {
                if (System.currentTimeMillis() - startAt >= timeout) {
                    break;
                }
                if (inputStream.available() <= 0) {
                    try {
                        process.exitValue();
                        break;
                    } catch (RuntimeException ignored) {
                        Thread.sleep(30L);
                        continue;
                    }
                }
                int read = inputStream.read(buffer);
                if (read < 0) {
                    break;
                }
                outputStream.write(buffer, 0, read);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { inputStream.close(); } catch (Exception ignored) {}
        }
    }

    private static void appendReflectError(StringBuilder sb, String name, Exception exc) {
        Throwable cause = exc.getCause();
        String message = (cause == null || cause.getMessage() == null) ? exc.getMessage() : cause.getMessage();
        if (message == null) {
            message = exc.getClass().getName();
        }
        appendReflectError(sb, name, message);
    }

    private static void appendReflectError(StringBuilder sb, String name, String message) {
        if (sb.length() > 0) {
            sb.append('；');
        }
        sb.append(name).append('：').append(message);
    }

    private static final class ShizukuExecResult {
        final String stdout;
        final String stderr;
        final int exitCode;
        final boolean exited;
        final long elapsedMs;

        ShizukuExecResult(String stdout, String stderr, int exitCode, boolean exited, long elapsedMs) {
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exitCode = exitCode;
            this.exited = exited;
            this.elapsedMs = elapsedMs;
        }
    }
}
