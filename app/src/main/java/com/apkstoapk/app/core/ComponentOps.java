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
import java.util.Locale;

/**
 * List / query / tweak application components in AndroidManifest.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class ComponentOps {
    private ComponentOps() {}

    public static final class ComponentInfo {
        public final String tag;
        public final String name;
        public final Boolean exported;

        public ComponentInfo(String tag, String name, Boolean exported) {
            this.tag = tag;
            this.name = name;
            this.exported = exported;
        }

        @Override
        public String toString() {
            return tag + " name=" + name + " exported=" + exported;
        }
    }

    public static List<ComponentInfo> listActivities(ApkModule module, boolean includeAlias) {
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        List<ComponentInfo> out = new ArrayList<>();
        Iterator<ResXmlElement> it = m.getActivities(includeAlias);
        while (it != null && it.hasNext()) {
            ResXmlElement el = it.next();
            out.add(toInfo(el));
        }
        return out;
    }

    public static List<ComponentInfo> listServices(ApkModule module) {
        return listByTag(module, AndroidManifest.TAG_service);
    }

    public static List<ComponentInfo> listReceivers(ApkModule module) {
        return listByTag(module, AndroidManifest.TAG_receiver);
    }

    public static List<ComponentInfo> listProviders(ApkModule module) {
        return listByTag(module, AndroidManifest.TAG_provider);
    }

    public static List<ComponentInfo> listAll(ApkModule module) {
        List<ComponentInfo> out = new ArrayList<>();
        out.addAll(listActivities(module, true));
        out.addAll(listServices(module));
        out.addAll(listReceivers(module));
        out.addAll(listProviders(module));
        return out;
    }

    public static String getMainActivityClassName(ApkModule module) {
        return ManifestOps.requireManifest(module).getMainActivityClassName();
    }

    public static void setMainActivityClassName(ApkModule module, String className,
                                                SimpleApkLogger logger) {
        ManifestOps.setMainActivityClassName(module, className, logger);
    }

    public static void setApplicationClassName(ApkModule module, String className,
                                               SimpleApkLogger logger) {
        ManifestOps.setApplicationClassName(module, className, logger);
    }

    /**
     * Set android:exported on a component matched by tag + name.
     * name may be relative (".Main") or full.
     */
    public static boolean setExported(
            ApkModule module,
            String tag,
            String componentName,
            boolean exported,
            SimpleApkLogger logger
    ) {
        if (tag == null || tag.trim().isEmpty()) {
            throw new IllegalArgumentException("tag is blank");
        }
        if (componentName == null || componentName.trim().isEmpty()) {
            throw new IllegalArgumentException("componentName is blank");
        }
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        String want = componentName.trim();
        String pkg = m.getPackageName();
        List<ResXmlElement> list = m.listApplicationElementsByTag(tag.trim());
        if (list == null) list = Collections.emptyList();
        for (ResXmlElement el : list) {
            String name = readName(el);
            if (name == null) continue;
            if (nameMatches(name, want, pkg)) {
                ResXmlAttribute attr = el.getOrCreateAndroidAttribute(
                        AndroidManifest.NAME_exported, AndroidManifest.ID_exported);
                attr.setValueAsBoolean(exported);
                m.refresh();
                module.setManifest(m);
                if (logger != null) {
                    logger.ok("已设 exported", "exported set",
                            tag + " " + name + " → " + exported);
                }
                return true;
            }
        }
        if (logger != null) {
            logger.bi("未找到组件", "Component not found", tag + " " + want);
        }
        return false;
    }

    public static boolean setActivityExported(ApkModule module, String name, boolean exported,
                                              SimpleApkLogger logger) {
        return setExported(module, AndroidManifest.TAG_activity, name, exported, logger)
                || setExported(module, AndroidManifest.TAG_activity_alias, name, exported, logger);
    }

    private static List<ComponentInfo> listByTag(ApkModule module, String tag) {
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        List<ResXmlElement> list = m.listApplicationElementsByTag(tag);
        if (list == null || list.isEmpty()) return new ArrayList<>();
        List<ComponentInfo> out = new ArrayList<>(list.size());
        for (ResXmlElement el : list) {
            out.add(toInfo(el));
        }
        return out;
    }

    private static ComponentInfo toInfo(ResXmlElement el) {
        String tag = el != null ? el.getName() : "?";
        return new ComponentInfo(tag, readName(el), readExported(el));
    }

    private static String readName(ResXmlElement el) {
        if (el == null) return null;
        ResXmlAttribute attr = el.searchAttributeByResourceId(AndroidManifest.ID_name);
        if (attr == null) attr = el.searchAttributeByName(AndroidManifest.NAME_name);
        if (attr == null || attr.getValueType() != ValueType.STRING) return null;
        return attr.getValueAsString();
    }

    private static Boolean readExported(ResXmlElement el) {
        if (el == null) return null;
        ResXmlAttribute attr = el.searchAttributeByResourceId(AndroidManifest.ID_exported);
        if (attr == null) attr = el.searchAttributeByName(AndroidManifest.NAME_exported);
        if (attr == null || attr.getValueType() != ValueType.BOOLEAN) return null;
        return attr.getValueAsBoolean();
    }

    private static boolean nameMatches(String actual, String want, String pkg) {
        if (actual.equals(want)) return true;
        String a = actual;
        String w = want;
        if (pkg != null) {
            if (a.startsWith(".")) a = pkg + a;
            if (w.startsWith(".")) w = pkg + w;
            if (!a.contains(".") && a.length() > 0) a = pkg + "." + a;
            if (!w.contains(".") && w.length() > 0) w = pkg + "." + w;
        }
        return a.equals(w) || a.toLowerCase(Locale.US).equals(w.toLowerCase(Locale.US));
    }
}