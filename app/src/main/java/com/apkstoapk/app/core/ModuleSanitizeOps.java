package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.AndroidManifestBlockSplitSanitizer;
import com.reandroid.apk.ApkModule;

/**
 * Split-manifest cleanup and native-lib policy helpers on a loaded module.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class ModuleSanitizeOps {
    private ModuleSanitizeOps() {}

    /** Remove splitTypes / isSplitRequired / vending split meta, etc. */
    public static void sanitizeSplitManifest(ApkModule module, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        ManifestWorkflow.sanitizeSplitInfo(module, logger);
        try {
            AndroidManifestBlockSplitSanitizer sanitizer = new AndroidManifestBlockSplitSanitizer();
            if (sanitizer.sanitize(module) && logger != null) {
                logger.ok("已应用内置 split 清理器", "Built-in split sanitizer applied");
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("内置 split 清理器跳过", "Built-in sanitizer skipped", t.getMessage());
            }
        }
    }

    public static void forceExtractNativeLibsTrue(ApkModule module, SimpleApkLogger logger) {
        ManifestWorkflow.forceExtractNativeLibsTrue(module, logger);
    }

    public static void setExtractNativeLibs(ApkModule module, boolean value, SimpleApkLogger logger) {
        ManifestOps.setExtractNativeLibs(module, value, logger);
    }

    /** sanitize split fields + force extractNativeLibs=true */
    public static void sanitizeForStandaloneApk(ApkModule module, SimpleApkLogger logger) {
        if (logger != null) {
            logger.stage("清理为可独立安装 APK", "Sanitize for standalone APK");
        }
        sanitizeSplitManifest(module, logger);
        forceExtractNativeLibsTrue(module, logger);
    }
}