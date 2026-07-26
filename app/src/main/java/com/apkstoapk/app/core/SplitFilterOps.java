package com.apkstoapk.app.core;

import android.content.Context;

import com.apkstoapk.app.util.SimpleApkLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Split name filtering helpers.
 * Device-based path wraps {@link SplitSelector}. Pure filters need no Context.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class SplitFilterOps {
    private SplitFilterOps() {}

    /** Names that should be excluded for current device. */
    public static List<String> excludeNotForDevice(Context context, List<String> allSplitNames) {
        return SplitSelector.excludeNotForDevice(context, allSplitNames);
    }

    public static List<String> excludeNotForDevice(
            Context context,
            List<String> allSplitNames,
            SimpleApkLogger logger
    ) {
        List<String> exclude = SplitSelector.excludeNotForDevice(context, allSplitNames);
        if (logger != null && exclude != null && !exclude.isEmpty()) {
            logger.bi("按设备排除分包", "Exclude splits for device");
            for (String s : exclude) logger.item("排除", "Exclude", s);
        }
        return exclude;
    }

    /** Keep only splits whose simple name contains any of the tokens (case-insensitive). */
    public static List<String> excludeNotMatchingTokens(
            List<String> allSplitNames,
            List<String> keepTokens
    ) {
        if (allSplitNames == null || allSplitNames.isEmpty()) return new ArrayList<>();
        if (keepTokens == null || keepTokens.isEmpty()) return new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (String t : keepTokens) {
            if (t != null && !t.trim().isEmpty()) {
                tokens.add(t.trim().toLowerCase(Locale.US));
            }
        }
        List<String> exclude = new ArrayList<>();
        for (String name : allSplitNames) {
            if (name == null) continue;
            String simple = name;
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0) simple = name.substring(slash + 1);
            String n = simple.toLowerCase(Locale.US);
            if (isLikelyBase(n)) continue;
            boolean keep = false;
            for (String t : tokens) {
                if (n.contains(t)) {
                    keep = true;
                    break;
                }
            }
            // config splits that match no token → exclude
            if (!keep && (n.contains("config") || n.startsWith("split"))) {
                exclude.add(name);
            }
        }
        return exclude;
    }

    /** Invert: return names to keep = all - exclude. */
    public static List<String> keepList(List<String> all, List<String> exclude) {
        List<String> out = new ArrayList<>();
        if (all == null) return out;
        for (String a : all) {
            if (a == null) continue;
            if (exclude == null || !containsName(exclude, a)) out.add(a);
        }
        return out;
    }

    private static boolean containsName(List<String> list, String name) {
        for (String e : list) {
            if (e == null) continue;
            if (e.equals(name)) return true;
            String simple = new java.io.File(name).getName();
            if (e.equals(simple) || name.endsWith("/" + e)) return true;
        }
        return false;
    }

    private static boolean isLikelyBase(String simpleLower) {
        return "base.apk".equals(simpleLower)
                || (!simpleLower.startsWith("config")
                && !simpleLower.startsWith("split")
                && !simpleLower.contains("config."));
    }
}