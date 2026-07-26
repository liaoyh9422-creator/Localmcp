package com.apkstoapk.app.core;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.apkstoapk.app.util.IoUtils;
import com.apkstoapk.app.util.SimpleApkLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Resolve a single loadable APK from:
 * - plain .apk
 * - split containers: .apks / .xapk / .apkm / .zip (and zip-like)
 *
 * For containers, extracts the best "base" module for Manifest editing.
 */
public final class InputApkResolver {
    private InputApkResolver() {}

    public static final class Result {
        /** Local apk file ready for {@link ApkModuleIO#load}. */
        public final File apkFile;
        /** True if source was a multi-split container. */
        public final boolean fromContainer;
        /** Selected entry name inside container (if any). */
        public final String selectedEntry;
        /** All apk entry names found in container (empty if plain apk). */
        public final List<String> containerEntries;
        /** Human label for UI. */
        public final String sourceLabel;

        public Result(File apkFile, boolean fromContainer, String selectedEntry,
                      List<String> containerEntries, String sourceLabel) {
            this.apkFile = apkFile;
            this.fromContainer = fromContainer;
            this.selectedEntry = selectedEntry;
            this.containerEntries = containerEntries != null
                    ? containerEntries : Collections.<String>emptyList();
            this.sourceLabel = sourceLabel;
        }
    }

    /**
     * Copy uri to workDir and return a single APK path (extract base from container if needed).
     */
    public static Result resolve(
            Context context,
            Uri uri,
            String displayName,
            File workDir,
            SimpleApkLogger logger
    ) throws Exception {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (uri == null) throw new IllegalArgumentException("uri is null");
        if (workDir == null) throw new IllegalArgumentException("workDir is null");
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new java.io.IOException("Cannot create workDir: " + workDir);
        }

        String label = displayName;
        if (TextUtils.isEmpty(label)) {
            label = uri.getLastPathSegment();
        }
        if (TextUtils.isEmpty(label)) label = "input.bin";

        String lower = label.toLowerCase(Locale.US);
        boolean nameLooksContainer = lower.endsWith(".apks")
                || lower.endsWith(".xapk")
                || lower.endsWith(".apkm")
                || lower.endsWith(".aspk")
                || lower.endsWith(".zip");

        File raw = new File(workDir, "raw_" + System.currentTimeMillis()
                + guessSuffix(label));
        IoUtils.copy(uri, context, raw);

        // Fast path: plain apk by name
        if (lower.endsWith(".apk") && !nameLooksContainer) {
            if (logger != null) {
                logger.bi("输入类型", "Input type", "single APK");
            }
            File apk = new File(workDir, "module.apk");
            IoUtils.copy(raw, apk);
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
            return new Result(apk, false, null, Collections.<String>emptyList(), label);
        }

        // Try as split container
        List<String> entries;
        try {
            entries = SplitOps.listSplits(raw);
        } catch (Exception e) {
            // Not a container — try load as apk anyway
            if (logger != null) {
                logger.warn("非分包容器，按 APK 尝试", "Not split container, try as APK",
                        e.getMessage());
            }
            File apk = new File(workDir, "module.apk");
            IoUtils.copy(raw, apk);
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
            return new Result(apk, false, null, Collections.<String>emptyList(), label);
        }

        if (entries == null || entries.isEmpty()) {
            // zip with no apk entries — still try as apk
            if (logger != null) {
                logger.warn("容器内无 APK 条目", "No APK entries in container", label);
            }
            File apk = new File(workDir, "module.apk");
            IoUtils.copy(raw, apk);
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
            return new Result(apk, false, null, Collections.<String>emptyList(), label);
        }

        if (entries.size() == 1) {
            String only = entries.get(0);
            if (logger != null) {
                logger.bi("输入类型", "Input type", "container (1 apk)");
                logger.item("条目", "Entry", only);
            }
            File extractDir = new File(workDir, "extract");
            SplitOps.extractApks(raw, extractDir, null, logger);
            File apk = new File(extractDir, new File(only).getName());
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
            if (!apk.isFile()) {
                throw new java.io.IOException("Failed to extract: " + only);
            }
            return new Result(apk, true, only, entries, label + " → " + new File(only).getName());
        }

        // Multi-split: pick base module
        String selected = pickBaseEntry(entries);
        if (logger != null) {
            logger.bi("输入类型", "Input type", "split container (" + entries.size() + " apks)");
            for (String e : entries) {
                logger.item("分包", "Split", e);
            }
            logger.ok("选用 base 模块", "Selected base module", selected);
        }

        // Extract only selected (exclude others)
        List<String> exclude = new ArrayList<>();
        for (String e : entries) {
            if (!e.equals(selected) && !new File(e).getName().equals(new File(selected).getName())) {
                exclude.add(e);
                exclude.add(new File(e).getName());
            }
        }
        File extractDir = new File(workDir, "extract");
        SplitOps.ExtractResult er = SplitOps.extractApks(raw, extractDir, exclude, logger);
        //noinspection ResultOfMethodCallIgnored
        raw.delete();

        File apk = new File(extractDir, new File(selected).getName());
        if (!apk.isFile() && er.extracted != null && !er.extracted.isEmpty()) {
            apk = new File(extractDir, er.extracted.get(0));
        }
        if (!apk.isFile()) {
            throw new java.io.IOException("Base APK not extracted: " + selected);
        }
        String uiLabel = label + " → " + apk.getName()
                + "（共 " + entries.size() + " 分包，仅编辑 base）";
        return new Result(apk, true, selected, entries, uiLabel);
    }

    /**
     * Prefer:
     * 1) base.apk / base-master.apk / standalone
     * 2) name without split_config / config.
     * 3) shortest simple name
     */
    static String pickBaseEntry(List<String> entries) {
        if (entries == null || entries.isEmpty()) return null;
        List<String> copy = new ArrayList<>(entries);
        Collections.sort(copy, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Integer.compare(score(a), score(b));
            }
        });
        return copy.get(0);
    }

    /** Lower score = better base candidate. */
    private static int score(String entry) {
        String simple = new File(entry).getName().toLowerCase(Locale.US);
        int s = 100;
        if (simple.equals("base.apk")) s = 0;
        else if (simple.equals("base-master.apk")) s = 1;
        else if (simple.startsWith("base") && simple.endsWith(".apk")) s = 2;
        else if (simple.contains("master")) s = 3;
        else if (simple.contains("split_config") || simple.contains("config.")) s = 50;
        else if (simple.startsWith("split_")) s = 40;
        // prefer shorter non-config names
        s += Math.min(simple.length(), 30);
        return s;
    }

    private static String guessSuffix(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".apk")) return ".apk";
        if (n.endsWith(".apks")) return ".apks";
        if (n.endsWith(".xapk")) return ".xapk";
        if (n.endsWith(".apkm")) return ".apkm";
        if (n.endsWith(".aspk")) return ".aspk";
        if (n.endsWith(".zip")) return ".zip";
        return ".bin";
    }
}
