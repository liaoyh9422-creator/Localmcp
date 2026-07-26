package com.apkstoapk.app.core;

import com.apkstoapk.app.util.IoUtils;
import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.AndroidManifestBlockSplitSanitizer;
import com.reandroid.apk.ApkModule;
import com.reandroid.apkeditor.common.AndroidManifestHelper;
import com.reandroid.app.AndroidManifest;
import com.reandroid.archive.ZipEntryMap;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.container.SpecTypePair;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ResValue;
import com.reandroid.arsc.value.ValueType;
import com.reandroid.xml.XMLFactory;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Find / auto-edit / optionally manual-edit / save AndroidManifest.xml,
 * then caller continues APKS->APK write+sign.
 */
public final class ManifestWorkflow {
    private ManifestWorkflow() {}

    public static final class Result {
        public final String xml;
        public final File exportedFile;
        public final boolean userEdited;

        public Result(String xml, File exportedFile, boolean userEdited) {
            this.xml = xml;
            this.exportedFile = exportedFile;
            this.userEdited = userEdited;
        }
    }

    /**
     * @param exportDir optional directory to save readable AndroidManifest.xml
     * @param promptEdit whether to invoke handler for manual edit
     * @param handler may be null if promptEdit is false
     * @return null if user cancelled
     */
    public static Result process(
            ApkModule module,
            SimpleApkLogger logger,
            File exportDir,
            boolean autoSanitize,
            boolean forceExtractNativeLibsTrue
    ) throws Exception {
        logger.bi("正在搜索 AndroidManifest.xml", "Searching AndroidManifest.xml…");
        if (!module.hasAndroidManifest()) {
            throw new IllegalStateException("Merged module has no AndroidManifest.xml");
        }

        AndroidManifestBlock manifest = module.getAndroidManifest();
        if (manifest == null) {
            throw new IllegalStateException("Failed to load AndroidManifest.xml");
        }
        logger.ok("已找到 Manifest", "Found manifest",
                AndroidManifestBlock.FILE_NAME
                        + " (package=" + safe(manifest.getPackageName())
                        + ", versionCode=" + safe(String.valueOf(manifest.getVersionCode())) + ")");

        // Load android framework so attribute ids become readable names (not r0x...)
        logger.bi("加载属性名解析用 Framework", "Loading framework for attribute name decode");
        FrameworkHelper.ensureAndroidFramework(module, logger);
        ensurePackageBlock(module, manifest);

        if (autoSanitize) {
            logger.bi("自动编辑 Manifest（清理 split 字段）",
                    "Auto-editing manifest (remove split flags)…");
            sanitizeSplitInfo(module, logger);
            // Also run REAndroid built-in sanitizer for broader cleanup
            AndroidManifestBlockSplitSanitizer sanitizer = new AndroidManifestBlockSplitSanitizer();
            if (sanitizer.sanitize(module)) {
                logger.ok("已应用内置 split 清理器", "Built-in split sanitizer applied");
            }
            manifest = module.getAndroidManifest();
            ensurePackageBlock(module, manifest);
            manifest.refresh();
        }

        if (forceExtractNativeLibsTrue) {
            // Auto rewrite android:extractNativeLibs="false" -> "true"
            forceExtractNativeLibsTrue(module, logger);
        }
        ensurePackageBlock(module, manifest);
        String xml = exportReadableXml(manifest, logger);
        xml = AndroidAttrNames.replaceUnknownAttrNames(xml);
        logger.ok("Manifest 已导出为可读 XML",
                "Manifest exported to readable XML",
                xml.length() + " chars");

        File exported = null;
        if (exportDir != null) {
            if (!exportDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                exportDir.mkdirs();
            }
            exported = new File(exportDir, "AndroidManifest.xml");
            writeText(exported, xml);
            logger.ok("可读 Manifest 已保存", "Saved readable manifest",
                    exported.getAbsolutePath());
        }

        // Ensure module holds refreshed manifest before writeApk
        AndroidManifestBlock finalManifest = module.getAndroidManifest();
        if (finalManifest != null) {
            finalManifest.refreshFull();
            module.setManifest(finalManifest);
        }
        logger.ok("Manifest 已写回合并模块", "Manifest saved into merged module");
        return new Result(xml, exported, false);
    }

    public static String exportReadableXml(AndroidManifestBlock manifest, SimpleApkLogger logger)
            throws Exception {
        try {
            String xml = manifest.serializeToXml();
            return AndroidAttrNames.replaceUnknownAttrNames(xml);
        } catch (Exception first) {
            // Fallback: serialize without full resource decode if package missing
            logger.warn("serializeToXml 失败，改用 decode=false 重试",
                    "serializeToXml failed, retry with decode=false",
                    first.getMessage());
            java.io.StringWriter writer = new java.io.StringWriter();
            org.xmlpull.v1.XmlSerializer serializer = XMLFactory.newSerializer(writer);
            manifest.serialize(serializer, false);
            serializer.flush();
            return AndroidAttrNames.replaceUnknownAttrNames(writer.toString());
        }
    }

    public static void applyReadableXml(ApkModule module, String xml, SimpleApkLogger logger)
            throws Exception {
        if (xml == null || xml.trim().isEmpty()) {
            throw new IllegalArgumentException("Manifest XML is empty");
        }
        AndroidManifestBlock current = module.getAndroidManifest();
        PackageBlock packageBlock = current != null ? current.getPackageBlock() : null;
        if (packageBlock == null && module.hasTableBlock()) {
            packageBlock = module.getTableBlock().pickOne();
        }

        AndroidManifestBlock next = new AndroidManifestBlock();
        if (packageBlock != null) {
            next.setPackageBlock(packageBlock);
        }
        XmlPullParser parser = XMLFactory.newPullParser(xml);
        next.parse(parser);
        next.refreshFull();
        module.setManifest(next);
        logger.ok("Manifest XML 已写回",
                "Manifest XML written back",
                "package=" + safe(next.getPackageName())
                        + ", versionCode=" + safe(String.valueOf(next.getVersionCode())));
    }

    public static void sanitizeSplitInfo(ApkModule mergedModule, SimpleApkLogger logger) {
        if (!mergedModule.hasAndroidManifest()) {
            return;
        }
        AndroidManifestBlock manifest = mergedModule.getAndroidManifest();
        logger.bi("清理 split 相关 Manifest 条目",
                "Sanitizing split-related manifest entries…");

        AndroidManifestHelper.removeAttributeFromManifestById(manifest,
                AndroidManifest.ID_requiredSplitTypes, logger);
        AndroidManifestHelper.removeAttributeFromManifestById(manifest,
                AndroidManifest.ID_splitTypes, logger);
        AndroidManifestHelper.removeAttributeFromManifestByName(manifest,
                AndroidManifest.NAME_splitTypes, logger);
        AndroidManifestHelper.removeAttributeFromManifestByName(manifest,
                AndroidManifest.NAME_requiredSplitTypes, logger);
        AndroidManifestHelper.removeAttributeFromManifestByName(manifest,
                AndroidManifest.NAME_splitTypes, logger);
        // NOTE: Do NOT remove extractNativeLibs here.
        // We will force android:extractNativeLibs="true" after sanitize.
        AndroidManifestHelper.removeAttributeFromManifestAndApplication(manifest,
                AndroidManifest.ID_isSplitRequired, logger, AndroidManifest.NAME_isSplitRequired);

        // clear split name attribute if present
        try {
            ResXmlElement manifestElement = manifest.getManifestElement();
            if (manifestElement != null && manifestElement.removeAttributesWithName(AndroidManifest.NAME_split)) {
                logger.ok("已清除 manifest split 名称", "Cleared manifest split name");
            }
        } catch (Exception ignored) {
        }

        ResXmlElement application = manifest.getApplicationElement();
        if (application == null) {
            manifest.refresh();
            return;
        }

        List<ResXmlElement> splitMetaDataElements = AndroidManifestHelper.listSplitRequired(application);
        boolean splitsRemoved = false;
        for (ResXmlElement meta : splitMetaDataElements) {
            if (!splitsRemoved) {
                ResXmlAttribute nameAttribute = meta.searchAttributeByResourceId(AndroidManifest.ID_name);
                if (nameAttribute != null
                        && "com.android.vending.splits".equals(nameAttribute.getValueAsString())) {
                    ResXmlAttribute valueAttribute = meta.searchAttributeByResourceId(AndroidManifest.ID_value);
                    if (valueAttribute == null) {
                        valueAttribute = meta.searchAttributeByResourceId(AndroidManifest.ID_resource);
                    }
                    if (valueAttribute != null && valueAttribute.getValueType() == ValueType.REFERENCE
                            && mergedModule.hasTableBlock()) {
                        TableBlock tableBlock = mergedModule.getTableBlock();
                        ResourceEntry resourceEntry = tableBlock.getResource(valueAttribute.getData());
                        if (resourceEntry != null) {
                            ZipEntryMap zipEntryMap = mergedModule.getZipEntryMap();
                            for (Entry entry : resourceEntry) {
                                if (entry == null) continue;
                                ResValue resValue = entry.getResValue();
                                if (resValue == null) continue;
                                String path = resValue.getValueAsString();
                                logger.item("已移除资源表条目", "Removed table entry", path);
                                zipEntryMap.remove(path);
                                entry.setNull(true);
                                SpecTypePair specTypePair = entry.getTypeBlock().getParentSpecTypePair();
                                specTypePair.removeNullEntries(entry.getId());
                            }
                            splitsRemoved = true;
                        }
                    }
                }
            }
            logger.item("已移除元素", "Removed-element",
                    "<" + meta.getName() + "> name=\""
                            + AndroidManifestBlock.getAndroidNameValue(meta) + "\"");
            application.remove(meta);
        }
        manifest.refresh();
    }

    /**
     * Optionally rewrite package / versionName / application label.
     * Null or blank values are left unchanged.
     * packageName uses ApkModule.setPackageName (manifest + resources table).
     * appLabel is written as a plain string on application android:label
     * (resource-ref labels become literal text; multi-locale strings.xml not rewritten).
     */
    public static void applyIdentity(
            ApkModule module,
            SimpleApkLogger logger,
            String packageName,
            String versionName,
            String appLabel
    ) {
        if (module == null || !module.hasAndroidManifest()) {
            return;
        }
        boolean any = hasText(packageName) || hasText(versionName) || hasText(appLabel);
        if (!any) {
            return;
        }
        if (logger != null) {
            logger.stage("修改包身份信息", "Apply package identity");
        }
        AndroidManifestBlock manifest = module.getAndroidManifest();

        if (hasText(packageName)) {
            String before = module.getPackageName();
            String next = packageName.trim();
            try {
                // True rename: DEX types + manifest package + absolute refs
                PackageRenameOps.rename(module, next, logger);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(
                        "True package rename failed: " + e.getMessage(), e);
            }
            // rename may replace manifest instance
            manifest = module.getAndroidManifest();
            if (logger != null) {
                logger.ok("已改包名(真改包)", "Package renamed (true)",
                        safe(before) + " → " + next);
            }
        }
        if (manifest == null) {
            return;
        }
        if (hasText(versionName)) {
            String before = manifest.getVersionName();
            manifest.setVersionName(versionName.trim());
            if (logger != null) {
                logger.ok("已改版本名", "Version name set",
                        safe(before) + " → " + versionName.trim());
            }
        }
        if (hasText(appLabel)) {
            String before = manifest.getApplicationLabelString();
            if (before == null) {
                Integer ref = manifest.getApplicationLabelReference();
                if (ref != null) {
                    before = "@0x" + Integer.toHexString(ref);
                }
            }
            manifest.setApplicationLabel(appLabel.trim());
            if (logger != null) {
                logger.ok("已改应用名", "App label set",
                        safe(before) + " → " + appLabel.trim());
            }
        }
        manifest.refresh();
        module.setManifest(manifest);
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * Search AndroidManifest and force:
     *   android:extractNativeLibs="true"
     * (rewrite false -> true; create if missing)
     */
    public static void forceExtractNativeLibsTrue(ApkModule module, SimpleApkLogger logger) {
        if (module == null || !module.hasAndroidManifest()) {
            if (logger != null) {
                logger.warn("无法设置 extractNativeLibs：缺少 Manifest",
                        "Cannot set extractNativeLibs: missing Manifest");
            }
            return;
        }
        AndroidManifestBlock manifest = module.getAndroidManifest();
        Boolean before = manifest.isExtractNativeLibs();
        // Prefer module API so lib compression policy stays consistent.
        module.setExtractNativeLibs(Boolean.TRUE);
        // Also ensure attribute is present on application element.
        manifest.setExtractNativeLibs(Boolean.TRUE);
        manifest.refresh();
        Boolean after = manifest.isExtractNativeLibs();
        if (logger != null) {
            logger.ok("已设置 extractNativeLibs=true",
                    "Forced extractNativeLibs=true",
                    "before=" + before + ", after=" + after);
            if (Boolean.FALSE.equals(before)) {
                logger.item("已改写", "Rewritten",
                        "android:extractNativeLibs=\"false\" → \"true\"");
            } else if (before == null) {
                logger.item("已新增属性", "Attribute created",
                        "android:extractNativeLibs=\"true\"");
            } else {
                logger.item("属性已是 true / 已确认", "Already true / confirmed",
                        "android:extractNativeLibs=\"true\"");
            }
        }
    }

    private static void ensurePackageBlock(ApkModule module, AndroidManifestBlock manifest) {
        if (manifest.getPackageBlock() != null) return;
        if (!module.hasTableBlock()) return;
        TableBlock tableBlock = module.getTableBlock();
        PackageBlock packageBlock = tableBlock.pickOne(manifest.guessCurrentPackageId());
        if (packageBlock == null) {
            packageBlock = tableBlock.pickOne();
        }
        if (packageBlock != null) {
            manifest.setPackageBlock(packageBlock);
        }
    }

    private static void writeText(File file, String text) throws Exception {
        try (OutputStream os = IoUtils.getOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            writer.write(text);
            writer.flush();
        }
    }

    private static String safe(String s) {
        return s == null ? "?" : s;
    }
}