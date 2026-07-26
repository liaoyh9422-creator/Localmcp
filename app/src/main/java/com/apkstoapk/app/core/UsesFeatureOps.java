package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.app.AndroidManifest;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;
import com.reandroid.xml.XMLPath;

import java.util.ArrayList;
import java.util.List;

/**
 * &lt;uses-feature&gt; helpers on AndroidManifest.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class UsesFeatureOps {
    private static final int ID_REQUIRED = 0x0101028e;
    private static final int ID_GLES_VERSION = 0x01010281;
    private static final XMLPath PATH_USES_FEATURE =
            AndroidManifest.PATH_MANIFEST.element(AndroidManifest.TAG_uses_feature);

    private UsesFeatureOps() {}

    public static final class FeatureItem {
        public final String name;
        public final Boolean required;
        public final Integer glEsVersion;

        public FeatureItem(String name, Boolean required, Integer glEsVersion) {
            this.name = name;
            this.required = required;
            this.glEsVersion = glEsVersion;
        }

        @Override
        public String toString() {
            return "feature name=" + name
                    + " required=" + required
                    + (glEsVersion != null ? " gles=0x" + Integer.toHexString(glEsVersion) : "");
        }
    }

    public static List<FeatureItem> list(ApkModule module) {
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        List<ResXmlElement> els = listFeatureElements(m);
        List<FeatureItem> out = new ArrayList<>();
        for (ResXmlElement el : els) {
            FeatureItem item = toItem(el);
            if (item != null) out.add(item);
        }
        return out;
    }

    public static boolean has(ApkModule module, String featureName) {
        return find(module, featureName) != null;
    }

    public static void add(ApkModule module, String featureName, boolean required,
                           SimpleApkLogger logger) {
        if (featureName == null || featureName.trim().isEmpty()) {
            throw new IllegalArgumentException("featureName is blank");
        }
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        ResXmlElement el = m.getOrCreateNamedElement(PATH_USES_FEATURE, featureName.trim());
        ResXmlAttribute req = el.getOrCreateAndroidAttribute("required", ID_REQUIRED);
        req.setValueAsBoolean(required);
        m.refresh();
        module.setManifest(m);
        if (logger != null) {
            logger.ok("uses-feature 已加", "uses-feature added",
                    featureName.trim() + " required=" + required);
        }
    }

    public static void add(ApkModule module, String featureName, SimpleApkLogger logger) {
        add(module, featureName, true, logger);
    }

    public static boolean remove(ApkModule module, String featureName, SimpleApkLogger logger) {
        if (featureName == null || featureName.trim().isEmpty()) {
            throw new IllegalArgumentException("featureName is blank");
        }
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        ResXmlElement el = find(module, featureName);
        if (el == null) {
            if (logger != null) {
                logger.bi("uses-feature 不存在", "uses-feature missing", featureName.trim());
            }
            return false;
        }
        ResXmlElement parent = el.getParentElement();
        if (parent == null) return false;
        parent.remove(el);
        m.refresh();
        module.setManifest(m);
        if (logger != null) {
            logger.ok("uses-feature 已删", "uses-feature removed", featureName.trim());
        }
        return true;
    }

    private static ResXmlElement find(ApkModule module, String featureName) {
        if (featureName == null) return null;
        String want = featureName.trim();
        for (ResXmlElement el : listFeatureElements(ManifestOps.requireManifest(module))) {
            String n = readName(el);
            if (want.equals(n)) return el;
        }
        return null;
    }

    private static List<ResXmlElement> listFeatureElements(AndroidManifestBlock m) {
        List<ResXmlElement> out = new ArrayList<>();
        try {
            List<ResXmlElement> viaPath = PATH_USES_FEATURE.list(m);
            if (viaPath != null) {
                out.addAll(viaPath);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static FeatureItem toItem(ResXmlElement el) {
        String name = readName(el);
        Boolean required = null;
        Integer gles = null;
        ResXmlAttribute req = el.searchAttributeByResourceId(ID_REQUIRED);
        if (req == null) req = el.searchAttributeByName("required");
        if (req != null && req.getValueType() == ValueType.BOOLEAN) {
            required = req.getValueAsBoolean();
        }
        ResXmlAttribute glesAttr = el.searchAttributeByResourceId(ID_GLES_VERSION);
        if (glesAttr == null) glesAttr = el.searchAttributeByName("glEsVersion");
        if (glesAttr != null
                && (glesAttr.getValueType() == ValueType.HEX
                || glesAttr.getValueType() == ValueType.DEC)) {
            gles = glesAttr.getData();
        }
        if (name == null && gles == null) return null;
        return new FeatureItem(name, required, gles);
    }

    private static String readName(ResXmlElement el) {
        if (el == null) return null;
        ResXmlAttribute attr = el.searchAttributeByResourceId(AndroidManifest.ID_name);
        if (attr == null) attr = el.searchAttributeByName(AndroidManifest.NAME_name);
        if (attr == null || attr.getValueType() != ValueType.STRING) return null;
        return attr.getValueAsString();
    }
}