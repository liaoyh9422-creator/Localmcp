package com.apkstoapk.app.mcp;

import android.content.Context;
import android.net.Uri;

import com.apkstoapk.app.core.ApkInspect;
import com.apkstoapk.app.core.ApkModuleIO;
import com.apkstoapk.app.core.ApksMerger;
import com.apkstoapk.app.core.ComponentOps;
import com.apkstoapk.app.core.DexOps;
import com.apkstoapk.app.core.DexRenameOps;
import com.apkstoapk.app.core.DexBrowserOps;
import com.apkstoapk.app.core.DexPatcher;
import com.apkstoapk.app.core.EntryOps;
import com.apkstoapk.app.core.FileHashOps;
import com.apkstoapk.app.core.InstallOps;
import com.apkstoapk.app.core.ManifestOps;
import com.apkstoapk.app.core.ManifestXmlOps;
import com.apkstoapk.app.core.MergeResult;
import com.apkstoapk.app.core.MetaDataOps;
import com.apkstoapk.app.core.ModuleSanitizeOps;
import com.apkstoapk.app.core.PackageRenameOps;
import com.apkstoapk.app.core.PermissionOps;
import com.apkstoapk.app.core.SignOps;
import com.apkstoapk.app.core.SmaliCompileOps;
import com.apkstoapk.app.core.SplitOps;
import com.apkstoapk.app.core.SplitSelector;
import com.apkstoapk.app.core.StringResOps;
import com.apkstoapk.app.core.VerifyOps;
import com.apkstoapk.app.util.SimpleApkLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.reandroid.apk.ApkModule;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * APK-domain MCP tools. Wraps existing Ops / ApksMerger with File paths.
 *
 * <p>Profiles for {@link #mergeApks}:
 * <ul>
 *   <li>{@code agent} (default): auto_exclude_device=true, patch_dex=false</li>
 *   <li>{@code ui}: auto_exclude_device=true, patch_dex=true</li>
 *   <li>{@code raw}: auto_exclude_device=false, patch_dex=false</li>
 * </ul>
 * Explicit args always override profile defaults.
 */
final class McpApkTools {
    private static final int DEFAULT_LOG_LIMIT = 80;

    private final Context context;

    McpApkTools(Context context) {
        this.context = context.getApplicationContext();
    }

    JsonObject listSplits(JsonObject args) throws Exception {
        File container = requireFile(args, "file_path");
        List<String> splits = SplitOps.listSplits(container);
        JsonObject result = new JsonObject();
        result.addProperty("path", container.getAbsolutePath());
        result.addProperty("count", splits.size());
        result.add("splits", toStringArray(splits));
        return result;
    }

    JsonObject mergeApks(JsonObject args) throws Exception {
        long start = System.currentTimeMillis();
        String path = optString(args, "file_path");
        String splitDirPath = optString(args, "split_dir");
        if ((path == null || path.isEmpty()) && (splitDirPath == null || splitDirPath.isEmpty())) {
            throw new IllegalArgumentException("path 或 split_dir 至少提供一个");
        }

        String profile = normalizeProfile(optString(args, "profile"));
        boolean defaultAutoExclude = !"raw".equals(profile);
        boolean defaultPatchDex = "ui".equals(profile);

        SimpleApkLogger logger = new SimpleApkLogger();
        logger.bi("profile", "profile", profile);

        ApksMerger.Options opt = new ApksMerger.Options();
        opt.sign = optBoolean(args, "sign", true);
        opt.force = optBoolean(args, "force", false);
        opt.autoEditManifest = optBoolean(args, "auto_edit_manifest", true);
        opt.forceExtractNativeLibsTrue = optBoolean(args, "force_extract_native_libs", true);
        opt.patchDex = optBoolean(args, "patch_dex", defaultPatchDex);
        applyDexPatchOptionsToMerger(args, opt);
        opt.packageName = blankToNull(optString(args, "package_name"));
        opt.versionName = blankToNull(optString(args, "version_name"));
        opt.appLabel = blankToNull(optString(args, "app_label"));
        opt.versionCode = optInteger(args, "version_code");
        opt.soAbi = blankToNull(optString(args, "so_abi"));
        if (opt.soAbi == null) {
            opt.soAbi = "arm64-v8a";
        }

        List<String> userExclude = readStringList(args, "exclude_splits");
        boolean autoExclude = optBoolean(args, "auto_exclude_device", defaultAutoExclude);
        List<String> autoExcluded = new ArrayList<>();
        List<String> finalExclude = new ArrayList<>(userExclude);

        List<File> soFiles = readExistingFiles(args, "so_paths");
        if (!soFiles.isEmpty()) {
            opt.soFiles = soFiles;
            logger.bi("注入 so 数量", "so_paths count", String.valueOf(soFiles.size()));
        }

        resolveOutputFile(args, opt, "merged");

        ApksMerger merger = new ApksMerger(context, logger);
        MergeResult mergeResult;

        if (splitDirPath != null && !splitDirPath.trim().isEmpty()) {
            File splitDir = new File(splitDirPath.trim());
            if (!splitDir.isDirectory()) {
                throw new IllegalArgumentException("split_dir 不是目录: " + splitDir);
            }
            if (autoExclude) {
                List<String> names = listApkNamesInDir(splitDir);
                autoExcluded = SplitSelector.excludeNotForDevice(context, names);
                mergeExcludeLists(finalExclude, autoExcluded);
                if (!autoExcluded.isEmpty()) {
                    logger.bi("按设备自动排除分包", "Auto-exclude splits for device");
                    for (String s : autoExcluded) {
                        logger.item("排除", "Exclude", s);
                    }
                }
            }
            opt.splitsToExclude = finalExclude;
            mergeResult = merger.mergeDirectory(splitDir, opt);
        } else {
            File container = requireFile(args, "file_path");
            // Friendly error when path is a plain single APK (not a split container).
            List<String> splits;
            try {
                splits = SplitOps.listSplits(container);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "NOT_SPLIT_CONTAINER: 无法作为分包容器读取，单 APK 请用 patch_apk。path="
                                + container.getAbsolutePath()
                                + " (" + e.getMessage() + ")",
                        e);
            }
            if (splits.isEmpty()) {
                throw new IllegalArgumentException(
                        "NOT_SPLIT_CONTAINER: 容器内无 .apk 分包。单 APK 改身份请用 patch_apk。path="
                                + container.getAbsolutePath());
            }
            logger.ok("分包数量", "Split count", String.valueOf(splits.size()));
            if (autoExclude) {
                autoExcluded = SplitSelector.excludeNotForDevice(context, splits);
                mergeExcludeLists(finalExclude, autoExcluded);
                if (!autoExcluded.isEmpty()) {
                    logger.bi("按设备自动排除分包", "Auto-exclude splits for device");
                    for (String s : autoExcluded) {
                        logger.item("排除", "Exclude", s);
                    }
                }
            }
            opt.splitsToExclude = finalExclude;
            mergeResult = merger.mergeUri(Uri.fromFile(container), opt);
        }

        File out = mergeResult.outputApk;
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("profile", profile);
        result.addProperty("output", out != null ? out.getAbsolutePath() : null);
        result.addProperty("signed", mergeResult.signed);
        result.addProperty("elapsed_ms", mergeResult.elapsedMs > 0
                ? mergeResult.elapsedMs
                : (System.currentTimeMillis() - start));
        result.addProperty("auto_exclude_device", autoExclude);
        result.add("excluded_splits", toStringArray(finalExclude));
        result.add("auto_excluded_splits", toStringArray(autoExcluded));
        attachIdentitySummary(result, out);
        result.add("logs", toStringArray(limitLogs(mergeResult.logs, args)));
        return result;
    }

    /**
     * Single-APK identity patch (no split merge).
     * package_name defaults to <b>true package rename</b> (DEX types + manifest).
     * Set rename_dex=false for manifest-only applicationId change.
     */
    JsonObject patchApk(JsonObject args) throws Exception {
        long start = System.currentTimeMillis();
        File input = requireFile(args, "file_path");
        String packageName = blankToNull(optString(args, "package_name"));
        String versionName = blankToNull(optString(args, "version_name"));
        String appLabel = blankToNull(optString(args, "app_label"));
        Integer versionCode = optInteger(args, "version_code");
        boolean sign = optBoolean(args, "sign", true);
        boolean verbose = optBoolean(args, "verbose_logs", false);
        // true = DEX RenameTypes + manifest (default); false = manifest package only
        boolean renameDex = optBoolean(args, "rename_dex", true);
        boolean debuggable = args != null && args.has("debuggable") && !args.get("debuggable").isJsonNull();
        Boolean debuggableValue = debuggable ? optBoolean(args, "debuggable", false) : null;
        boolean extractNativeLibs = args != null && args.has("extract_native_libs")
                && !args.get("extract_native_libs").isJsonNull();
        Boolean extractNativeLibsValue = extractNativeLibs
                ? optBoolean(args, "extract_native_libs", true) : null;
        boolean patchDex = optBoolean(args, "patch_dex", false);
        List<DexPatcher.Target> clearMethods = parseClearMethodTargets(args);
        boolean hasDexCustom = hasDexCustomArgs(args) || !clearMethods.isEmpty();
        List<String> addPermissions = readStringList(args, "add_permissions");
        List<String> removePermissions = readStringList(args, "remove_permissions");

        if (packageName == null && versionName == null && appLabel == null && versionCode == null
                && !debuggable && !extractNativeLibs && !patchDex && !hasDexCustom
                && addPermissions.isEmpty() && removePermissions.isEmpty()) {
            throw new IllegalArgumentException(
                    "至少提供 package_name / version_name / version_code / app_label "
                            + "/ debuggable / extract_native_libs / patch_dex "
                            + "/ clear_methods / remove_invoke_p / inject_load_library / permissions 之一");
        }

        File unsignedOut = resolvePatchOutput(args, input, sign);
        File parent = unsignedOut.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        SimpleApkLogger logger = new SimpleApkLogger();
        logger.stage("单包改身份", "Patch APK identity");
        logger.bi("输入", "Input", input.getAbsolutePath());
        logger.bi("写出", "Write", unsignedOut.getAbsolutePath());
        if (packageName != null) {
            logger.bi("改包模式", "Rename mode",
                    renameDex ? "true(DEX+manifest)" : "manifest-only");
        }

        ApkModuleIO.transform(input, unsignedOut, (ApkModule module, SimpleApkLogger log) -> {
            if (packageName != null) {
                if (renameDex) {
                    ManifestOps.setPackageName(module, packageName, log);
                } else {
                    ManifestOps.setPackageNameManifestOnly(module, packageName, log);
                }
            }
            if (versionName != null) {
                ManifestOps.setVersionName(module, versionName, log);
            }
            if (versionCode != null) {
                ManifestOps.setVersionCode(module, versionCode, log);
            }
            if (appLabel != null) {
                // prefer @string resource rewrite when label is a reference
                StringResOps.setAppLabelPreferResource(module, appLabel, log);
            }
            if (debuggableValue != null) {
                ManifestOps.setDebuggable(module, debuggableValue, log);
            }
            if (extractNativeLibsValue != null) {
                ManifestOps.setExtractNativeLibs(module, extractNativeLibsValue, log);
            }
            if (!addPermissions.isEmpty()) {
                PermissionOps.addAll(module, addPermissions, log);
            }
            if (!removePermissions.isEmpty()) {
                PermissionOps.removeAll(module, removePermissions, log);
            }
            if (patchDex || hasDexCustom) {
                applyDexPatchFromArgs(module, args, patchDex, clearMethods, log);
            }
        }, logger);

        File finalOut = unsignedOut;
        boolean signed = false;
        if (sign) {
            File signedFile = new File(
                    unsignedOut.getParentFile(),
                    stripApkExtension(unsignedOut.getName()) + "-signed.apk");
            // If user already asked for *-signed.apk as output, sign in place via temp.
            if (unsignedOut.getAbsolutePath().equals(signedFile.getAbsolutePath())) {
                File tmp = new File(unsignedOut.getParentFile(),
                        "patch_unsigned_" + System.currentTimeMillis() + ".apk");
                if (!unsignedOut.renameTo(tmp)) {
                    throw new IllegalStateException("无法准备签名临时文件");
                }
                SignOps.signDebug(context, tmp, unsignedOut, logger);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                finalOut = unsignedOut;
            } else {
                SignOps.signDebug(context, unsignedOut, signedFile, logger);
                if (unsignedOut.exists() && !unsignedOut.equals(signedFile)) {
                    //noinspection ResultOfMethodCallIgnored
                    unsignedOut.delete();
                }
                finalOut = signedFile;
            }
            signed = true;
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("input", input.getAbsolutePath());
        result.addProperty("output", finalOut.getAbsolutePath());
        result.addProperty("signed", signed);
        result.addProperty("elapsed_ms", System.currentTimeMillis() - start);
        attachIdentitySummary(result, finalOut);
        List<String> logs = logger.getLines();
        if (!verbose && logs != null && logs.size() > DEFAULT_LOG_LIMIT) {
            logs = logs.subList(Math.max(0, logs.size() - DEFAULT_LOG_LIMIT), logs.size());
        }
        result.add("logs", toStringArray(logs));
        return result;
    }

    JsonObject inspectApk(JsonObject args) throws Exception {
        File apk = requireFile(args, "file_path");
        SimpleApkLogger logger = new SimpleApkLogger();
        ApkInspect.Report report = ApkInspect.inspectFile(apk, logger);
        JsonObject result = new JsonObject();
        result.addProperty("path", apk.getAbsolutePath());
        result.addProperty("entry_count", report.entryCount);
        result.addProperty("has_table_block", report.hasTableBlock);
        result.add("dex_files", toStringArray(report.dexFiles));
        result.add("native_libs", toStringArray(limit(report.nativeLibs, 100)));
        result.add("abis", toStringArray(report.abis));
        result.add("assets", toStringArray(limit(report.assets, 50)));
        if (report.manifest != null) {
            result.add("manifest", snapshotJson(report.manifest));
            // Flat summary for agents
            result.addProperty("package_name", report.manifest.packageName);
            if (report.manifest.versionCode != null) {
                result.addProperty("version_code", report.manifest.versionCode);
            }
            result.addProperty("version_name", report.manifest.versionName);
            result.addProperty("app_label", report.manifest.appLabel);
        }
        return result;
    }

    JsonObject signApk(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String outputPath = optString(args, "output");
        File output;
        if (outputPath != null && !outputPath.trim().isEmpty()) {
            output = new File(outputPath.trim());
        } else {
            String name = input.getName();
            String base = name.toLowerCase(Locale.US).endsWith(".apk")
                    ? name.substring(0, name.length() - 4) : name;
            output = new File(input.getParentFile(), base + "-signed.apk");
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        SimpleApkLogger logger = new SimpleApkLogger();
        SignOps.signDebug(context, input, output, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("input", input.getAbsolutePath());
        result.addProperty("output", output.getAbsolutePath());
        result.addProperty("signed", true);
        attachIdentitySummary(result, output);
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject verifyApk(JsonObject args) throws Exception {
        File apk = requireFile(args, "file_path");
        SimpleApkLogger logger = new SimpleApkLogger();
        VerifyOps.Report report = VerifyOps.verify(apk, logger);
        JsonObject result = new JsonObject();
        result.addProperty("path", apk.getAbsolutePath());
        result.addProperty("verified", report.verified);
        result.addProperty("v1", report.v1);
        result.addProperty("v2", report.v2);
        result.addProperty("v3", report.v3);
        result.addProperty("v31", report.v31);
        result.addProperty("v4", report.v4);
        result.add("signer_subjects", toStringArray(report.signerSubjects));
        result.add("errors", toStringArray(report.errors));
        result.add("warnings", toStringArray(report.warnings));
        return result;
    }

    JsonObject installApk(JsonObject args) throws Exception {
        File apk = requireFile(args, "file_path");
        SimpleApkLogger logger = new SimpleApkLogger();
        InstallOps.install(context, apk, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("path", apk.getAbsolutePath());
        result.addProperty("message", "已调起安装器");
        return result;
    }

    JsonObject exportApk(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String outputPath = optString(args, "output");
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("output 不能为空");
        }
        File output = new File(outputPath.trim());
        File parent = output.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        SimpleApkLogger logger = new SimpleApkLogger();
        SignOps.exportCopy(input, output, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("input", input.getAbsolutePath());
        result.addProperty("output", output.getAbsolutePath());
        return result;
    }

    JsonObject hashFile(JsonObject args) throws Exception {
        File file = requireFile(args, "file_path");
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("md5", FileHashOps.md5Hex(file));
        result.addProperty("sha256", FileHashOps.sha256Hex(file));
        result.addProperty("size", file.length());
        return result;
    }

    /** Explicit true package rename tool (DEX + manifest). */
    JsonObject renamePackage(JsonObject args) throws Exception {
        long start = System.currentTimeMillis();
        File input = requireFile(args, "file_path");
        String packageName = blankToNull(optString(args, "package_name"));
        if (packageName == null) {
            throw new IllegalArgumentException("package_name 不能为空");
        }
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        logger.stage("真改包", "Rename package");
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            PackageRenameOps.rename(module, packageName, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("input", input.getAbsolutePath());
        result.addProperty("output", finalOut.getAbsolutePath());
        result.addProperty("signed", sign);
        result.addProperty("elapsed_ms", System.currentTimeMillis() - start);
        attachIdentitySummary(result, finalOut);
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject listPermissions(JsonObject args) throws Exception {
        File apk = requireFile(args, "file_path");
        SimpleApkLogger logger = new SimpleApkLogger();
        try (ApkModule module = ApkModuleIO.load(apk, logger)) {
            JsonObject result = new JsonObject();
            result.addProperty("path", apk.getAbsolutePath());
            result.add("permissions", toStringArray(PermissionOps.list(module)));
            return result;
        }
    }

    JsonObject editPermissions(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        List<String> add = readStringList(args, "add");
        List<String> remove = readStringList(args, "remove");
        if (add.isEmpty() && remove.isEmpty()) {
            throw new IllegalArgumentException("add / remove 至少提供一个");
        }
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            if (!add.isEmpty()) PermissionOps.addAll(module, add, log);
            if (!remove.isEmpty()) PermissionOps.removeAll(module, remove, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("output", finalOut.getAbsolutePath());
        result.addProperty("signed", sign);
        attachIdentitySummary(result, finalOut);
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject listComponents(JsonObject args) throws Exception {
        File apk = requireFile(args, "file_path");
        SimpleApkLogger logger = new SimpleApkLogger();
        try (ApkModule module = ApkModuleIO.load(apk, logger)) {
            JsonObject result = new JsonObject();
            result.addProperty("path", apk.getAbsolutePath());
            result.addProperty("application_class",
                    ManifestOps.requireManifest(module).getApplicationClassName());
            result.addProperty("main_activity", ComponentOps.getMainActivityClassName(module));
            result.add("activities", componentArray(ComponentOps.listActivities(module, true)));
            result.add("services", componentArray(ComponentOps.listServices(module)));
            result.add("receivers", componentArray(ComponentOps.listReceivers(module)));
            result.add("providers", componentArray(ComponentOps.listProviders(module)));
            return result;
        }
    }

    JsonObject setComponentName(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String applicationClass = blankToNull(optString(args, "application_class"));
        String mainActivity = blankToNull(optString(args, "main_activity"));
        if (applicationClass == null && mainActivity == null) {
            throw new IllegalArgumentException("application_class / main_activity 至少提供一个");
        }
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            if (applicationClass != null) {
                ComponentOps.setApplicationClassName(module, applicationClass, log);
            }
            if (mainActivity != null) {
                ComponentOps.setMainActivityClassName(module, mainActivity, log);
            }
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("output", finalOut.getAbsolutePath());
        attachIdentitySummary(result, finalOut);
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject listMetaData(JsonObject args) throws Exception {
        File apk = requireFile(args, "file_path");
        try (ApkModule module = ApkModuleIO.load(apk, null)) {
            JsonArray arr = new JsonArray();
            for (MetaDataOps.MetaItem item : MetaDataOps.list(module)) {
                JsonObject o = new JsonObject();
                o.addProperty("name", item.name);
                if (item.value != null) o.addProperty("value", item.value);
                if (item.resourceId != null) o.addProperty("resource_id", item.resourceId);
                if (item.raw != null) o.addProperty("raw", item.raw);
                arr.add(o);
            }
            JsonObject result = new JsonObject();
            result.addProperty("path", apk.getAbsolutePath());
            result.add("meta_data", arr);
            return result;
        }
    }

    JsonObject editMetaData(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String name = blankToNull(optString(args, "name"));
        String value = optString(args, "value");
        boolean remove = optBoolean(args, "remove", false);
        if (name == null) throw new IllegalArgumentException("name 不能为空");
        if (!remove && value == null) {
            throw new IllegalArgumentException("value 不能为空（或 remove=true）");
        }
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            if (remove) MetaDataOps.remove(module, name, log);
            else MetaDataOps.setString(module, name, value, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("output", finalOut.getAbsolutePath());
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject injectEntry(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String entryPath = blankToNull(optString(args, "entry_path"));
        String filePath = blankToNull(optString(args, "file"));
        String text = optString(args, "text");
        if (entryPath == null) throw new IllegalArgumentException("entry_path 不能为空");
        if (filePath == null && text == null) {
            throw new IllegalArgumentException("file 或 text 至少提供一个");
        }
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            if (filePath != null) {
                EntryOps.putFile(module, new File(filePath), entryPath, log);
            } else {
                EntryOps.putStringUtf8(module, text, entryPath, log);
            }
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("output", finalOut.getAbsolutePath());
        result.addProperty("entry_path", entryPath);
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject removeEntry(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String entryPath = blankToNull(optString(args, "entry_path"));
        String prefix = blankToNull(optString(args, "prefix"));
        if (entryPath == null && prefix == null) {
            throw new IllegalArgumentException("entry_path 或 prefix 至少提供一个");
        }
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            if (entryPath != null) EntryOps.remove(module, entryPath, log);
            if (prefix != null) EntryOps.removeByPrefix(module, prefix, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("output", finalOut.getAbsolutePath());
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject exportManifestXml(JsonObject args) throws Exception {
        File apk = requireFile(args, "file_path");
        String outputPath = optString(args, "output");
        SimpleApkLogger logger = new SimpleApkLogger();
        try (ApkModule module = ApkModuleIO.load(apk, logger)) {
            String xml = ManifestXmlOps.toXml(module, logger);
            JsonObject result = new JsonObject();
            result.addProperty("path", apk.getAbsolutePath());
            if (outputPath != null && !outputPath.trim().isEmpty()) {
                File out = new File(outputPath.trim());
                ManifestXmlOps.exportToFile(module, out, logger);
                result.addProperty("output", out.getAbsolutePath());
            } else {
                result.addProperty("xml", xml);
            }
            return result;
        }
    }

    JsonObject applyManifestXml(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String xml = optString(args, "xml");
        String xmlFile = blankToNull(optString(args, "xml_file"));
        if ((xml == null || xml.isEmpty()) && xmlFile == null) {
            throw new IllegalArgumentException("xml 或 xml_file 至少提供一个");
        }
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            if (xmlFile != null) ManifestXmlOps.applyXmlFile(module, new File(xmlFile), log);
            else ManifestXmlOps.applyXml(module, xml, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("output", finalOut.getAbsolutePath());
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject patchDex(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        List<DexPatcher.Target> clearMethods = parseClearMethodTargets(args);
        SimpleApkLogger logger = new SimpleApkLogger();
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            // patch_dex tool: default strategies unless custom args override
            applyDexPatchFromArgs(module, args, true, clearMethods, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("input", input.getAbsolutePath());
        result.addProperty("output", finalOut.getAbsolutePath());
        result.addProperty("signed", sign);
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    /**
     * 任意 smali 编译写回 APK。
     * smali / smali_file 二选一；mode=replace|upsert；可选 dex、output、sign。
     */
    JsonObject compileSmali(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String smali = blankToNull(optString(args, "smali"));
        String smaliFilePath = blankToNull(optString(args, "smali_file"));
        if (smali == null && smaliFilePath == null) {
            // 兼容 source / code / file
            smali = blankToNull(optString(args, "source"));
            if (smali == null) smali = blankToNull(optString(args, "code"));
            if (smaliFilePath == null) smaliFilePath = blankToNull(optString(args, "file"));
        }
        if (smali == null && smaliFilePath == null) {
            throw new IllegalArgumentException("smali 或 smali_file 至少提供一个");
        }
        String mode = blankToNull(optString(args, "mode"));
        if (mode == null) mode = "replace";
        String preferDex = blankToNull(optString(args, "dex"));
        if (preferDex == null) preferDex = blankToNull(optString(args, "prefer_dex"));
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        final String smaliFinal = smali;
        final String smaliFileFinal = smaliFilePath;
        final String modeFinal = mode;
        final String preferDexFinal = preferDex;
        final SmaliCompileOps.Result[] holder = new SmaliCompileOps.Result[1];
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            if (smaliFileFinal != null) {
                holder[0] = SmaliCompileOps.applySmaliFile(
                        module, new File(smaliFileFinal), modeFinal, preferDexFinal, log);
            } else {
                holder[0] = SmaliCompileOps.applySmali(
                        module, smaliFinal, modeFinal, preferDexFinal, log);
            }
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        SmaliCompileOps.Result r = holder[0];
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("input", input.getAbsolutePath());
        result.addProperty("output", finalOut.getAbsolutePath());
        result.addProperty("signed", sign);
        if (r != null) {
            result.addProperty("class", r.classDescriptor);
            result.addProperty("dex", r.dexName);
            result.addProperty("created", r.created);
            result.addProperty("replaced", r.replaced);
            result.add("details", toStringArray(r.details));
        }
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    /** 导出单个类的 smali 文本（ARSCLib），可选写到 output 文件。 */
    JsonObject exportSmaliClass(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String className = blankToNull(optString(args, "class"));
        if (className == null) className = blankToNull(optString(args, "class_name"));
        if (className == null) className = blankToNull(optString(args, "type"));
        if (className == null) {
            throw new IllegalArgumentException("class 不能为空（如 com.foo.Bar 或 Lcom/foo/Bar;）");
        }
        SimpleApkLogger logger = new SimpleApkLogger();
        String smali;
        try (com.reandroid.apk.ApkModule module = ApkModuleIO.load(input, logger)) {
            smali = SmaliCompileOps.exportSmali(module, className, logger);
        }
        String outputPath = blankToNull(optString(args, "output"));
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("path", input.getAbsolutePath());
        result.addProperty("class", SmaliCompileOps.normalizeDescriptor(className));
        if (outputPath != null) {
            File out = new File(outputPath);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            java.nio.file.Files.write(
                    out.toPath(),
                    smali.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            result.addProperty("output", out.getAbsolutePath());
            result.addProperty("size", smali.length());
        } else {
            result.addProperty("smali", smali);
        }
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }


    JsonObject listDex(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        List<DexBrowserOps.DexEntry> list = DexBrowserOps.listDexEntries(input);
        JsonArray arr = new JsonArray();
        for (DexBrowserOps.DexEntry e : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.name);
            o.addProperty("size", e.size);
            o.addProperty("size_text", DexBrowserOps.formatSize(e.size));
            arr.add(o);
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", input.getAbsolutePath());
        result.addProperty("count", list.size());
        result.add("dex_files", arr);
        return result;
    }

    JsonObject listDexClasses(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String dexEntry = blankToNull(optString(args, "dex"));
        String filter = blankToNull(optString(args, "filter"));
        if (filter == null) filter = blankToNull(optString(args, "query"));
        Integer limitObj = optInteger(args, "limit");
        int limit = limitObj != null ? limitObj : 2000;
        SimpleApkLogger logger = new SimpleApkLogger();
        File work = new File(context.getCacheDir(), "dex_mcp_" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        try {
            File dexFile = new File(work, "target.dex");
            DexBrowserOps.extractDexEntry(input, dexEntry, dexFile);
            if (dexEntry == null) {
                List<DexBrowserOps.DexEntry> entries = DexBrowserOps.listDexEntries(input);
                dexEntry = entries.get(0).name;
            }
            List<DexBrowserOps.ClassItem> classes = DexBrowserOps.listClasses(dexFile, filter, limit);
            JsonArray arr = new JsonArray();
            for (DexBrowserOps.ClassItem c : classes) {
                JsonObject o = new JsonObject();
                o.addProperty("type", c.typeName);
                o.addProperty("java", c.javaName);
                o.addProperty("simple", c.simpleName);
                arr.add(o);
            }
            JsonObject result = new JsonObject();
            result.addProperty("path", input.getAbsolutePath());
            result.addProperty("dex", dexEntry);
            result.addProperty("filter", filter != null ? filter : "");
            result.addProperty("count", classes.size());
            result.addProperty("limit", limit);
            result.add("classes", arr);
            result.add("logs", toStringArray(logger.getLines()));
            return result;
        } finally {
            deleteRecursively(work);
        }
    }

    JsonObject decompileSmali(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String className = blankToNull(optString(args, "class"));
        if (className == null) className = blankToNull(optString(args, "class_name"));
        if (className == null) throw new IllegalArgumentException("class 不能为空");
        String dexEntry = blankToNull(optString(args, "dex"));
        SimpleApkLogger logger = new SimpleApkLogger();
        File work = new File(context.getCacheDir(), "dex_smali_" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        try {
            File dexFile = new File(work, "target.dex");
            DexBrowserOps.extractDexEntry(input, dexEntry, dexFile);
            String smali = DexBrowserOps.toSmali(dexFile, className, logger);
            String outputPath = blankToNull(optString(args, "output"));
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("path", input.getAbsolutePath());
            result.addProperty("class", DexBrowserOps.normalizeType(className));
            if (outputPath != null) {
                File out = new File(outputPath);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                java.nio.file.Files.write(out.toPath(),
                        smali.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                result.addProperty("output", out.getAbsolutePath());
                result.addProperty("size", smali.length());
            } else {
                result.addProperty("smali", smali);
            }
            result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
            return result;
        } finally {
            deleteRecursively(work);
        }
    }

    JsonObject decompileJava(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String className = blankToNull(optString(args, "class"));
        if (className == null) className = blankToNull(optString(args, "class_name"));
        if (className == null) throw new IllegalArgumentException("class 不能为空");
        String dexEntry = blankToNull(optString(args, "dex"));
        SimpleApkLogger logger = new SimpleApkLogger();
        File work = new File(context.getCacheDir(), "dex_java_" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        try {
            File dexFile = new File(work, "target.dex");
            DexBrowserOps.extractDexEntry(input, dexEntry, dexFile);
            String code = DexBrowserOps.toJava(dexFile, className, logger);
            String outputPath = blankToNull(optString(args, "output"));
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("path", input.getAbsolutePath());
            result.addProperty("class", DexBrowserOps.typeToJava(DexBrowserOps.normalizeType(className)));
            if (outputPath != null) {
                File out = new File(outputPath);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                java.nio.file.Files.write(out.toPath(),
                        code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                result.addProperty("output", out.getAbsolutePath());
                result.addProperty("size", code.length());
            } else {
                result.addProperty("java", code);
            }
            result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
            return result;
        } finally {
            deleteRecursively(work);
        }
    }

    JsonObject extractDex(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String dexEntry = blankToNull(optString(args, "dex"));
        String outputPath = blankToNull(optString(args, "output"));
        if (outputPath == null) {
            String base = stripApkExtension(input.getName());
            String name = dexEntry != null ? new File(dexEntry).getName() : "classes.dex";
            outputPath = new File(input.getParentFile(), base + "-" + name).getAbsolutePath();
        }
        File out = new File(outputPath);
        DexBrowserOps.extractDexEntry(input, dexEntry, out);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("path", input.getAbsolutePath());
        result.addProperty("dex", dexEntry != null ? dexEntry : "auto");
        result.addProperty("output", out.getAbsolutePath());
        result.addProperty("size", out.length());
        return result;
    }

    JsonObject exportSmaliDir(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String dexEntry = blankToNull(optString(args, "dex"));
        String outputPath = blankToNull(optString(args, "output"));
        if (outputPath == null) {
            outputPath = new File(input.getParentFile(),
                    stripApkExtension(input.getName()) + "-smali").getAbsolutePath();
        }
        File outDir = new File(outputPath);
        SimpleApkLogger logger = new SimpleApkLogger();
        int count;
        try (com.reandroid.apk.ApkModule module = ApkModuleIO.load(input, logger)) {
            count = SmaliCompileOps.exportSmaliDirectory(module, dexEntry, outDir, logger);
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("path", input.getAbsolutePath());
        result.addProperty("output", outDir.getAbsolutePath());
        result.addProperty("class_count", count);
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject importSmaliDir(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String dirPath = blankToNull(optString(args, "smali_dir"));
        if (dirPath == null) dirPath = blankToNull(optString(args, "dir"));
        if (dirPath == null) throw new IllegalArgumentException("smali_dir 不能为空");
        File smaliDir = new File(dirPath);
        if (!smaliDir.isDirectory()) {
            throw new IllegalArgumentException("smali_dir 不是目录: " + dirPath);
        }
        String mode = blankToNull(optString(args, "mode"));
        if (mode == null) mode = "upsert";
        String preferDex = blankToNull(optString(args, "dex"));
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        final int[] holder = new int[1];
        final String modeF = mode;
        final String dexF = preferDex;
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            holder[0] = SmaliCompileOps.importSmaliDirectory(module, smaliDir, modeF, dexF, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("input", input.getAbsolutePath());
        result.addProperty("output", finalOut.getAbsolutePath());
        result.addProperty("imported", holder[0]);
        result.addProperty("signed", sign);
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject renameDexMethod(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String className = blankToNull(optString(args, "class"));
        if (className == null) className = blankToNull(optString(args, "class_name"));
        String oldMethod = blankToNull(optString(args, "old_method"));
        if (oldMethod == null) oldMethod = blankToNull(optString(args, "method"));
        String newMethod = blankToNull(optString(args, "new_method"));
        if (newMethod == null) newMethod = blankToNull(optString(args, "new_name"));
        String proto = blankToNull(optString(args, "proto"));
        if (className == null || oldMethod == null || newMethod == null) {
            throw new IllegalArgumentException("class / old_method / new_method 不能为空");
        }
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        final String c = className, o = oldMethod, n = newMethod, p = proto;
        final DexRenameOps.Result[] holder = new DexRenameOps.Result[1];
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            holder[0] = DexRenameOps.renameMethod(module, c, o, n, p, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("input", input.getAbsolutePath());
        result.addProperty("output", finalOut.getAbsolutePath());
        result.addProperty("signed", sign);
        if (holder[0] != null) {
            result.addProperty("dex_touched", holder[0].dexTouched);
            result.addProperty("applied", holder[0].applied);
            result.add("details", toStringArray(holder[0].details));
        }
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject renameDexField(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String className = blankToNull(optString(args, "class"));
        if (className == null) className = blankToNull(optString(args, "class_name"));
        String oldField = blankToNull(optString(args, "old_field"));
        if (oldField == null) oldField = blankToNull(optString(args, "field"));
        String newField = blankToNull(optString(args, "new_field"));
        if (newField == null) newField = blankToNull(optString(args, "new_name"));
        String fieldType = blankToNull(optString(args, "field_type"));
        if (fieldType == null) fieldType = blankToNull(optString(args, "type"));
        if (className == null || oldField == null || newField == null) {
            throw new IllegalArgumentException("class / old_field / new_field 不能为空");
        }
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        final String c = className, o = oldField, n = newField, t = fieldType;
        final DexRenameOps.Result[] holder = new DexRenameOps.Result[1];
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            holder[0] = DexRenameOps.renameField(module, c, o, n, t, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("input", input.getAbsolutePath());
        result.addProperty("output", finalOut.getAbsolutePath());
        result.addProperty("signed", sign);
        if (holder[0] != null) {
            result.addProperty("dex_touched", holder[0].dexTouched);
            result.addProperty("applied", holder[0].applied);
            result.add("details", toStringArray(holder[0].details));
        }
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject clearDexMethods(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        List<DexPatcher.Target> targets = parseClearMethodTargets(args);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "clear_methods 不能为空（字符串 Lcls;->name()V 或 {class,method,proto}）");
        }
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        final List<DexPatcher.Target> t = targets;
        final DexPatcher.Result[] holder = new DexPatcher.Result[1];
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            holder[0] = DexOps.clearMethodsOnly(module, t, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("input", input.getAbsolutePath());
        result.addProperty("output", finalOut.getAbsolutePath());
        result.addProperty("signed", sign);
        if (holder[0] != null) {
            result.addProperty("cleared", holder[0].methodsCleared);
            result.add("details", toStringArray(holder[0].details));
        }
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    JsonObject setStringRes(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        String name = blankToNull(optString(args, "name"));
        String value = optString(args, "value");
        String type = blankToNull(optString(args, "type"));
        if (name == null || value == null) {
            throw new IllegalArgumentException("name / value 不能为空");
        }
        if (type == null) type = "string";
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        String t = type;
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            StringResOps.setString(module, t, name, value, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("output", finalOut.getAbsolutePath());
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    JsonObject sanitizeApk(JsonObject args) throws Exception {
        File input = requireFile(args, "file_path");
        boolean sign = optBoolean(args, "sign", true);
        File unsignedOut = resolvePatchOutput(args, input, sign);
        SimpleApkLogger logger = new SimpleApkLogger();
        ApkModuleIO.transform(input, unsignedOut, (module, log) -> {
            ModuleSanitizeOps.sanitizeForStandaloneApk(module, log);
        }, logger);
        File finalOut = maybeSign(context, unsignedOut, sign, logger);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("output", finalOut.getAbsolutePath());
        result.add("logs", toStringArray(limitLogs(logger.getLines(), args)));
        return result;
    }

    // --- helpers ---

    private File maybeSign(android.content.Context context, File unsignedOut, boolean sign,
                           SimpleApkLogger logger) throws Exception {
        if (!sign) return unsignedOut;
        File signedFile = new File(
                unsignedOut.getParentFile(),
                stripApkExtension(unsignedOut.getName()) + "-signed.apk");
        if (unsignedOut.getAbsolutePath().equals(signedFile.getAbsolutePath())) {
            File tmp = new File(unsignedOut.getParentFile(),
                    "patch_unsigned_" + System.currentTimeMillis() + ".apk");
            if (!unsignedOut.renameTo(tmp)) {
                throw new IllegalStateException("无法准备签名临时文件");
            }
            SignOps.signDebug(context, tmp, unsignedOut, logger);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return unsignedOut;
        }
        SignOps.signDebug(context, unsignedOut, signedFile, logger);
        if (unsignedOut.exists() && !unsignedOut.equals(signedFile)) {
            //noinspection ResultOfMethodCallIgnored
            unsignedOut.delete();
        }
        return signedFile;
    }

    private static JsonArray componentArray(List<ComponentOps.ComponentInfo> list) {
        JsonArray arr = new JsonArray();
        if (list == null) return arr;
        for (ComponentOps.ComponentInfo c : list) {
            if (c == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("tag", c.tag);
            o.addProperty("name", c.name);
            if (c.exported != null) o.addProperty("exported", c.exported);
            arr.add(o);
        }
        return arr;
    }

    /**
     * 自用默认输出目录（不备份原包）：所有「没指定 output」的改包结果都丢这里，好找。
     * 路径：/storage/emulated/0/ApksToApk/out
     * 若无法创建/写入，再退回 App 外部文件目录。
     */
    private File defaultApkOutDir() {
        File preferred = new File("/storage/emulated/0/ApksToApk/out");
        try {
            if (!preferred.exists()) {
                //noinspection ResultOfMethodCallIgnored
                preferred.mkdirs();
            }
            if (preferred.isDirectory() && preferred.canWrite()) {
                return preferred;
            }
        } catch (Exception ignored) {
        }
        File ext = context.getExternalFilesDir(null);
        if (ext == null) {
            ext = context.getCacheDir();
        }
        File fallback = new File(ext, "out");
        if (!fallback.exists()) {
            //noinspection ResultOfMethodCallIgnored
            fallback.mkdirs();
        }
        return fallback;
    }

    /** 文件名：原名_时间戳.apk，避免互相覆盖。 */
    private static String autoOutName(String inputName, String tag) {
        String base = stripApkExtension(inputName != null ? inputName : "apk");
        String t = tag == null || tag.trim().isEmpty() ? "out" : tag.trim();
        return base + "_" + t + "_" + System.currentTimeMillis() + ".apk";
    }

    private void resolveOutputFile(JsonObject args, ApksMerger.Options opt, String prefix) {
        String output = optString(args, "output");
        if (output != null && !output.trim().isEmpty()) {
            opt.outputFile = new File(output.trim());
            File parent = opt.outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
        } else {
            File dir = defaultApkOutDir();
            String tag = prefix != null ? prefix : "merged";
            opt.outputFile = new File(dir, autoOutName("merged.apk", tag));
        }
    }

    private File resolvePatchOutput(JsonObject args, File input, boolean willSign) {
        String outputPath = optString(args, "output");
        if (outputPath != null && !outputPath.trim().isEmpty()) {
            File f = new File(outputPath.trim());
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            return f;
        }
        // 未指定 output：统一进固定 out 目录，不把成品扔在原 APK 旁边搅成一锅
        File dir = defaultApkOutDir();
        String name = input != null ? input.getName() : "apk.apk";
        // willSign 时中间未签名文件也进 out，签名后变成 xxx-signed.apk 仍在同目录
        return new File(dir, autoOutName(name, willSign ? "work" : "out"));
    }

    private void attachIdentitySummary(JsonObject result, File apk) {
        if (apk == null || !apk.isFile()) {
            return;
        }
        try {
            ApkInspect.Report report = ApkInspect.inspectFile(apk, null);
            if (report.manifest != null) {
                ManifestOps.Snapshot s = report.manifest;
                if (s.packageName != null) {
                    result.addProperty("package_name", s.packageName);
                }
                if (s.versionCode != null) {
                    result.addProperty("version_code", s.versionCode);
                }
                if (s.versionName != null) {
                    result.addProperty("version_name", s.versionName);
                }
                if (s.appLabel != null) {
                    result.addProperty("app_label", s.appLabel);
                }
            }
        } catch (Exception e) {
            result.addProperty("identity_warning", "inspect failed: " + e.getMessage());
        }
    }

    private static String normalizeProfile(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "agent";
        }
        String p = raw.trim().toLowerCase(Locale.US);
        if ("ui".equals(p) || "raw".equals(p) || "agent".equals(p)) {
            return p;
        }
        return "agent";
    }

    private static void mergeExcludeLists(List<String> into, List<String> extra) {
        if (extra == null || extra.isEmpty()) return;
        Set<String> seen = new LinkedHashSet<>(into);
        for (String s : extra) {
            if (s != null && seen.add(s)) {
                into.add(s);
            }
        }
    }

    private static List<String> listApkNamesInDir(File dir) {
        List<String> names = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return names;
        for (File f : files) {
            if (f != null && f.isFile()) {
                String n = f.getName();
                if (n.toLowerCase(Locale.US).endsWith(".apk")) {
                    names.add(n);
                }
            }
        }
        return names;
    }

    private static List<File> readExistingFiles(JsonObject args, String key) {
        List<File> out = new ArrayList<>();
        for (String p : readStringList(args, key)) {
            File f = new File(p);
            if (!f.isFile()) {
                throw new IllegalArgumentException(key + " 文件不存在: " + f.getAbsolutePath());
            }
            out.add(f);
        }
        return out;
    }

    private static List<String> limitLogs(List<String> logs, JsonObject args) {
        if (logs == null) return new ArrayList<>();
        if (optBoolean(args, "verbose_logs", false)) {
            return logs;
        }
        if (logs.size() <= DEFAULT_LOG_LIMIT) {
            return logs;
        }
        // Keep head marker + tail (most recent / important end stages)
        List<String> clipped = new ArrayList<>();
        clipped.add("… logs truncated (" + logs.size() + " lines, set verbose_logs=true for full) …");
        int from = Math.max(0, logs.size() - (DEFAULT_LOG_LIMIT - 1));
        clipped.addAll(logs.subList(from, logs.size()));
        return clipped;
    }

    private static JsonObject snapshotJson(ManifestOps.Snapshot s) {
        JsonObject o = new JsonObject();
        o.addProperty("package_name", s.packageName);
        if (s.versionCode != null) o.addProperty("version_code", s.versionCode);
        o.addProperty("version_name", s.versionName);
        o.addProperty("app_label", s.appLabel);
        if (s.appLabelRef != null) o.addProperty("app_label_ref", s.appLabelRef);
        o.addProperty("application_class", s.applicationClass);
        o.addProperty("main_activity", s.mainActivityClass);
        if (s.debuggable != null) o.addProperty("debuggable", s.debuggable);
        if (s.extractNativeLibs != null) o.addProperty("extract_native_libs", s.extractNativeLibs);
        if (s.minSdkVersion != null) o.addProperty("min_sdk", s.minSdkVersion);
        if (s.targetSdkVersion != null) o.addProperty("target_sdk", s.targetSdkVersion);
        o.add("uses_permissions", toStringArray(s.usesPermissions));
        return o;
    }

    /**
     * DEX 自定义规则（MCP）：
     * <ul>
     *   <li>{@code clear_methods}: 数组。元素可为
     *     字符串 {@code Lpkg/Cls;->name()V}，或对象
     *     {@code {class, method, proto}}（也认 class_name/method_name）</li>
     *   <li>{@code use_default_clear}: 是否合并默认 DetectionPopup 清空列表</li>
     *   <li>{@code remove_invoke_p}: 删除 ApplicationMain 中 ->p 调用</li>
     *   <li>{@code inject_load_library}: Unity onCreate 插入 loadLibrary("Widget")</li>
     * </ul>
     */
    private static boolean hasDexCustomArgs(JsonObject args) {
        if (args == null) return false;
        return args.has("clear_methods")
                || args.has("use_default_clear")
                || args.has("remove_invoke_p")
                || args.has("inject_load_library")
                || args.has("dex_clear_methods");
    }

    private static void applyDexPatchOptionsToMerger(JsonObject args, ApksMerger.Options opt) {
        List<DexPatcher.Target> custom = parseClearMethodTargets(args);
        // 记录 profile/显式 patch_dex 意图：决定侧策略默认值
        boolean defaultStrategies = opt.patchDex;
        boolean useDefaultClear = resolveUseDefaultClear(args, custom, defaultStrategies);
        List<DexPatcher.Target> targets = new ArrayList<>();
        if (useDefaultClear) {
            targets.addAll(DexPatcher.defaultClearTargets());
        }
        if (!custom.isEmpty()) {
            targets.addAll(custom);
        }
        if (!targets.isEmpty()) {
            opt.dexPatchTargets = targets;
        }
        // 仅 clear_methods / 自定义开关时也要启用 patchDex 流水线
        if (!opt.patchDex && (!custom.isEmpty() || hasDexCustomArgs(args))) {
            opt.patchDex = true;
        }
        // patch_dex=true（含 ui profile）→ 侧策略默认 true；仅自定义 clear → 默认 false
        opt.removeInvokeP = optBoolean(args, "remove_invoke_p", defaultStrategies);
        opt.injectLoadLibrary = optBoolean(args, "inject_load_library", defaultStrategies);
    }

    private static void applyDexPatchFromArgs(
            ApkModule module,
            JsonObject args,
            boolean defaultStrategies,
            List<DexPatcher.Target> customClear,
            SimpleApkLogger log
    ) throws Exception {
        List<DexPatcher.Target> custom = customClear != null
                ? customClear
                : parseClearMethodTargets(args);
        boolean useDefaultClear = resolveUseDefaultClear(args, custom, defaultStrategies);
        List<DexPatcher.Target> targets = new ArrayList<>();
        if (useDefaultClear) {
            targets.addAll(DexPatcher.defaultClearTargets());
        }
        if (custom != null) {
            targets.addAll(custom);
        }
        // defaultStrategies=true：完整默认（含 removeP/inject）；仅自定义 clear 且未显式写开关时
        // remove_invoke_p / inject_load_library 默认 false，避免误伤
        boolean removeP = optBoolean(args, "remove_invoke_p", defaultStrategies);
        boolean inject = optBoolean(args, "inject_load_library", defaultStrategies);
        if (targets.isEmpty() && !removeP && !inject) {
            if (log != null) {
                log.warn("DEX 补丁无目标", "DEX patch has no targets");
            }
            return;
        }
        DexPatcher.Result r = DexOps.apply(module, targets, removeP, inject, log);
        if (log != null && r != null) {
            log.bi("DEX 补丁结果", "DEX patch result",
                    "clear=" + r.methodsCleared
                            + ", removeP=" + r.invokePRemoved
                            + ", inject=" + r.loadLibraryInjected);
        }
    }

    private static boolean resolveUseDefaultClear(
            JsonObject args,
            List<DexPatcher.Target> custom,
            boolean defaultStrategies
    ) {
        boolean hasCustom = custom != null && !custom.isEmpty();
        // 显式参数优先
        if (args != null && args.has("use_default_clear") && !args.get("use_default_clear").isJsonNull()) {
            return optBoolean(args, "use_default_clear", !hasCustom);
        }
        // 有自定义 clear 时默认不合并默认列表；纯默认策略时用默认列表
        if (hasCustom) {
            return false;
        }
        return defaultStrategies;
    }

    /**
     * 解析 clear_methods / dex_clear_methods。
     * 支持：
     * <pre>
     * "Lcom/foo/Bar;->show()V"
     * {"class":"Lcom/foo/Bar;","method":"show","proto":"()V"}
     * {"class":"com.foo.Bar","method":"show","proto":"()V"}
     * </pre>
     */
    private static List<DexPatcher.Target> parseClearMethodTargets(JsonObject args) {
        List<DexPatcher.Target> out = new ArrayList<>();
        if (args == null) return out;
        JsonElement el = null;
        if (args.has("clear_methods") && !args.get("clear_methods").isJsonNull()) {
            el = args.get("clear_methods");
        } else if (args.has("dex_clear_methods") && !args.get("dex_clear_methods").isJsonNull()) {
            el = args.get("dex_clear_methods");
        }
        if (el == null) return out;
        if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                DexPatcher.Target t = parseOneClearTarget(item);
                if (t != null) out.add(t);
            }
        } else {
            DexPatcher.Target t = parseOneClearTarget(el);
            if (t != null) out.add(t);
        }
        return out;
    }

    private static DexPatcher.Target parseOneClearTarget(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            return parseMethodSpec(el.getAsString());
        }
        if (!el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        String cls = firstNonBlank(
                optStringExact(o, "class"),
                optStringExact(o, "class_name"),
                optStringExact(o, "classDescriptor"),
                optStringExact(o, "descriptor")
        );
        String method = firstNonBlank(
                optStringExact(o, "method"),
                optStringExact(o, "method_name"),
                optStringExact(o, "name")
        );
        String proto = firstNonBlank(
                optStringExact(o, "proto"),
                optStringExact(o, "signature"),
                optStringExact(o, "descriptor_method")
        );
        // object 也可只给 spec 字段
        String spec = firstNonBlank(optStringExact(o, "spec"), optStringExact(o, "target"));
        if (spec != null && (cls == null || method == null)) {
            return parseMethodSpec(spec);
        }
        if (cls == null || method == null) {
            throw new IllegalArgumentException(
                    "clear_methods 项需要 class+method(+proto) 或 Lcls;->name()V 字符串");
        }
        if (proto == null || proto.trim().isEmpty()) {
            proto = "()V";
        }
        return DexOps.target(cls.trim(), method.trim(), proto.trim());
    }

    /** 解析 {@code Lpkg/Cls;->name(II)V} 或 {@code pkg.Cls->name()V}。 */
    private static DexPatcher.Target parseMethodSpec(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int arrow = s.indexOf("->");
        if (arrow < 0) {
            throw new IllegalArgumentException("clear_methods 字符串需含 -> ： " + s);
        }
        String cls = s.substring(0, arrow).trim();
        String rest = s.substring(arrow + 2).trim();
        int paren = rest.indexOf('(');
        if (paren < 0) {
            throw new IllegalArgumentException("clear_methods 字符串需含 proto，如 name()V： " + s);
        }
        String method = rest.substring(0, paren).trim();
        String proto = rest.substring(paren).trim();
        if (method.isEmpty()) {
            throw new IllegalArgumentException("clear_methods 方法名为空： " + s);
        }
        return DexOps.target(cls, method, proto);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private static File requireFile(JsonObject args, String key) {
        String path = optString(args, key);
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " 不能为空");
        }
        File file = new File(path.trim());
        if (!file.isFile()) {
            throw new IllegalArgumentException(key + " 文件不存在: " + file.getAbsolutePath());
        }
        return file;
    }

    /** 读字符串参数（无别名兼容）。 */
    private static String optString(JsonObject args, String key) {
        return optStringExact(args, key);
    }

    private static String optStringExact(JsonObject args, String key) {
        if (args == null || key == null || !args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean optBoolean(JsonObject args, String key, boolean def) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return def;
        }
        try {
            return args.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    private static Integer optInteger(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsInt();
        } catch (Exception e) {
            try {
                String s = args.get(key).getAsString();
                if (s == null || s.trim().isEmpty()) return null;
                return Integer.parseInt(s.trim());
            } catch (Exception e2) {
                throw new IllegalArgumentException(key + " 不是整数: " + args.get(key));
            }
        }
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static List<String> readStringList(JsonObject args, String key) {
        List<String> out = new ArrayList<>();
        if (args == null || !args.has(key) || !args.get(key).isJsonArray()) {
            return out;
        }
        for (JsonElement el : args.getAsJsonArray(key)) {
            if (el != null && el.isJsonPrimitive()) {
                String v = el.getAsString();
                if (v != null && !v.trim().isEmpty()) {
                    out.add(v.trim());
                }
            }
        }
        return out;
    }

    private static JsonArray toStringArray(List<String> list) {
        JsonArray array = new JsonArray();
        if (list != null) {
            for (String s : list) {
                if (s != null) array.add(s);
            }
        }
        return array;
    }

    private static List<String> limit(List<String> list, int max) {
        if (list == null || list.size() <= max) return list;
        return list.subList(0, max);
    }

    private static String stripApkExtension(String name) {
        if (name == null) return "apk";
        if (name.toLowerCase(Locale.US).endsWith(".apk")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }
}
