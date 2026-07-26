package com.apkstoapk.app.core;

import com.apkstoapk.app.util.IoUtils;
import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.archive.ArchiveFile;
import com.reandroid.archive.InputSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * File-based split container helpers (apks/xapk/apkm/zip).
 * No Context/Uri. Not wired into UI / {@link ApksMerger}.
 */
public final class SplitOps {
    private SplitOps() {}

    public static final class ExtractResult {
        public final int count;
        public final List<String> extracted;
        public final List<String> skipped;

        public ExtractResult(int count, List<String> extracted, List<String> skipped) {
            this.count = count;
            this.extracted = extracted;
            this.skipped = skipped;
        }
    }

    /** List .apk entry names inside a split container file. */
    public static List<String> listSplits(File container) throws IOException {
        if (container == null || !container.isFile()) {
            throw new IllegalArgumentException("container missing: " + container);
        }
        ArchiveFile archive = null;
        try {
            archive = new ArchiveFile(container);
            List<String> names = new ArrayList<>();
            for (InputSource source : archive.getInputSources()) {
                String name = source.getName();
                if (name != null && name.toLowerCase(Locale.US).endsWith(".apk")) {
                    names.add(name);
                }
            }
            Collections.sort(names);
            return names;
        } finally {
            if (archive != null) {
                try {
                    archive.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Extract selected .apk entries into outDir (flat file names).
     * @param excludeNames simple or full entry names to skip; null/empty = extract all apks
     */
    public static ExtractResult extractApks(
            File container,
            File outDir,
            List<String> excludeNames,
            SimpleApkLogger logger
    ) throws IOException {
        if (container == null || !container.isFile()) {
            throw new IllegalArgumentException("container missing: " + container);
        }
        if (outDir == null) throw new IllegalArgumentException("outDir is null");
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Cannot create outDir: " + outDir);
        }
        if (logger != null) {
            logger.stage("解包分包容器", "Extract split container");
            logger.bi("容器", "Container", container.getAbsolutePath());
        }

        ArchiveFile archive = null;
        List<String> extracted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            archive = new ArchiveFile(container);
            for (InputSource source : archive.getInputSources()) {
                String name = source.getName();
                if (name == null || !name.toLowerCase(Locale.US).endsWith(".apk")) {
                    if (name != null) skipped.add(name);
                    continue;
                }
                String simple = new File(name).getName();
                if (shouldExclude(simple, name, excludeNames)) {
                    skipped.add(name);
                    if (logger != null) logger.item("跳过", "Skip", name);
                    continue;
                }
                File out = new File(outDir, simple);
                if (!out.getCanonicalPath().startsWith(outDir.getCanonicalPath() + File.separator)
                        && !out.getCanonicalPath().equals(outDir.getCanonicalPath())) {
                    skipped.add(name);
                    if (logger != null) logger.warn("非法路径", "Invalid path", name);
                    continue;
                }
                try (InputStream in = source.openStream()) {
                    IoUtils.copy(in, out);
                }
                extracted.add(simple);
                if (logger != null) logger.item("已提取", "Extracted", simple);
            }
        } finally {
            if (archive != null) {
                try {
                    archive.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (extracted.isEmpty()) {
            throw new IOException("No APK modules extracted from: " + container);
        }
        if (logger != null) {
            logger.ok("提取完成", "Extraction finished", extracted.size() + " module(s)");
        }
        return new ExtractResult(extracted.size(), extracted, skipped);
    }

    private static boolean shouldExclude(String simpleName, String fullName, List<String> excludes) {
        if (excludes == null || excludes.isEmpty()) return false;
        for (String ex : excludes) {
            if (ex == null) continue;
            if (ex.equals(simpleName) || ex.equals(fullName) || fullName.endsWith("/" + ex)) {
                return true;
            }
        }
        return false;
    }
}