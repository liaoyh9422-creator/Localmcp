package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.zip.Deflater;

/**
 * File-based split-directory merge without UI / {@link ApksMerger} pipeline.
 * Does not sign, inject so, or patch dex. Caller composes those via other Ops.
 *
 * Typical:
 *   SplitOps.extractApks(container, workDir, exclude, log);
 *   MergeOps.mergeDirectory(workDir, outApk, false, Deflater.BEST_COMPRESSION, log);
 *   // optional: load outApk and ManifestOps / DexOps / SignOps
 */
public final class MergeOps {
    private MergeOps() {}

    public static final class Options {
        /** Force merge when versionCode mismatches. */
        public boolean force = false;
        /** ZIP compression 0-9. */
        public int compressionLevel = Deflater.BEST_COMPRESSION;
        /** Clear split-related manifest fields after merge. Default true. */
        public boolean sanitizeSplitManifest = true;
        /** Force extractNativeLibs=true after merge. Default true. */
        public boolean forceExtractNativeLibsTrue = true;
        public File outputFile;
    }

    public static final class Result {
        public final File outputApk;
        public final long elapsedMs;

        public Result(File outputApk, long elapsedMs) {
            this.outputApk = outputApk;
            this.elapsedMs = elapsedMs;
        }
    }

    public static Result mergeDirectory(File splitDir, File outputApk, SimpleApkLogger logger)
            throws Exception {
        Options opt = new Options();
        opt.outputFile = outputApk;
        return mergeDirectory(splitDir, opt, logger);
    }

    public static Result mergeDirectory(
            File splitDir,
            File outputApk,
            boolean force,
            int compressionLevel,
            SimpleApkLogger logger
    ) throws Exception {
        Options opt = new Options();
        opt.outputFile = outputApk;
        opt.force = force;
        opt.compressionLevel = compressionLevel;
        return mergeDirectory(splitDir, opt, logger);
    }

    public static Result mergeDirectory(File splitDir, Options options, SimpleApkLogger logger)
            throws Exception {
        if (splitDir == null || !splitDir.isDirectory()) {
            throw new IllegalArgumentException("splitDir missing: " + splitDir);
        }
        Options opt = options != null ? options : new Options();
        if (opt.outputFile == null) {
            throw new IllegalArgumentException("options.outputFile is null");
        }
        long start = System.currentTimeMillis();
        if (logger != null) {
            logger.stage("合并分包目录", "Merge split directory");
            logger.bi("目录", "Directory", splitDir.getAbsolutePath());
            logger.bi("输出", "Output", opt.outputFile.getAbsolutePath());
        }

        try (ApkBundle bundle = new ApkBundle(opt.compressionLevel)) {
            if (logger != null) {
                bundle.setAPKLogger(logger);
            }
            try {
                bundle.loadApkDirectory(splitDir, true);
            } catch (FileNotFoundException e) {
                throw new IOException("No split APK modules found in: " + splitDir, e);
            }
            if (logger != null) {
                logger.ok("找到模块", "Modules found",
                        String.valueOf(bundle.getApkModuleList().size()));
            }

            try (ApkModule merged = bundle.mergeModules(opt.force)) {
                if (opt.sanitizeSplitManifest) {
                    ManifestWorkflow.sanitizeSplitInfo(merged, logger);
                    try {
                        com.reandroid.apk.AndroidManifestBlockSplitSanitizer sanitizer =
                                new com.reandroid.apk.AndroidManifestBlockSplitSanitizer();
                        if (sanitizer.sanitize(merged) && logger != null) {
                            logger.ok("已应用内置 split 清理器", "Built-in split sanitizer applied");
                        }
                    } catch (Throwable t) {
                        if (logger != null) {
                            logger.warn("内置 split 清理器跳过", "Built-in sanitizer skipped",
                                    t.getMessage());
                        }
                    }
                }
                if (opt.forceExtractNativeLibsTrue) {
                    ManifestWorkflow.forceExtractNativeLibsTrue(merged, logger);
                }

                File out = opt.outputFile;
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                merged.writeApk(out);
                if (logger != null) {
                    logger.ok("合并写出完成", "Merge written", out.getAbsolutePath());
                }
                return new Result(out, System.currentTimeMillis() - start);
            }
        }
    }
}