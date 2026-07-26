package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.DexFileInputSource;
import com.reandroid.archive.InputSource;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only inspection of a loaded {@link ApkModule} or APK file.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class ApkInspect {
    private ApkInspect() {}

    public static final class Report {
        public final ManifestOps.Snapshot manifest;
        public final List<String> entries;
        public final List<String> dexFiles;
        public final List<String> nativeLibs;
        public final List<String> abis;
        public final List<String> assets;
        public final boolean hasTableBlock;
        public final int entryCount;

        public Report(
                ManifestOps.Snapshot manifest,
                List<String> entries,
                List<String> dexFiles,
                List<String> nativeLibs,
                List<String> abis,
                List<String> assets,
                boolean hasTableBlock
        ) {
            this.manifest = manifest;
            this.entries = entries;
            this.dexFiles = dexFiles;
            this.nativeLibs = nativeLibs;
            this.abis = abis;
            this.assets = assets;
            this.hasTableBlock = hasTableBlock;
            this.entryCount = entries == null ? 0 : entries.size();
        }
    }

    public static Report inspect(ApkModule module) {
        if (module == null) throw new IllegalArgumentException("module is null");
        ManifestOps.Snapshot snap = module.hasAndroidManifest()
                ? ManifestOps.snapshot(module)
                : null;
        List<String> entries = listEntries(module);
        List<String> dex = listDexNames(module);
        List<String> libs = listNativeLibs(module);
        List<String> abis = listAbis(libs);
        List<String> assets = listEntriesWithPrefix(module, "assets/");
        return new Report(snap, entries, dex, libs, abis, assets, module.hasTableBlock());
    }

    public static Report inspectFile(File apkFile, SimpleApkLogger logger) throws Exception {
        try (ApkModule module = ApkModuleIO.load(apkFile, logger)) {
            return inspect(module);
        }
    }

    public static List<String> listEntries(ApkModule module) {
        if (module == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (InputSource src : module.getInputSources()) {
            if (src == null) continue;
            String name = src.getAlias();
            if (name == null || name.isEmpty()) name = src.getName();
            if (name != null) out.add(name);
        }
        Collections.sort(out);
        return out;
    }

    public static List<String> listEntriesWithPrefix(ApkModule module, String prefix) {
        String p = prefix == null ? "" : prefix.replace('\\', '/');
        List<String> all = listEntries(module);
        if (p.isEmpty()) return all;
        List<String> out = new ArrayList<>();
        for (String e : all) {
            if (e.startsWith(p)) out.add(e);
        }
        return out;
    }

    public static List<String> listDexNames(ApkModule module) {
        if (module == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        List<DexFileInputSource> dex = module.listDexFiles();
        if (dex != null) {
            for (DexFileInputSource d : dex) {
                if (d == null) continue;
                String n = d.getAlias();
                if (n == null || n.isEmpty()) n = d.getName();
                if (n != null) out.add(n);
            }
        }
        Collections.sort(out);
        return out;
    }

    public static List<String> listNativeLibs(ApkModule module) {
        List<String> out = new ArrayList<>();
        for (String e : listEntries(module)) {
            if (e == null) continue;
            String n = e.replace('\\', '/');
            if (n.startsWith("lib/") && n.toLowerCase(Locale.US).endsWith(".so")) {
                out.add(n);
            }
        }
        return out;
    }

    public static List<String> listAbis(ApkModule module) {
        return listAbis(listNativeLibs(module));
    }

    private static List<String> listAbis(List<String> nativeLibs) {
        Set<String> set = new LinkedHashSet<>();
        if (nativeLibs != null) {
            for (String lib : nativeLibs) {
                // lib/<abi>/name.so
                String n = lib.replace('\\', '/');
                if (!n.startsWith("lib/")) continue;
                int second = n.indexOf('/', 4);
                if (second > 4) {
                    set.add(n.substring(4, second));
                }
            }
        }
        return new ArrayList<>(set);
    }
}