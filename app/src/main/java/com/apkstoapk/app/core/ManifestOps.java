package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.app.AndroidManifest;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Low-level AndroidManifest / package-identity ops on a loaded {@link ApkModule}.
 * Not wired into UI or {@link ApksMerger}; call explicitly from tools/MCP/tests.
 */
public final class ManifestOps {
    private ManifestOps() {}

    public static final class Snapshot {
        public final String packageName;
        public final Integer versionCode;
        public final String versionName;
        public final String appLabel;
        public final Integer appLabelRef;
        public final String applicationClass;
        public final String mainActivityClass;
        public final Boolean debuggable;
        public final Boolean extractNativeLibs;
        public final Integer minSdkVersion;
        public final Integer targetSdkVersion;
        public final List<String> usesPermissions;

        public Snapshot(
                String packageName,
                Integer versionCode,
                String versionName,
                String appLabel,
                Integer appLabelRef,
                String applicationClass,
                String mainActivityClass,
                Boolean debuggable,
                Boolean extractNativeLibs,
                Integer minSdkVersion,
                Integer targetSdkVersion,
                List<String> usesPermissions
        ) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.appLabel = appLabel;
            this.appLabelRef = appLabelRef;
            this.applicationClass = applicationClass;
            this.mainActivityClass = mainActivityClass;
            this.debuggable = debuggable;
            this.extractNativeLibs = extractNativeLibs;
            this.minSdkVersion = minSdkVersion;
            this.targetSdkVersion = targetSdkVersion;
            this.usesPermissions = usesPermissions;
        }
    }

    public static AndroidManifestBlock requireManifest(ApkModule module) {
        if (module == null || !module.hasAndroidManifest()) {
            throw new IllegalStateException("ApkModule has no AndroidManifest.xml");
        }
        AndroidManifestBlock manifest = module.getAndroidManifest();
        if (manifest == null) {
            throw new IllegalStateException("Failed to load AndroidManifest.xml");
        }
        return manifest;
    }

    public static Snapshot snapshot(ApkModule module) {
        AndroidManifestBlock m = requireManifest(module);
        return new Snapshot(
                module.getPackageName(),
                m.getVersionCode(),
                m.getVersionName(),
                m.getApplicationLabelString(),
                m.getApplicationLabelReference(),
                m.getApplicationClassName(),
                m.getMainActivityClassName(),
                isDebuggable(m),
                m.isExtractNativeLibs(),
                m.getMinSdkVersion(),
                m.getTargetSdkVersion(),
                listUsesPermissions(m)
        );
    }

    /** Null/blank fields are skipped. packageName also updates resources table via ApkModule. */
    public static void applyIdentity(
            ApkModule module,
            String packageName,
            String versionName,
            String appLabel,
            SimpleApkLogger logger
    ) {
        ManifestWorkflow.applyIdentity(module, logger, packageName, versionName, appLabel);
    }

    /**
     * True package rename (DEX types + manifest package + absolute component refs).
     * Uses {@link PackageRenameOps}. For manifest-only applicationId change without DEX,
     * call {@link #setPackageNameManifestOnly}.
     */
    public static void setPackageName(ApkModule module, String packageName, SimpleApkLogger logger) {
        requireText(packageName, "packageName");
        try {
            PackageRenameOps.rename(module, packageName.trim(), logger);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("True package rename failed: " + e.getMessage(), e);
        }
        refresh(module);
    }

    /** Manifest / resources table package only. Does not rename DEX classes. */
    public static void setPackageNameManifestOnly(
            ApkModule module,
            String packageName,
            SimpleApkLogger logger
    ) {
        requireText(packageName, "packageName");
        String before = module.getPackageName();
        String next = packageName.trim();
        module.setPackageName(next);
        refresh(module);
        logOk(logger, "包名(仅Manifest)", "packageName(manifest-only)", before, next);
    }

    /**
     * Expand relative/short component names to {@code oldPackage + name} so they keep
     * pointing at existing DEX classes after package rename.
     * Leaves already-absolute names (other packages) untouched.
     */
    public static int pinRelativeComponentNames(
            ApkModule module,
            String oldPackage,
            SimpleApkLogger logger
    ) {
        if (module == null || oldPackage == null || oldPackage.trim().isEmpty()) {
            return 0;
        }
        AndroidManifestBlock m = requireManifest(module);
        String pkg = oldPackage.trim();
        int changed = 0;

        // <application android:name>
        ResXmlElement application = m.getApplicationElement();
        if (application != null) {
            if (pinNameAttr(application, pkg)) {
                changed++;
            }
        }

        // activity / service / receiver / provider / activity-alias / instrumentation
        String[] tags = new String[]{
                AndroidManifest.TAG_activity,
                AndroidManifest.TAG_activity_alias,
                AndroidManifest.TAG_service,
                AndroidManifest.TAG_receiver,
                AndroidManifest.TAG_provider,
                "instrumentation"
        };
        ResXmlElement manifestEl = m.getManifestElement();
        if (manifestEl != null) {
            for (String tag : tags) {
                List<ResXmlElement> list = m.listApplicationElementsByTag(tag);
                // instrumentation is under <manifest>, not always under <application>
                if ((list == null || list.isEmpty())
                        && "instrumentation".equals(tag)) {
                    list = new ArrayList<>();
                    Iterator<ResXmlElement> it = manifestEl.getElements(tag);
                    while (it != null && it.hasNext()) {
                        list.add(it.next());
                    }
                }
                if (list == null) continue;
                for (ResXmlElement el : list) {
                    if (pinNameAttr(el, pkg)) {
                        changed++;
                    }
                    // provider authorities often use ${applicationId}; rewrite plain old-package prefix only
                    if (AndroidManifest.TAG_provider.equals(tag)) {
                        if (pinProviderAuthorities(el, pkg, module.getPackageName())) {
                            // authorities rewrite uses current package as "old" before rename
                        }
                    }
                }
            }
        }

        if (changed > 0) {
            refresh(module);
        }
        return changed;
    }

    private static boolean pinNameAttr(ResXmlElement el, String oldPackage) {
        if (el == null) return false;
        ResXmlAttribute attr = el.searchAttributeByResourceId(AndroidManifest.ID_name);
        if (attr == null) {
            attr = el.searchAttributeByName(AndroidManifest.NAME_name);
        }
        if (attr == null || attr.getValueType() != ValueType.STRING) {
            return false;
        }
        String name = attr.getValueAsString();
        String pinned = toFqcnUnderPackage(name, oldPackage);
        if (pinned == null || pinned.equals(name)) {
            return false;
        }
        attr.setValueAsString(pinned);
        return true;
    }

    /**
     * If authorities is exactly oldPackage or oldPackage + ".*", leave as-is when we only
     * pin names; package rename of authorities is separate. No-op here for now.
     */
    private static boolean pinProviderAuthorities(
            ResXmlElement el,
            String oldPackage,
            String currentPackage
    ) {
        return false;
    }

    /** @return absolute class name under oldPackage, or null if should not change */
    static String toFqcnUnderPackage(String name, String oldPackage) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || oldPackage == null || oldPackage.isEmpty()) return null;
        // already absolute under old package
        if (n.equals(oldPackage) || n.startsWith(oldPackage + ".")) {
            return n;
        }
        // other absolute package (contains '.' and does not start with '.') — keep
        if (n.indexOf('.') > 0 && !n.startsWith(".")) {
            return n;
        }
        // ".MainActivity" or "MainActivity"
        if (n.startsWith(".")) {
            return oldPackage + n;
        }
        return oldPackage + "." + n;
    }

    public static void setVersionName(ApkModule module, String versionName, SimpleApkLogger logger) {
        requireText(versionName, "versionName");
        AndroidManifestBlock m = requireManifest(module);
        String before = m.getVersionName();
        m.setVersionName(versionName.trim());
        refresh(module);
        logOk(logger, "版本名", "versionName", before, versionName.trim());
    }

    public static void setVersionCode(ApkModule module, int versionCode, SimpleApkLogger logger) {
        AndroidManifestBlock m = requireManifest(module);
        Integer before = m.getVersionCode();
        m.setVersionCode(versionCode);
        refresh(module);
        logOk(logger, "版本号", "versionCode",
                before == null ? null : String.valueOf(before),
                String.valueOf(versionCode));
    }

    public static void setAppLabel(ApkModule module, String label, SimpleApkLogger logger) {
        requireText(label, "appLabel");
        AndroidManifestBlock m = requireManifest(module);
        String before = m.getApplicationLabelString();
        if (before == null) {
            Integer ref = m.getApplicationLabelReference();
            if (ref != null) before = "@0x" + Integer.toHexString(ref);
        }
        m.setApplicationLabel(label.trim());
        refresh(module);
        logOk(logger, "应用名", "appLabel", before, label.trim());
    }

    public static void setDebuggable(ApkModule module, boolean debuggable, SimpleApkLogger logger) {
        AndroidManifestBlock m = requireManifest(module);
        Boolean before = isDebuggable(m);
        m.setDebuggable(debuggable);
        refresh(module);
        logOk(logger, "debuggable", "debuggable",
                before == null ? null : String.valueOf(before),
                String.valueOf(debuggable));
    }

    public static void setExtractNativeLibs(ApkModule module, boolean value, SimpleApkLogger logger) {
        if (value) {
            ManifestWorkflow.forceExtractNativeLibsTrue(module, logger);
            return;
        }
        AndroidManifestBlock m = requireManifest(module);
        Boolean before = m.isExtractNativeLibs();
        module.setExtractNativeLibs(Boolean.FALSE);
        m.setExtractNativeLibs(Boolean.FALSE);
        refresh(module);
        logOk(logger, "extractNativeLibs", "extractNativeLibs",
                before == null ? null : String.valueOf(before), "false");
    }

    public static void setApplicationClassName(ApkModule module, String className, SimpleApkLogger logger) {
        requireText(className, "applicationClass");
        AndroidManifestBlock m = requireManifest(module);
        String before = m.getApplicationClassName();
        m.setApplicationClassName(className.trim());
        refresh(module);
        logOk(logger, "Application", "applicationClass", before, className.trim());
    }

    public static void setMainActivityClassName(ApkModule module, String className, SimpleApkLogger logger) {
        requireText(className, "mainActivity");
        AndroidManifestBlock m = requireManifest(module);
        String before = m.getMainActivityClassName();
        m.setMainActivityClassName(className.trim());
        refresh(module);
        logOk(logger, "MainActivity", "mainActivity", before, className.trim());
    }

    public static void setMinSdkVersion(ApkModule module, int minSdk, SimpleApkLogger logger) {
        AndroidManifestBlock m = requireManifest(module);
        Integer before = m.getMinSdkVersion();
        m.setMinSdkVersion(minSdk);
        refresh(module);
        logOk(logger, "minSdk", "minSdk",
                before == null ? null : String.valueOf(before),
                String.valueOf(minSdk));
    }

    public static void setTargetSdkVersion(ApkModule module, int targetSdk, SimpleApkLogger logger) {
        AndroidManifestBlock m = requireManifest(module);
        Integer before = m.getTargetSdkVersion();
        m.setTargetSdkVersion(targetSdk);
        refresh(module);
        logOk(logger, "targetSdk", "targetSdk",
                before == null ? null : String.valueOf(before),
                String.valueOf(targetSdk));
    }

    public static Integer getMaxSdkVersion(ApkModule module) {
        AndroidManifestBlock m = requireManifest(module);
        ResXmlElement manifest = m.getManifestElement();
        if (manifest == null) return null;
        ResXmlElement usesSdk = manifest.getElement(AndroidManifest.TAG_uses_sdk);
        if (usesSdk == null) return null;
        ResXmlAttribute attr = usesSdk.searchAttributeByResourceId(AndroidManifest.ID_maxSdkVersion);
        if (attr == null) attr = usesSdk.searchAttributeByName(AndroidManifest.NAME_maxSdkVersion);
        if (attr == null || attr.getValueType() != ValueType.DEC) return null;
        return attr.getData();
    }

    public static void setMaxSdkVersion(ApkModule module, int maxSdk, SimpleApkLogger logger) {
        AndroidManifestBlock m = requireManifest(module);
        Integer before = getMaxSdkVersion(module);
        // getOrCreateManifestElement() is private in REAndroid; use public getter.
        ResXmlElement manifest = m.getManifestElement();
        if (manifest == null) {
            throw new IllegalStateException("Missing <manifest> element");
        }
        ResXmlElement usesSdk = manifest.getElement(AndroidManifest.TAG_uses_sdk);
        if (usesSdk == null) {
            // Ensure uses-sdk exists via public API path (creates element internally).
            Integer minSdk = m.getMinSdkVersion();
            m.setMinSdkVersion(minSdk != null ? minSdk : 1);
            usesSdk = manifest.getElement(AndroidManifest.TAG_uses_sdk);
        }
        if (usesSdk == null) {
            usesSdk = manifest.newElement(AndroidManifest.TAG_uses_sdk);
        }
        ResXmlAttribute attr = usesSdk.getOrCreateAndroidAttribute(
                AndroidManifest.NAME_maxSdkVersion, AndroidManifest.ID_maxSdkVersion);
        attr.setTypeAndData(ValueType.DEC, maxSdk);
        refresh(module);
        logOk(logger, "maxSdk", "maxSdk",
                before == null ? null : String.valueOf(before),
                String.valueOf(maxSdk));
    }

    public static boolean removeMaxSdkVersion(ApkModule module, SimpleApkLogger logger) {
        AndroidManifestBlock m = requireManifest(module);
        ResXmlElement manifest = m.getManifestElement();
        if (manifest == null) return false;
        ResXmlElement usesSdk = manifest.getElement(AndroidManifest.TAG_uses_sdk);
        if (usesSdk == null) return false;
        boolean removed = usesSdk.removeAttributesWithId(AndroidManifest.ID_maxSdkVersion)
                || usesSdk.removeAttributesWithName(AndroidManifest.NAME_maxSdkVersion);
        if (removed) {
            refresh(module);
            if (logger != null) logger.ok("已删 maxSdk", "maxSdk removed");
        }
        return removed;
    }

    public static void refreshManifest(ApkModule module, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        module.refreshManifest();
        if (logger != null) logger.ok("Manifest 已刷新", "Manifest refreshed");
    }

    public static void addUsesPermission(ApkModule module, String permission, SimpleApkLogger logger) {
        requireText(permission, "permission");
        AndroidManifestBlock m = requireManifest(module);
        m.addUsesPermission(permission.trim());
        refresh(module);
        if (logger != null) {
            logger.ok("已加权限", "Permission added", permission.trim());
        }
    }

    public static boolean removeUsesPermission(ApkModule module, String permission, SimpleApkLogger logger) {
        requireText(permission, "permission");
        AndroidManifestBlock m = requireManifest(module);
        ResXmlElement el = m.getUsesPermission(permission.trim());
        if (el == null) {
            if (logger != null) {
                logger.bi("权限不存在", "Permission missing", permission.trim());
            }
            return false;
        }
        ResXmlElement parent = el.getParentElement();
        if (parent != null) {
            parent.remove(el);
        } else {
            return false;
        }
        refresh(module);
        if (logger != null) {
            logger.ok("已删权限", "Permission removed", permission.trim());
        }
        return true;
    }

    public static List<String> listUsesPermissions(ApkModule module) {
        return listUsesPermissions(requireManifest(module));
    }

    public static List<String> listUsesPermissions(AndroidManifestBlock manifest) {
        if (manifest == null) return Collections.emptyList();
        List<String> list = manifest.getUsesPermissions();
        return list == null ? new ArrayList<String>() : new ArrayList<String>(list);
    }

    public static Boolean isDebuggable(AndroidManifestBlock manifest) {
        if (manifest == null) return null;
        ResXmlElement application = manifest.getApplicationElement();
        if (application == null) return null;
        ResXmlAttribute attr = application.searchAttributeByResourceId(AndroidManifest.ID_debuggable);
        if (attr == null) {
            attr = application.searchAttributeByName(AndroidManifest.NAME_debuggable);
        }
        if (attr == null || attr.getValueType() != ValueType.BOOLEAN) return null;
        return attr.getValueAsBoolean();
    }

    private static void refresh(ApkModule module) {
        AndroidManifestBlock m = module.getAndroidManifest();
        if (m != null) {
            m.refresh();
            module.setManifest(m);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is blank");
        }
    }

    private static void logOk(SimpleApkLogger logger, String zh, String en, String before, String after) {
        if (logger == null) return;
        logger.ok("已改" + zh, en + " set", safe(before) + " → " + safe(after));
    }

    private static String safe(String s) {
        return s == null ? "?" : s;
    }
}