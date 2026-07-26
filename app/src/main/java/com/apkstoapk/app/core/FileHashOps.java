package com.apkstoapk.app.core;

import com.apkstoapk.app.util.IoUtils;
import com.apkstoapk.app.util.SimpleApkLogger;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * File digest helpers for APK/artifacts.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class FileHashOps {
    private FileHashOps() {}

    public static byte[] sha256(File file) throws Exception {
        return digest(file, "SHA-256");
    }

    public static byte[] md5(File file) throws Exception {
        return digest(file, "MD5");
    }

    public static String sha256Hex(File file) throws Exception {
        return toHex(sha256(file));
    }

    public static String md5Hex(File file) throws Exception {
        return toHex(md5(file));
    }

    public static String sha256Hex(File file, SimpleApkLogger logger) throws Exception {
        if (logger != null) {
            logger.stage("计算 SHA-256", "Compute SHA-256");
            logger.bi("文件", "File", file.getAbsolutePath());
        }
        String hex = sha256Hex(file);
        if (logger != null) {
            logger.ok("SHA-256", "SHA-256", hex);
        }
        return hex;
    }

    public static long size(File file) {
        if (file == null || !file.isFile()) return -1L;
        return file.length();
    }

    public static byte[] digest(File file, String algorithm) throws Exception {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("file missing: " + file);
        }
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (InputStream in = IoUtils.getInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    public static String toHex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }
}