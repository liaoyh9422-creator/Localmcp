package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Batch permission helpers on top of {@link ManifestOps}.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class PermissionOps {
    private PermissionOps() {}

    public static List<String> list(ApkModule module) {
        return ManifestOps.listUsesPermissions(module);
    }

    public static boolean has(ApkModule module, String permission) {
        if (permission == null) return false;
        List<String> list = list(module);
        for (String p : list) {
            if (permission.equals(p)) return true;
        }
        return false;
    }

    public static void add(ApkModule module, String permission, SimpleApkLogger logger) {
        ManifestOps.addUsesPermission(module, permission, logger);
    }

    public static boolean remove(ApkModule module, String permission, SimpleApkLogger logger) {
        return ManifestOps.removeUsesPermission(module, permission, logger);
    }

    public static int addAll(ApkModule module, List<String> permissions, SimpleApkLogger logger) {
        if (permissions == null || permissions.isEmpty()) return 0;
        int n = 0;
        for (String p : permissions) {
            if (p == null || p.trim().isEmpty()) continue;
            if (!has(module, p.trim())) {
                ManifestOps.addUsesPermission(module, p.trim(), logger);
                n++;
            }
        }
        return n;
    }

    public static int removeAll(ApkModule module, List<String> permissions, SimpleApkLogger logger) {
        if (permissions == null || permissions.isEmpty()) return 0;
        int n = 0;
        for (String p : permissions) {
            if (p == null || p.trim().isEmpty()) continue;
            if (ManifestOps.removeUsesPermission(module, p.trim(), logger)) n++;
        }
        return n;
    }

    /** Ensure exactly the given set is present: add missing, remove extras not in keep list. */
    public static void replaceAll(
            ApkModule module,
            List<String> keepPermissions,
            SimpleApkLogger logger
    ) {
        List<String> keep = new ArrayList<>();
        if (keepPermissions != null) {
            for (String p : keepPermissions) {
                if (p != null && !p.trim().isEmpty()) keep.add(p.trim());
            }
        }
        List<String> current = list(module);
        for (String p : current) {
            if (!keep.contains(p)) {
                ManifestOps.removeUsesPermission(module, p, logger);
            }
        }
        for (String p : keep) {
            if (!has(module, p)) {
                ManifestOps.addUsesPermission(module, p, logger);
            }
        }
    }
}