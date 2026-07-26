package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ValueType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal resources.arsc string helpers (default/any config).
 * Not a full resource editor. Not wired into UI / {@link ApksMerger}.
 */
public final class StringResOps {
    private StringResOps() {}

    public static final class StringItem {
        public final int resourceId;
        public final String type;
        public final String name;
        public final String value;
        public final String config;

        public StringItem(int resourceId, String type, String name, String value, String config) {
            this.resourceId = resourceId;
            this.type = type;
            this.name = name;
            this.value = value;
            this.config = config;
        }

        @Override
        public String toString() {
            return type + "/" + name + " (" + config + ") = " + value;
        }
    }

    public static TableBlock requireTable(ApkModule module) {
        if (module == null || !module.hasTableBlock()) {
            throw new IllegalStateException("ApkModule has no resources.arsc");
        }
        TableBlock table = module.getTableBlock();
        if (table == null) throw new IllegalStateException("TableBlock is null");
        return table;
    }

    public static PackageBlock pickPackage(ApkModule module) {
        TableBlock table = requireTable(module);
        PackageBlock pkg = table.pickOne();
        if (pkg == null) throw new IllegalStateException("No resource package in table");
        return pkg;
    }

    /** Read first non-null string value for type/name (usually type=\"string\"). */
    public static String getString(ApkModule module, String type, String name) {
        ResourceEntry re = find(module, type, name);
        if (re == null) return null;
        Iterator<Entry> it = re.iterator(true);
        while (it != null && it.hasNext()) {
            Entry e = it.next();
            if (e == null) continue;
            if (e.getValueType() == ValueType.STRING) {
                return e.getValueAsString();
            }
        }
        return null;
    }

    public static String getString(ApkModule module, String name) {
        return getString(module, "string", name);
    }

    public static String getStringById(ApkModule module, int resourceId) {
        TableBlock table = requireTable(module);
        ResourceEntry re = table.getResource(resourceId);
        if (re == null) return null;
        Iterator<Entry> it = re.iterator(true);
        while (it != null && it.hasNext()) {
            Entry e = it.next();
            if (e != null && e.getValueType() == ValueType.STRING) {
                return e.getValueAsString();
            }
        }
        return null;
    }

    /**
     * Write string into the first existing config entry, or default/any entry.
     * Creates entry via package getOrCreate when missing.
     */
    public static void setString(
            ApkModule module,
            String type,
            String name,
            String value,
            SimpleApkLogger logger
    ) {
        if (type == null || type.trim().isEmpty()) type = "string";
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name is blank");
        }
        if (value == null) throw new IllegalArgumentException("value is null");
        PackageBlock pkg = pickPackage(module);
        Entry entry = null;
        ResourceEntry re = pkg.getResource(type.trim(), name.trim());
        if (re != null) {
            entry = re.any();
        }
        if (entry == null) {
            entry = pkg.getOrCreate("", type.trim(), name.trim());
        }
        if (entry == null) {
            throw new IllegalStateException("Cannot create resource " + type + "/" + name);
        }
        String before = entry.getValueAsString();
        entry.setValueAsString(value);
        TableBlock table = module.getTableBlock();
        if (table != null) table.refresh();
        if (logger != null) {
            logger.ok("字符串资源已写", "string res set",
                    type + "/" + name + ": " + safe(before) + " → " + value);
        }
    }

    public static void setString(ApkModule module, String name, String value, SimpleApkLogger logger) {
        setString(module, "string", name, value, logger);
    }

    /**
     * Prefer rewriting @string app_name resource; falls back to literal application label.
     */
    public static void setAppLabelPreferResource(
            ApkModule module,
            String label,
            SimpleApkLogger logger
    ) {
        if (label == null) throw new IllegalArgumentException("label is null");
        Integer ref = ManifestOps.requireManifest(module).getApplicationLabelReference();
        if (ref != null && ref != 0 && module.hasTableBlock()) {
            TableBlock table = module.getTableBlock();
            ResourceEntry re = table.getResource(ref);
            if (re != null) {
                Entry entry = re.any();
                if (entry != null) {
                    String before = entry.getValueAsString();
                    entry.setValueAsString(label);
                    table.refresh();
                    if (logger != null) {
                        logger.ok("应用名资源已写", "app label resource set",
                                re.getType() + "/" + re.getName()
                                        + ": " + safe(before) + " → " + label);
                    }
                    return;
                }
            }
        }
        ManifestOps.setAppLabel(module, label, logger);
    }

    /** List string-type resources with at least one STRING value (best-effort, may be large). */
    public static List<StringItem> listStrings(ApkModule module, int maxItems) {
        int limit = maxItems <= 0 ? 200 : maxItems;
        PackageBlock pkg = pickPackage(module);
        List<StringItem> out = new ArrayList<>();
        Iterator<ResourceEntry> it = pkg.getResources("string");
        if (it == null) return out;
        while (it.hasNext() && out.size() < limit) {
            ResourceEntry re = it.next();
            if (re == null) continue;
            Entry e = re.any();
            if (e == null || e.getValueType() != ValueType.STRING) continue;
            String cfg = e.getResConfig() != null ? e.getResConfig().getQualifiers() : "";
            if (cfg == null || cfg.isEmpty()) cfg = "default";
            out.add(new StringItem(re.getResourceId(), "string", re.getName(),
                    e.getValueAsString(), cfg));
        }
        return out;
    }

    private static ResourceEntry find(ApkModule module, String type, String name) {
        if (!module.hasTableBlock()) return null;
        if (type == null || name == null) return null;
        PackageBlock pkg = module.getTableBlock().pickOne();
        if (pkg == null) return null;
        return pkg.getResource(type.trim(), name.trim());
    }

    private static String safe(String s) {
        return s == null ? "?" : s;
    }
}