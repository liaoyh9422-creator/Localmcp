package com.apkstoapk.app.core;

import android.content.Context;

import com.apkstoapk.app.util.IoUtils;
import com.apkstoapk.app.util.SimpleApkLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Signing helpers with File/keystore paths.
 * Wraps {@link SignHelper}. Not wired into UI / {@link ApksMerger}.
 */
public final class SignOps {
    private SignOps() {}

    public static final class Options {
        public boolean v1 = true;
        public boolean v2 = true;
        public boolean v3 = true;
        /** Keystore type: BKS (Android debug assets) or JKS/PKCS12 depending on stream. */
        public String keystoreType = "BKS";
    }

    public static void signDebug(Context context, File inputApk, File outputApk, SimpleApkLogger logger)
            throws Exception {
        if (logger != null) {
            logger.stage("签名 APK (debug)", "Sign APK (debug)");
            logger.bi("输入", "Input", inputApk.getAbsolutePath());
            logger.bi("输出", "Output", outputApk.getAbsolutePath());
        }
        SignHelper.signWithDebugKey(context, inputApk, outputApk);
        if (logger != null) {
            logger.ok("签名完成", "Signing done", outputApk.getAbsolutePath());
        }
    }

    /**
     * Sign with a BKS keystore file (same path as {@link SignHelper#sign}).
     * Uses first alias in the store.
     */
    public static void signBks(
            File keystoreFile,
            String password,
            File inputApk,
            File outputApk,
            boolean v1,
            boolean v2,
            boolean v3,
            SimpleApkLogger logger
    ) throws Exception {
        if (keystoreFile == null || !keystoreFile.isFile()) {
            throw new IllegalArgumentException("keystore missing: " + keystoreFile);
        }
        if (logger != null) {
            logger.stage("签名 APK (BKS)", "Sign APK (BKS)");
            logger.bi("keystore", "keystore", keystoreFile.getAbsolutePath());
            logger.bi("输入", "Input", abs(inputApk));
            logger.bi("输出", "Output", abs(outputApk));
        }
        try (InputStream in = new FileInputStream(keystoreFile)) {
            SignHelper.sign(in, password, inputApk, outputApk, v1, v2, v3);
        }
        if (logger != null) {
            logger.ok("签名完成", "Signing done", abs(outputApk));
        }
    }

    public static void signBks(File keystoreFile, String password, File inputApk, File outputApk,
                               SimpleApkLogger logger) throws Exception {
        signBks(keystoreFile, password, inputApk, outputApk, true, true, true, logger);
    }

    /** Copy apk to destination path (export without install). */
    public static void exportCopy(File inputApk, File outputApk, SimpleApkLogger logger) throws Exception {
        if (inputApk == null || !inputApk.isFile()) {
            throw new IllegalArgumentException("input missing: " + inputApk);
        }
        if (outputApk == null) throw new IllegalArgumentException("output is null");
        if (logger != null) {
            logger.stage("导出 APK", "Export APK");
            logger.bi("源", "Source", inputApk.getAbsolutePath());
            logger.bi("目标", "Dest", outputApk.getAbsolutePath());
        }
        IoUtils.copy(inputApk, outputApk);
        if (logger != null) {
            logger.ok("导出完成", "Export done", outputApk.getAbsolutePath());
        }
    }

    private static String abs(File f) {
        return f == null ? "?" : f.getAbsolutePath();
    }
}