package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.FrameworkApk;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;

import java.io.File;

/**
 * Load Android framework resource table so manifest attribute ids decode to names
 * instead of r0x........
 */
public final class FrameworkHelper {
    private static final String[] CANDIDATES = new String[]{
            "/system/framework/framework-res.apk",
            "/system/framework/framework-res-vext.apk",
            "/system_ext/framework/framework-res.apk",
            "/product/framework/framework-res.apk",
            "/vendor/framework/framework-res.apk"
    };

    private static volatile FrameworkApk cachedSystemFramework;

    private FrameworkHelper() {}

    public static void ensureAndroidFramework(ApkModule module, SimpleApkLogger logger) {
        if (module == null) return;
        try {
            TableBlock tableBlock = null;
            try {
                tableBlock = module.getTableBlock();
            } catch (Exception ignored) {
            }
            if (tableBlock == null) {
                if (logger != null) {
                    logger.warn("无 resources.arsc，跳过 framework 关联",
                            "No resources.arsc; skip framework link");
                }
                return;
            }
            if (tableBlock.hasFramework()) {
                if (logger != null) {
                    logger.bi("Framework 已关联", "Framework already linked");
                }
            }

            // Prefer REAndroid internal frameworks if present in classpath
            try {
                FrameworkApk fw = module.initializeAndroidFramework((Integer) null);
                if (fw != null) {
                    if (logger != null) {
                        logger.ok("已加载内置 Android framework",
                                "Loaded internal android framework",
                                fw.getName() + " (" + fw.getVersionName() + ")");
                    }
                    relinkManifest(module);
                    return;
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.warn("内置 framework 不可用",
                            "Internal framework unavailable",
                            t.getMessage());
                }
            }

            FrameworkApk systemFw = getSystemFramework(logger);
            if (systemFw != null) {
                tableBlock.addFramework(systemFw.getTableBlock());
                if (logger != null) {
                    logger.ok("已关联系统 framework-res.apk",
                            "Linked system framework-res.apk for attribute name decode");
                }
                relinkManifest(module);
            } else if (logger != null) {
                logger.warn("未找到系统 framework，将使用内置属性名映射",
                        "No system framework found; will use built-in attr name map");
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.err("Framework 加载失败", "Framework load failed", t.getMessage());
            }
        }
    }

    private static void relinkManifest(ApkModule module) {
        try {
            AndroidManifestBlock manifest = module.getAndroidManifest();
            if (manifest == null || !module.hasTableBlock()) return;
            if (manifest.getPackageBlock() == null && module.hasTableBlock()) {
                TableBlock tableBlock = module.getTableBlock();
                PackageBlock packageBlock = tableBlock.pickOne(manifest.guessCurrentPackageId());
                if (packageBlock == null) {
                    packageBlock = tableBlock.pickOne();
                }
                if (packageBlock != null) {
                    manifest.setPackageBlock(packageBlock);
                }
            }
            manifest.setApkFile(module);
        } catch (Exception ignored) {
        }
    }

    public static synchronized FrameworkApk getSystemFramework(SimpleApkLogger logger) {
        if (cachedSystemFramework != null && !cachedSystemFramework.isDestroyed()) {
            return cachedSystemFramework;
        }
        for (String path : CANDIDATES) {
            if (path == null || !path.endsWith(".apk")) continue;
            File file = new File(path);
            if (!file.isFile() || !file.canRead()) continue;
            try {
                FrameworkApk fw = FrameworkApk.loadApkFile(file);
                cachedSystemFramework = fw;
                if (logger != null) {
                    logger.ok("使用 framework", "Using framework",
                            file.getAbsolutePath() + " (" + fw.getVersionName() + ")");
                }
                return fw;
            } catch (Throwable t) {
                if (logger != null) {
                    logger.item("跳过 framework", "Skip framework",
                            path + ": " + t.getMessage());
                }
            }
        }
        return null;
    }
}
