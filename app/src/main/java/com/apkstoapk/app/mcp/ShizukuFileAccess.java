package com.apkstoapk.app.mcp;

import android.util.Base64;
import java.io.File;

final class ShizukuFileAccess {
    private static final int TIMEOUT_MS = 15000;

    private ShizukuFileAccess() {
    }

    static boolean isAvailable() {
        try {
            return rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean exists(File file) {
        return "1".equals(runBooleanCommand(file, "-e"));
    }

    static boolean isDirectory(File file) {
        return "1".equals(runBooleanCommand(file, "-d"));
    }

    static boolean isRegularFile(File file) {
        return "1".equals(runBooleanCommand(file, "-f"));
    }

    static String readText(File file) throws Exception {
        return runRequired("cat " + quote(file.getAbsolutePath()));
    }

    static byte[] readBytes(File file) throws Exception {
        String base64 = runRequired("base64 < " + quote(file.getAbsolutePath()));
        return Base64.decode(base64, Base64.DEFAULT);
    }

    private static String runBooleanCommand(File file, String testFlag) {
        if (!isAvailable()) {
            return "0";
        }
        try {
            String result = McpSystemCompat.runShizukuCommandRaw(
                    "if [ " + testFlag + " " + quote(file.getAbsolutePath()) + " ]; then printf 1; else printf 0; fi",
                    file.getParentFile(),
                    TIMEOUT_MS
            );
            return result == null ? "0" : result.trim();
        } catch (Exception ignored) {
            return "0";
        }
    }

    private static String runRequired(String command) throws Exception {
        return McpSystemCompat.runShizukuCommandRaw(command, new File("/sdcard"), TIMEOUT_MS);
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }
}
