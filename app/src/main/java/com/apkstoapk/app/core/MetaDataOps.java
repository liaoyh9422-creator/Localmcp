package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.app.AndroidManifest;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;

import java.util.ArrayList;
import java.util.List;

/**
 * Application-level &lt;meta-data&gt; helpers.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class MetaDataOps {
    private MetaDataOps() {}

    public static final class MetaItem {
        public final String name;
        public final String value;      // string value if present
        public final Integer resourceId; // android:resource if REFERENCE
        public final String raw;

        public MetaItem(String name, String value, Integer resourceId, String raw) {
            this.name = name;
            this.value = value;
            this.resourceId = resourceId;
            this.raw = raw;
        }

        @Override
        public String toString() {
            if (value != null) return name + "=" + value;
            if (resourceId != null) return name + "=@0x" + Integer.toHexString(resourceId);
            return name + "=" + raw;
        }
    }

    public static List<MetaItem> list(ApkModule module) {
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        List<ResXmlElement> els = m.listApplicationElementsByTag(AndroidManifest.TAG_meta_data);
        List<MetaItem> out = new ArrayList<>();
        if (els == null) return out;
        for (ResXmlElement el : els) {
            MetaItem item = toItem(el);
            if (item != null) out.add(item);
        }
        return out;
    }

    public static MetaItem get(ApkModule module, String name) {
        ResXmlElement el = find(module, name);
        return el == null ? null : toItem(el);
    }

    public static String getString(ApkModule module, String name) {
        MetaItem item = get(module, name);
        return item == null ? null : item.value;
    }

    public static void setString(ApkModule module, String name, String value, SimpleApkLogger logger) {
        requireText(name, "name");
        if (value == null) throw new IllegalArgumentException("value is null");
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        ResXmlElement el = m.getOrCreateNamedElement(
                AndroidManifest.PATH_APPLICATION_META_DATA, name.trim());
        // clear resource attr if any
        el.removeAttributesWithId(AndroidManifest.ID_resource);
        ResXmlAttribute val = el.getOrCreateAndroidAttribute(
                AndroidManifest.NAME_value, AndroidManifest.ID_value);
        val.setValueAsString(value);
        m.refresh();
        module.setManifest(m);
        if (logger != null) {
            logger.ok("meta-data 已写", "meta-data set", name.trim() + "=" + value);
        }
    }

    public static boolean remove(ApkModule module, String name, SimpleApkLogger logger) {
        requireText(name, "name");
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        ResXmlElement el = find(module, name);
        if (el == null) {
            if (logger != null) logger.bi("meta-data 不存在", "meta-data missing", name.trim());
            return false;
        }
        ResXmlElement parent = el.getParentElement();
        if (parent == null) return false;
        parent.remove(el);
        m.refresh();
        module.setManifest(m);
        if (logger != null) logger.ok("meta-data 已删", "meta-data removed", name.trim());
        return true;
    }

    private static ResXmlElement find(ApkModule module, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        List<ResXmlElement> els = m.listApplicationElementsByTag(AndroidManifest.TAG_meta_data);
        if (els == null) return null;
        String want = name.trim();
        for (ResXmlElement el : els) {
            String n = readName(el);
            if (want.equals(n)) return el;
        }
        return null;
    }

    private static MetaItem toItem(ResXmlElement el) {
        String name = readName(el);
        if (name == null) return null;
        ResXmlAttribute valueAttr = el.searchAttributeByResourceId(AndroidManifest.ID_value);
        if (valueAttr == null) valueAttr = el.searchAttributeByName(AndroidManifest.NAME_value);
        ResXmlAttribute resAttr = el.searchAttributeByResourceId(AndroidManifest.ID_resource);
        if (resAttr == null) resAttr = el.searchAttributeByName(AndroidManifest.NAME_resource);

        String value = null;
        Integer resId = null;
        String raw = null;
        if (valueAttr != null) {
            if (valueAttr.getValueType() == ValueType.STRING) {
                value = valueAttr.getValueAsString();
            } else if (valueAttr.getValueType() == ValueType.REFERENCE) {
                resId = valueAttr.getData();
                raw = "@0x" + Integer.toHexString(valueAttr.getData());
            } else {
                raw = String.valueOf(valueAttr.getData());
            }
        }
        if (resId == null && resAttr != null && resAttr.getValueType() == ValueType.REFERENCE) {
            resId = resAttr.getData();
            if (raw == null) raw = "@0x" + Integer.toHexString(resAttr.getData());
        }
        return new MetaItem(name, value, resId, raw);
    }

    private static String readName(ResXmlElement el) {
        if (el == null) return null;
        ResXmlAttribute attr = el.searchAttributeByResourceId(AndroidManifest.ID_name);
        if (attr == null) attr = el.searchAttributeByName(AndroidManifest.NAME_name);
        if (attr == null || attr.getValueType() != ValueType.STRING) return null;
        return attr.getValueAsString();
    }

    private static void requireText(String v, String field) {
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is blank");
        }
    }
}