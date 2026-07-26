package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;

import java.io.File;

/**
 * Load / write {@link ApkModule} without going through merge UI.
 * Caller owns close/destroy.
 */
public final class ApkModuleIO {
    private ApkModuleIO() {}

    public static ApkModule load(File apkFile, SimpleApkLogger logger) throws Exception {
        if (apkFile == null || !apkFile.isFile()) {
            throw new IllegalArgumentException("apk missing: " + apkFile);
        }
        if (logger != null) {
            logger.stage("加载 APK", "Load APK");
            logger.bi("路径", "Path", apkFile.getAbsolutePath());
        }
        ApkModule module = ApkModule.loadApkFile(apkFile);
        if (logger != null) {
            logger.ok("已加载", "Loaded",
                    "package=" + safe(module.getPackageName())
                            + ", versionCode=" + module.getVersionCode());
        }
        return module;
    }

    public static void write(ApkModule module, File outApk, SimpleApkLogger logger) throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (outApk == null) throw new IllegalArgumentException("outApk is null");
        File parent = outApk.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (logger != null) {
            logger.stage("写出 APK", "Write APK");
            logger.bi("输出", "Output", outApk.getAbsolutePath());
        }
        module.writeApk(outApk);
        if (logger != null) {
            logger.ok("写出完成", "Write done", outApk.getAbsolutePath());
        }
    }

    // ARSCLib-1.3.9 ApkModule has no public set/getCompressionLevel.
    // Compression is controlled inside the library / write path.

    /** load → caller mutates → write. Does not sign. */
    public static void transform(
            File inputApk,
            File outputApk,
            Transformer transformer,
            SimpleApkLogger logger
    ) throws Exception {
        if (transformer == null) throw new IllegalArgumentException("transformer is null");
        try (ApkModule module = load(inputApk, logger)) {
            transformer.apply(module, logger);
            write(module, outputApk, logger);
        }
    }

    public interface Transformer {
        void apply(ApkModule module, SimpleApkLogger logger) throws Exception;
    }

    private static String safe(String s) {
        return s == null ? "?" : s;
    }
}