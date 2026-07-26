package com.apkstoapk.app.core;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.apkstoapk.app.util.IoUtils;
import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ArchiveFile;
import com.reandroid.archive.InputSource;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;

/**
 * Core APKS/XAPK/APKM/split-dir -> single APK merger.
 * Logic adapted from AntiSplit-M / REAndroid APKEditor merge path.
 *
 * Flow:
 * extract splits -> merge modules -> search/edit/save AndroidManifest.xml -> write apk -> sign
 */
public class ApksMerger {
    public static class Options {
        /** If true, sign with embedded debug keystore after merge. */
        public boolean sign = true;
        /** Force merge even when versionCode mismatches. */
        public boolean force = false;
        /** ZIP compression level 0-9, default BEST_COMPRESSION. */
        public int compressionLevel = Deflater.BEST_COMPRESSION;
        /**
         * Split entry names that should be skipped (e.g. "split_config.xxhdpi.apk").
         * Empty = include all.
         */
        public List<String> splitsToExclude = Collections.emptyList();
        /** Optional explicit output file. If null, written under workDir. */
        public File outputFile;
        /** Auto remove split-related manifest attributes/meta-data. Default true. */
        public boolean autoEditManifest = true;
        /**
         * Always rewrite android:extractNativeLibs false -> true.
         * Kept for compatibility; currently always applied in ManifestWorkflow.
         */
        public boolean forceExtractNativeLibsTrue = true;
        /** Directory to export readable AndroidManifest.xml. Optional. */
        public File manifestExportDir;
        /**
         * If true, apply default DEX strategies after merge (before write/sign).
         */
        public boolean patchDex = false;
        /** Optional custom DEX clear targets. Null/empty uses DetectionPopup defaults. */
        public List<DexPatcher.Target> dexPatchTargets;
        /** When patchDex: remove ApplicationMain ->p invokes. Default true. */
        public boolean removeInvokeP = true;
        /** When patchDex: inject loadLibrary into UnityPlayerActivity.onCreate. Default true. */
        public boolean injectLoadLibrary = true;
        /**
          * Optional .so files to place into lib/arm64-v8a/ inside merged APK.
          */
        public List<Uri> soUris = Collections.emptyList();
        /**
         * Optional local .so files (MCP / file paths). Injected into lib/&lt;soAbi&gt;/.
         * Applied after soUris.
         */
        public List<File> soFiles = Collections.emptyList();
        /** ABI directory for {@link #soFiles}. Default arm64-v8a. */
        public String soAbi = "arm64-v8a";
        /** Optional new applicationId / manifest package. Null/blank = keep. */
        public String packageName;
        /** Optional android:versionName. Null/blank = keep. */
        public String versionName;
        /** Optional launcher/application label. Null/blank = keep. */
        public String appLabel;
        /**
         * Optional android:versionCode. Null = keep.
         * Applied after {@link ManifestWorkflow#applyIdentity}.
         */
        public Integer versionCode;
    }

    private final Context context;
    private final SimpleApkLogger logger;

    public ApksMerger(Context context, SimpleApkLogger logger) {
        this.context = context.getApplicationContext();
        this.logger = logger != null ? logger : new SimpleApkLogger();
    }

    public SimpleApkLogger getLogger() {
        return logger;
    }

    /** List .apk entries inside an apks/xapk/apkm/zip container. */
    public List<String> listSplits(Uri splitContainerUri) throws IOException {
        File temp = null;
        ArchiveFile archive = null;
        try {
            File readable = tryResolveReadableFile(splitContainerUri);
            if (readable == null) {
                temp = new File(context.getCacheDir(), "list_" + System.currentTimeMillis() + ".bin");
                IoUtils.copy(splitContainerUri, context, temp);
                readable = temp;
            }
            archive = new ArchiveFile(readable);
            List<String> names = new ArrayList<>();
            for (InputSource source : archive.getInputSources()) {
                String name = source.getName();
                if (name != null && name.toLowerCase(Locale.US).endsWith(".apk")) {
                    names.add(name);
                }
            }
            Collections.sort(names);
            return names;
        } finally {
            if (archive != null) {
                try {
                    archive.close();
                } catch (Exception ignored) {
                }
            }
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    public MergeResult mergeUri(Uri inputUri, Options options) throws Exception {
        long start = System.currentTimeMillis();
        Options opt = options != null ? options : new Options();
        File workDir = new File(context.getCacheDir(), "merge_" + System.currentTimeMillis());
        if (!workDir.mkdirs() && !workDir.isDirectory()) {
            throw new IOException("Cannot create work dir: " + workDir);
        }
        try {
            logger.stage("开始转换", "Start conversion");
            extractSelectedApks(inputUri, workDir, opt.splitsToExclude);
            File out = mergeDirectoryInternal(workDir, opt);
            if (out == null) {
                throw new IOException("Merge cancelled");
            }
            logger.ok("转换完成", "Conversion finished", out.getAbsolutePath());
            return new MergeResult(out, opt.sign, logger.getLines(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            IoUtils.deleteRecursively(workDir);
            throw e;
        }
    }

    public MergeResult mergeDirectory(File splitDir, Options options) throws Exception {
        long start = System.currentTimeMillis();
        Options opt = options != null ? options : new Options();
        logger.stage("开始转换（分包目录）", "Start conversion (split directory)");
        File out = mergeDirectoryInternal(splitDir, opt);
        if (out == null) {
            throw new IOException("Merge cancelled");
        }
        logger.ok("转换完成", "Conversion finished", out.getAbsolutePath());
        return new MergeResult(out, opt.sign, logger.getLines(), System.currentTimeMillis() - start);
    }

    private File mergeDirectoryInternal(File workDir, Options opt) throws Exception {
        logger.stage("扫描分包模块", "Scan split modules");
        logger.bi("工作目录", "Work directory", workDir.getAbsolutePath());
        try (ApkBundle bundle = new ApkBundle(opt.compressionLevel)) {
            bundle.setAPKLogger(logger);
            try {
                bundle.loadApkDirectory(workDir, true);
            } catch (FileNotFoundException e) {
                throw new IOException("No split APK modules found in: " + workDir, e);
            }
            int moduleCount = bundle.getApkModuleList().size();
            logger.ok("找到模块", "Modules found", String.valueOf(moduleCount));

            logger.stage("合并模块", "Merge modules");
            try (ApkModule mergedModule = bundle.mergeModules(opt.force)) {
                logger.stage("处理 AndroidManifest.xml", "Process AndroidManifest.xml");
                // auto sanitize + force extractNativeLibs=true
                // exportDir=null: do NOT write external AndroidManifest.xml files
                ManifestWorkflow.Result manifestResult = ManifestWorkflow.process(
                        mergedModule,
                        logger,
                        null,
                        opt.autoEditManifest,
                        opt.forceExtractNativeLibsTrue
                );
                if (manifestResult == null) {
                    logger.warn("Manifest 处理失败", "Manifest processing failed");
                    return null;
                }

                // Ensure again right before write (in case later steps touch manifest).
                if (opt.forceExtractNativeLibsTrue) {
                    ManifestWorkflow.forceExtractNativeLibsTrue(mergedModule, logger);
                }

                ManifestWorkflow.applyIdentity(
                        mergedModule,
                        logger,
                        opt.packageName,
                        opt.versionName,
                        opt.appLabel
                );

                if (opt.versionCode != null) {
                    ManifestOps.setVersionCode(mergedModule, opt.versionCode, logger);
                }

                if (opt.patchDex) {
                    List<DexPatcher.Target> targets = opt.dexPatchTargets;
                    if (targets == null || targets.isEmpty()) {
                        targets = DexPatcher.defaultClearTargets();
                    }
                    // Default strategies:
                    // 1) clear DetectionPopup methods
                    // 2) remove ApplicationMain ->p invokes
                    // 3) inject System.loadLibrary("Widget") into UnityPlayerActivity.onCreate
                    DexPatcher.Result dexResult = DexPatcher.apply(
                            mergedModule, logger, targets, opt.removeInvokeP, opt.injectLoadLibrary);
                    logger.bi("DEX 补丁结果", "DEX patch result",
                            "clear=" + dexResult.methodsCleared
                                    + ", removeP=" + dexResult.invokePRemoved
                                    + ", inject=" + dexResult.loadLibraryInjected);
                }

                if (opt.soUris != null && !opt.soUris.isEmpty()) {
                    File soCache = new File(workDir, "so_inject");
                    SoInjector.Result soResult = SoInjector.injectUris(
                            context, mergedModule, opt.soUris, soCache, logger);
                    logger.bi("so 注入结果", "so inject result", soResult.injected + " file(s)");
                }

                if (opt.soFiles != null && !opt.soFiles.isEmpty()) {
                    String abi = opt.soAbi;
                    if (abi == null || abi.trim().isEmpty()) {
                        abi = "arm64-v8a";
                    }
                    EntryOps.Result soFilesResult = EntryOps.injectSos(
                            mergedModule, opt.soFiles, abi, logger);
                    logger.bi("so 文件注入结果", "so files inject result",
                            soFilesResult.count + " file(s) → lib/" + abi + "/");
                }

                logger.stage("写出合并 APK", "Write merged APK");
                File mergedApk = opt.outputFile != null
                        ? opt.outputFile
                        : new File(workDir, "merged_" + System.currentTimeMillis() + ".apk");
                File parent = mergedApk.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                mergedModule.writeApk(mergedApk);
                logger.ok("APK 已写出", "APK written", mergedApk.getAbsolutePath());

                if (opt.sign) {
                    logger.stage("签名 APK", "Sign APK");
                    File signed = new File(
                            mergedApk.getParentFile(),
                            stripApkExtension(mergedApk.getName()) + "-signed.apk"
                    );
                    SignHelper.signWithDebugKey(context, mergedApk, signed);
                    if (!mergedApk.equals(signed) && mergedApk.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        mergedApk.delete();
                    }
                    logger.ok("签名完成", "Signing done", signed.getAbsolutePath());
                    return signed;
                }
                logger.ok("完成（未签名）", "Done (unsigned)", mergedApk.getAbsolutePath());
                return mergedApk;
            }
        }
    }

    private void extractSelectedApks(Uri splitContainerUri, File workDir, List<String> splitsToExclude)
            throws IOException {
        File tempZip = null;
        ArchiveFile archive = null;
        try {
            logger.stage("解包输入文件", "Extract input package");
            File readable = tryResolveReadableFile(splitContainerUri);
            if (readable == null) {
                tempZip = new File(workDir, "input_" + System.currentTimeMillis() + ".zip");
                logger.bi("复制输入到缓存", "Copying input to cache", tempZip.getName());
                IoUtils.copy(splitContainerUri, context, tempZip);
                readable = tempZip;
            } else {
                logger.bi("直接使用文件", "Using file", readable.getAbsolutePath());
            }

            archive = new ArchiveFile(readable);
            int count = 0;
            for (InputSource source : archive.getInputSources()) {
                String name = source.getName();
                if (name == null || !name.toLowerCase(Locale.US).endsWith(".apk")) {
                    if (name != null) {
                        logger.item("跳过非 APK", "Skipping non-apk", name);
                    }
                    continue;
                }
                String simpleName = new File(name).getName();
                if (shouldExclude(simpleName, name, splitsToExclude)) {
                    logger.item("跳过未选分包", "Skipping unselected", name);
                    continue;
                }
                File out = new File(workDir, simpleName);
                if (!out.getCanonicalPath().startsWith(workDir.getCanonicalPath() + File.separator)
                        && !out.getCanonicalPath().equals(workDir.getCanonicalPath())) {
                    logger.warn("跳过非法路径", "Skipped invalid path", name);
                    continue;
                }
                try (InputStream in = source.openStream()) {
                    IoUtils.copy(in, out);
                }
                count++;
                logger.item("已提取", "Extracted", simpleName);
            }
            if (count == 0) {
                throw new IOException("No APK modules extracted from input");
            }
            logger.ok("提取完成", "Extraction finished", count + " module(s)");
        } finally {
            if (archive != null) {
                try {
                    archive.close();
                } catch (Exception ignored) {
                }
            }
            if (tempZip != null) {
                //noinspection ResultOfMethodCallIgnored
                tempZip.delete();
            }
        }
    }

    private static boolean shouldExclude(String simpleName, String fullName, List<String> excludes) {
        if (excludes == null || excludes.isEmpty()) {
            return false;
        }
        for (String ex : excludes) {
            if (ex == null) continue;
            if (ex.equals(simpleName) || ex.equals(fullName) || fullName.endsWith("/" + ex)) {
                return true;
            }
        }
        return false;
    }

    private File tryResolveReadableFile(Uri uri) {
        if (uri == null) return null;
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (!TextUtils.isEmpty(path)) {
                    File f = new File(path);
                    if (f.canRead()) return f;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String stripApkExtension(String name) {
        if (name == null) return "merged";
        if (name.toLowerCase(Locale.US).endsWith(".apk")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }
}