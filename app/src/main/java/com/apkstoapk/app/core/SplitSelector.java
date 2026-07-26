package com.apkstoapk.app.core;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Helps decide which config splits to keep for current device.
 * Adapted from AntiSplit-M DeviceSpecsUtil ideas (simplified).
 */
public final class SplitSelector {
    private SplitSelector() {}

    public static List<String> excludeNotForDevice(Context context, List<String> allSplitNames) {
        if (allSplitNames == null || allSplitNames.isEmpty()) {
            return new ArrayList<>();
        }
        String lang = Locale.getDefault().getLanguage();
        String density = densityName(context);
        String[] abis = Build.SUPPORTED_ABIS;

        List<String> toExclude = new ArrayList<>();
        boolean hasArchSplit = false;
        boolean hasLangSplit = false;
        boolean hasDpiSplit = false;

        for (String name : allSplitNames) {
            String n = name.toLowerCase(Locale.US);
            if (isArch(n)) hasArchSplit = true;
            if (isLang(n)) hasLangSplit = true;
            if (n.contains("dpi")) hasDpiSplit = true;
        }

        for (String name : allSplitNames) {
            String simple = name;
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0) simple = name.substring(slash + 1);
            String n = simple.toLowerCase(Locale.US);

            if (isBase(n)) {
                continue;
            }
            if (hasArchSplit && isArch(n) && !matchesAnyAbi(n, abis)) {
                toExclude.add(name);
                continue;
            }
            if (hasDpiSplit && n.contains("dpi") && !matchesDensity(n, density)) {
                toExclude.add(name);
                continue;
            }
            if (hasLangSplit && isLang(n) && !n.contains(lang.toLowerCase(Locale.US))) {
                // keep en as fallback if present? exclude non-matching languages
                if (!n.contains("en")) {
                    toExclude.add(name);
                } else if (!lang.toLowerCase(Locale.US).startsWith("en")) {
                    // if device not en, still keep en as fallback by not excluding here only when no device lang exists
                }
            }
        }
        return toExclude;
    }

    private static boolean isBase(String name) {
        return name.equals("base.apk")
                || (!name.startsWith("config") && !name.startsWith("split") && !name.contains("config."));
    }

    private static boolean isArch(String name) {
        return name.contains("armeabi")
                || name.contains("arm64")
                || name.contains("x86")
                || name.contains("mips");
    }

    private static boolean isLang(String name) {
        // config.zh.apk / split_config.zh_cn.apk style, without dpi/arch
        if (!name.contains("config")) return false;
        if (name.contains("dpi") || isArch(name)) return false;
        return name.matches(".*config[._][a-z]{2}([._-][a-z0-9]+)?\\.apk");
    }

    private static boolean matchesAnyAbi(String name, String[] abis) {
        for (String abi : abis) {
            if (abi == null) continue;
            String a = abi.toLowerCase(Locale.US).replace('-', '_');
            String n = name.replace('-', '_');
            if (n.contains(a)) return true;
            if (a.contains("arm64") && n.contains("arm64")) return true;
            if (a.contains("armeabi_v7a") && (n.contains("armeabi_v7a") || n.contains("arm7"))) return true;
            if (a.equals("x86_64") && (n.contains("x86_64") || n.contains("x86-64") || n.contains("x64"))) return true;
            if (a.equals("x86") && n.contains("x86") && !n.contains("x86_64") && !n.contains("x86-64")) return true;
        }
        return false;
    }

    private static boolean matchesDensity(String name, String density) {
        // avoid xxhdpi matching xhdpi
        String token = density + ".apk";
        String token2 = density + "_";
        if (name.contains(density)) {
            // reject longer density that also contains this as substring incorrectly
            if ("xhdpi".equals(density) && (name.contains("xxhdpi") || name.contains("xxxhdpi"))) {
                return false;
            }
            if ("hdpi".equals(density) && (name.contains("xhdpi") || name.contains("xxhdpi") || name.contains("xxxhdpi"))) {
                return false;
            }
            if ("mdpi".equals(density) && name.contains("tvdpi") == false) {
                // ok
            }
            return name.contains(density);
        }
        return name.endsWith(token) || name.contains(token2);
    }

    private static String densityName(Context context) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        if (dpi <= DisplayMetrics.DENSITY_LOW) return "ldpi";
        if (dpi <= DisplayMetrics.DENSITY_MEDIUM) return "mdpi";
        if (dpi <= DisplayMetrics.DENSITY_TV) return "tvdpi";
        if (dpi <= DisplayMetrics.DENSITY_HIGH) return "hdpi";
        if (dpi <= DisplayMetrics.DENSITY_XHIGH) return "xhdpi";
        if (dpi <= DisplayMetrics.DENSITY_XXHIGH) return "xxhdpi";
        return "xxxhdpi";
    }
}
