package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;

/**
 * Application icon resource id helpers (manifest reference only).
 * Does not replace PNG/WebP bytes; use {@link EntryOps} for file replace.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class IconOps {
    private IconOps() {}

    public static int getIconResourceId(ApkModule module) {
        return ManifestOps.requireManifest(module).getIconResourceId();
    }

    public static int getRoundIconResourceId(ApkModule module) {
        return ManifestOps.requireManifest(module).getRoundIconResourceId();
    }

    public static void setIconResourceId(ApkModule module, int resourceId, SimpleApkLogger logger) {
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        int before = m.getIconResourceId();
        m.setIconResourceId(resourceId);
        m.refresh();
        module.setManifest(m);
        if (logger != null) {
            logger.ok("已设 icon", "icon set",
                    "0x" + Integer.toHexString(before) + " → 0x" + Integer.toHexString(resourceId));
        }
    }

    public static void setRoundIconResourceId(ApkModule module, int resourceId,
                                              SimpleApkLogger logger) {
        AndroidManifestBlock m = ManifestOps.requireManifest(module);
        int before = m.getRoundIconResourceId();
        m.setRoundIconResourceId(resourceId);
        m.refresh();
        module.setManifest(m);
        if (logger != null) {
            logger.ok("已设 roundIcon", "roundIcon set",
                    "0x" + Integer.toHexString(before) + " → 0x" + Integer.toHexString(resourceId));
        }
    }
}