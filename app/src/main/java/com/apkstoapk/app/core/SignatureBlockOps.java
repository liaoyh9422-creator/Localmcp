package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;

import java.io.File;

/**
 * APK signing block dump/load helpers (v2/v3 block inside APK, not apksig re-sign).
 * Not wired into UI / {@link ApksMerger}.
 */
public final class SignatureBlockOps {
    private SignatureBlockOps() {}

    public static boolean hasSignatureBlock(ApkModule module) {
        return module != null && module.hasSignatureBlock();
    }

    public static void dumpBlock(ApkModule module, File outFile, SimpleApkLogger logger)
            throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (outFile == null) throw new IllegalArgumentException("outFile is null");
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        module.dumpSignatureBlock(outFile);
        if (logger != null) {
            logger.ok("签名块已导出", "Signature block dumped", outFile.getAbsolutePath());
        }
    }

    public static void dumpInfoFiles(ApkModule module, File directory, SimpleApkLogger logger)
            throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (directory == null) throw new IllegalArgumentException("directory is null");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create dir: " + directory);
        }
        module.dumpSignatureInfoFiles(directory);
        if (logger != null) {
            logger.ok("签名块分片已导出", "Signature info files dumped",
                    directory.getAbsolutePath());
        }
    }

    public static void loadBlock(ApkModule module, File blockFile, SimpleApkLogger logger)
            throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (blockFile == null || !blockFile.isFile()) {
            throw new IllegalArgumentException("block missing: " + blockFile);
        }
        module.loadSignatureBlock(blockFile);
        if (logger != null) {
            logger.ok("签名块已加载", "Signature block loaded", blockFile.getAbsolutePath());
        }
    }

    public static void scanInfoFiles(ApkModule module, File directory, SimpleApkLogger logger)
            throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (directory == null || !directory.isDirectory()) {
            throw new IllegalArgumentException("directory missing: " + directory);
        }
        module.scanSignatureInfoFiles(directory);
        if (logger != null) {
            logger.ok("签名分片已扫描加载", "Signature info scanned",
                    directory.getAbsolutePath());
        }
    }

    /** Clear in-memory signature block so writeApk won't keep old block. */
    public static void clear(ApkModule module, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        module.setApkSignatureBlock(null);
        if (logger != null) {
            logger.ok("签名块已清除", "Signature block cleared");
        }
    }
}