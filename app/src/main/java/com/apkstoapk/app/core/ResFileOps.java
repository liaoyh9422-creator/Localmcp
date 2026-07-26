package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.ResFile;
import com.reandroid.arsc.value.Entry;

import java.util.ArrayList;
import java.util.List;

/**
 * Resource-file (res/*) helpers on a loaded {@link ApkModule}.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class ResFileOps {
    private ResFileOps() {}

    public static final class ResItem {
        public final String path;
        public final int entryCount;
        public final boolean binaryXml;

        public ResItem(String path, int entryCount, boolean binaryXml) {
            this.path = path;
            this.entryCount = entryCount;
            this.binaryXml = binaryXml;
        }

        @Override
        public String toString() {
            return path + " entries=" + entryCount + (binaryXml ? " binXml" : "");
        }
    }

    public static List<ResItem> list(ApkModule module) {
        if (module == null) throw new IllegalArgumentException("module is null");
        List<ResItem> out = new ArrayList<>();
        if (!module.hasTableBlock()) return out;
        for (ResFile rf : module.listResFiles()) {
            if (rf == null) continue;
            out.add(new ResItem(rf.getFilePath(), rf.size(), rf.isBinaryXml()));
        }
        return out;
    }

    public static List<String> listPaths(ApkModule module) {
        List<String> out = new ArrayList<>();
        for (ResItem item : list(module)) {
            if (item.path != null) out.add(item.path);
        }
        return out;
    }

    public static ResFile get(ApkModule module, String path) {
        if (module == null) throw new IllegalArgumentException("module is null");
        return module.getResFile(EntryOps.normalizeEntryPath(path));
    }

    public static boolean contains(ApkModule module, String path) {
        return get(module, path) != null;
    }

    /** Remove resource file + null out linked entries. keepResourceId=true preserves id slot. */
    public static boolean remove(ApkModule module, String path, boolean keepResourceId,
                                 SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        String p = EntryOps.normalizeEntryPath(path);
        boolean ok = module.removeResFile(p, keepResourceId);
        if (logger != null) {
            if (ok) logger.ok("资源文件已删", "Res file removed", p);
            else logger.bi("资源文件不存在", "Res file missing", p);
        }
        return ok;
    }

    public static boolean remove(ApkModule module, String path, SimpleApkLogger logger) {
        return remove(module, path, true, logger);
    }

    public static int removeByPrefix(ApkModule module, String prefix, boolean keepResourceId,
                                     SimpleApkLogger logger) {
        String p = EntryOps.normalizeEntryPath(prefix);
        if (!p.endsWith("/")) p = p + "/";
        int n = 0;
        for (String path : listPaths(module)) {
            if (path != null && path.startsWith(p)) {
                if (module.removeResFile(path, keepResourceId)) n++;
            }
        }
        if (logger != null) {
            logger.ok("前缀资源删除", "Res prefix remove", n + " @ " + p);
        }
        return n;
    }

    public static void setResourcesRootDir(ApkModule module, String dirName, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (dirName == null || dirName.trim().isEmpty()) {
            throw new IllegalArgumentException("dirName is blank");
        }
        module.setResourcesRootDir(dirName.trim());
        if (logger != null) {
            logger.ok("资源根目录已设", "Resources root set", dirName.trim());
        }
    }

    public static void validateResourcesDir(ApkModule module, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        module.validateResourcesDir();
        if (logger != null) {
            logger.ok("资源目录已校验整理", "Resources dir validated");
        }
    }

    public static void refreshTable(ApkModule module, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        String msg = module.refreshTable();
        if (logger != null) {
            logger.ok("资源表已刷新", "Table refreshed", msg == null ? "ok" : msg);
        }
    }

    public static List<String> listEntryNamesForPath(ApkModule module, String path) {
        ResFile rf = get(module, path);
        List<String> names = new ArrayList<>();
        if (rf == null) return names;
        for (Entry e : rf) {
            if (e == null) continue;
            String n = e.getName();
            if (n != null) names.add(n);
        }
        return names;
    }
}