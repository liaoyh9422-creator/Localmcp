package com.apkstoapk.app.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback map for android: attribute resource ids -> readable names.
 * Used when framework table is missing/incomplete so export won't show r0x........
 */
public final class AndroidAttrNames {
    private static final Pattern R0X = Pattern.compile("(?:android:)?r0x([0-9a-fA-F]{8})");

    private static final Map<Integer, String> ATTRS;

    static {
        Map<Integer, String> m = new HashMap<>();
        // Core / frequently seen in manifests
        put(m, 0x01010000, "theme");
        put(m, 0x01010001, "label");
        put(m, 0x01010002, "icon");
        put(m, 0x01010003, "name");
        put(m, 0x0101000e, "enabled");
        put(m, 0x0101000f, "debuggable");
        put(m, 0x01010010, "exported");
        put(m, 0x01010011, "process");
        put(m, 0x01010012, "taskAffinity");
        put(m, 0x01010013, "multiprocess");
        put(m, 0x01010014, "finishOnTaskLaunch");
        put(m, 0x01010015, "clearTaskOnLaunch");
        put(m, 0x01010016, "stateNotNeeded");
        put(m, 0x01010017, "excludeFromRecents");
        put(m, 0x01010018, "authorities");
        put(m, 0x01010019, "syncable");
        put(m, 0x0101001a, "readPermission");
        put(m, 0x0101001b, "writePermission");
        put(m, 0x0101001c, "grantUriPermissions");
        put(m, 0x0101001d, "permission");
        put(m, 0x0101001e, "screenOrientation");
        put(m, 0x0101001f, "configChanges");
        put(m, 0x01010020, "description");
        put(m, 0x01010021, "targetPackage");
        put(m, 0x01010022, "handleProfiling");
        put(m, 0x01010023, "functionalTest");
        put(m, 0x01010024, "value");
        put(m, 0x01010025, "resource");
        put(m, 0x01010026, "hasCode");
        put(m, 0x01010027, "persistent");
        put(m, 0x01010028, "host");
        put(m, 0x0101002b, "sharedUserId");
        put(m, 0x0101002c, "labelFor");
        put(m, 0x0101002d, "backupAgent");
        put(m, 0x0101002e, "allowClearUserData");
        put(m, 0x0101002f, "manageSpaceActivity");
        put(m, 0x01010030, "priority");
        put(m, 0x01010031, "launchMode");
        put(m, 0x01010032, "screenSize");
        put(m, 0x01010033, "screenDensity");
        put(m, 0x01010034, "anyDensity");
        put(m, 0x01010035, "glEsVersion");
        put(m, 0x01010036, "reqTouchScreen");
        put(m, 0x01010037, "reqKeyboardType");
        put(m, 0x01010038, "reqHardKeyboard");
        put(m, 0x01010039, "reqNavigation");
        put(m, 0x0101003a, "windowSoftInputMode");
        put(m, 0x0101003b, "inputType");
        put(m, 0x0101003c, "imeOptions");
        put(m, 0x010100b3, "protectionLevel");
        put(m, 0x010100b4, "permissionGroup");
        put(m, 0x010100ba, "permissionFlags");
        put(m, 0x010100c4, "orientation");
        put(m, 0x010100d0, "id");
        put(m, 0x010100e0, "background");
        put(m, 0x010100e5, "clickable");
        put(m, 0x010100f2, "layout");
        put(m, 0x010100f4, "layout_width");
        put(m, 0x010100f5, "layout_height");
        put(m, 0x0101011c, "directBootAware");
        put(m, 0x0101011e, "windowFullscreen");
        put(m, 0x010101e6, "required");
        put(m, 0x010101e8, "minSdkVersion"); // legacy sometimes
        put(m, 0x0101020c, "minSdkVersion");
        put(m, 0x0101020d, "versionCode"); // older mapping in some tables
        put(m, 0x0101021b, "versionCode");
        put(m, 0x0101021c, "versionName");
        put(m, 0x01010202, "targetActivity");
        put(m, 0x01010203, "alwaysRetainTaskState");
        put(m, 0x01010204, "allowTaskReparenting");
        put(m, 0x0101020b, "killAfterRestore");
        put(m, 0x0101020e, "restoreNeedsApplication");
        put(m, 0x0101021d, "installLocation");
        put(m, 0x01010227, "uiOptions");
        put(m, 0x0101022b, "hardwareAccelerated");
        put(m, 0x0101024c, "largeHeap");
        put(m, 0x0101025c, "supportsRtl"); // may differ by API, keep also 0x010103af
        put(m, 0x0101026c, "parentActivityName");
        put(m, 0x01010270, "targetSdkVersion");
        put(m, 0x01010271, "maxSdkVersion");
        put(m, 0x01010272, "testOnly");
        put(m, 0x01010280, "logo");
        put(m, 0x0101028e, "banner");
        put(m, 0x010102b7, "allowBackup");
        put(m, 0x010102bf, "vmSafeMode");
        put(m, 0x010102c0, "singleUser");
        put(m, 0x010102c1, "protectedBroadcast");
        put(m, 0x010102c7, "stopWithTask");
        put(m, 0x010102d3, "allowEmbedded");
        put(m, 0x010102eb, "documentLaunchMode");
        put(m, 0x010102ec, "maxRecents");
        put(m, 0x010102ef, "autoRemoveFromRecents");
        put(m, 0x010102f0, "relinquishTaskIdentity");
        put(m, 0x01010303, "resumeWhilePausing");
        put(m, 0x0101030f, "supportsPictureInPicture");
        put(m, 0x0101031f, "alpha");
        put(m, 0x01010320, "transformPivotX");
        put(m, 0x01010321, "transformPivotY");
        put(m, 0x01010322, "translationX");
        put(m, 0x01010323, "translationY");
        put(m, 0x01010324, "scaleX");
        put(m, 0x01010325, "scaleY");
        put(m, 0x01010326, "rotation");
        put(m, 0x01010327, "rotationX");
        put(m, 0x01010328, "rotationY");
        put(m, 0x010103a5, "requiredFeature");
        put(m, 0x010103a6, "requiredNotFeature");
        put(m, 0x010103af, "supportsRtl");
        put(m, 0x010103d5, "permissionGroupFlags");
        put(m, 0x010103e8, "isGame");
        put(m, 0x010103f5, "fullBackupOnly");
        put(m, 0x010103f6, "theme");
        put(m, 0x01010400, "usesCleartextTraffic");
        put(m, 0x01010403, "resizeableActivity");
        put(m, 0x0101040c, "networkSecurityConfig");
        put(m, 0x0101045e, "roundIcon"); // older
        put(m, 0x010104ea, "extractNativeLibs");
        put(m, 0x0101050c, "appCategory");
        put(m, 0x0101052c, "roundIcon");
        put(m, 0x0101054b, "isolatedSplits");
        put(m, 0x0101055b, "isFeatureSplit");
        put(m, 0x01010572, "compileSdkVersion");
        put(m, 0x01010573, "compileSdkVersionCodename");
        put(m, 0x0101057c, "appComponentFactory");
        put(m, 0x0101057f, "gwpAsanMode");
        put(m, 0x01010591, "isSplitRequired");
        put(m, 0x010105b0, "requestLegacyExternalStorage");
        put(m, 0x010105b7, "forceQueryable");
        put(m, 0x010105bb, "hasFragileUserData");
        put(m, 0x010105c2, "allowNativeHeapPointerTagging");
        put(m, 0x010105c4, "autoRevokePermissions");
        put(m, 0x010105e3, "preserveLegacyExternalStorage");
        put(m, 0x010105f5, "enableOnBackInvokedCallback");
        put(m, 0x0101064e, "requiredSplitTypes");
        put(m, 0x0101064f, "splitTypes");
        put(m, 0x01010659, "dataExtractionRules");
        put(m, 0x01010663, "localeConfig");
        put(m, 0x0101066c, "knownActivityEmbeddingCerts");
        put(m, 0x01010681, "allowAudioPlaybackCapture");
        // Non-resource-id attributes still appear as plain names (package, platformBuildVersionCode...)
        ATTRS = Collections.unmodifiableMap(m);
    }

    private AndroidAttrNames() {}

    public static String nameOf(int attrId) {
        return ATTRS.get(attrId);
    }

    public static String nameOfHex(String hex8) {
        if (hex8 == null) return null;
        try {
            return nameOf((int) Long.parseLong(hex8, 16));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Replace android:r0xXXXXXXXX / r0xXXXXXXXX with readable attribute names when known.
     * Leaves unknown ids untouched.
     */
    public static String replaceUnknownAttrNames(String xml) {
        if (xml == null || xml.isEmpty() || !xml.contains("r0x")) {
            return xml;
        }
        Matcher matcher = R0X.matcher(xml);
        StringBuffer sb = new StringBuffer(xml.length());
        while (matcher.find()) {
            String hex = matcher.group(1);
            String name = nameOfHex(hex);
            if (name == null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String full = matcher.group();
            String replacement = full.regionMatches(true, 0, "android:", 0, 8)
                    ? "android:" + name
                    : name;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static Map<Integer, String> all() {
        return ATTRS;
    }

    private static void put(Map<Integer, String> map, int id, String name) {
        map.put(id, name);
    }
}
