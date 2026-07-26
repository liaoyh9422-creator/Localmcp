package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.archive.FileInputSource;
import com.reandroid.archive.InputSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Low-level ZIP entry inject / replace / remove on a loaded {@link ApkModule}.
 * Not wired into UI or {@link ApksMerger}.
 */
public final class EntryOps {
    private EntryOps() {}

    public static final class Result {
        public final int count;
        public final List<String> paths;

        public Result(int count, List<String> paths) {
            this.count = count;
            this.paths = paths;
        }
    }

    /** Put or replace one entry. entryPath uses APK zip style, e.g. assets/cfg.json */
    public static String putFile(ApkModule module, File file, String entryPath, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("file missing: " + file);
        }
        String path = normalizeEntryPath(entryPath);
        if (module.containsFile(path)) {
            module.removeInputSource(path);
            if (logger != null) logger.item("覆盖条目", "Overwrite entry", path);
        }
        InputSource source = new FileInputSource(file, path);
        module.add(source);
        if (logger != null) {
            logger.ok("已写入条目", "Entry written", path + " (" + file.length() + " bytes)");
        }
        return path;
    }

    /** Put or replace one entry from in-memory bytes. */
    public static String putBytes(ApkModule module, byte[] data, String entryPath,
                                  SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (data == null) throw new IllegalArgumentException("data is null");
        String path = normalizeEntryPath(entryPath);
        if (module.containsFile(path)) {
            module.removeInputSource(path);
            if (logger != null) logger.item("覆盖条目", "Overwrite entry", path);
        }
        module.add(new ByteInputSource(data, path));
        if (logger != null) {
            logger.ok("已写入条目", "Entry written", path + " (" + data.length + " bytes)");
        }
        return path;
    }

    public static String putStringUtf8(ApkModule module, String text, String entryPath,
                                       SimpleApkLogger logger) {
        byte[] data = text == null ? new byte[0] : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return putBytes(module, data, entryPath, logger);
    }

    /** Remove all entries under a prefix, e.g. "lib/armeabi-v7a/". */
    public static Result removeByPrefix(ApkModule module, String prefix, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        String p = normalizeEntryPath(prefix);
        if (!p.endsWith("/")) p = p + "/";
        List<String> removed = new ArrayList<>();
        for (InputSource src : module.getInputSources()) {
            if (src == null) continue;
            String name = src.getAlias();
            if (name == null || name.isEmpty()) name = src.getName();
            if (name != null && name.startsWith(p)) {
                module.removeInputSource(name);
                removed.add(name);
                if (logger != null) logger.item("已删除", "Removed", name);
            }
        }
        if (logger != null) {
            logger.ok("前缀删除完成", "Prefix remove done", removed.size() + " @ " + p);
        }
        return new Result(removed.size(), removed);
    }

    public static Result putFiles(ApkModule module, List<File> files, List<String> entryPaths,
                                  SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (files == null || entryPaths == null || files.size() != entryPaths.size()) {
            throw new IllegalArgumentException("files/entryPaths size mismatch");
        }
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            paths.add(putFile(module, files.get(i), entryPaths.get(i), logger));
        }
        return new Result(paths.size(), paths);
    }

    public static boolean remove(ApkModule module, String entryPath, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        String path = normalizeEntryPath(entryPath);
        if (!module.containsFile(path)) {
            if (logger != null) logger.bi("条目不存在", "Entry missing", path);
            return false;
        }
        module.removeInputSource(path);
        if (logger != null) logger.ok("已删除条目", "Entry removed", path);
        return true;
    }

    public static boolean contains(ApkModule module, String entryPath) {
        return module != null && module.containsFile(normalizeEntryPath(entryPath));
    }

    /** Extract one zip entry to a local file. */
    public static File extractToFile(
            ApkModule module,
            String entryPath,
            File outFile,
            SimpleApkLogger logger
    ) throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (outFile == null) throw new IllegalArgumentException("outFile is null");
        String path = normalizeEntryPath(entryPath);
        InputSource source = module.getInputSource(path);
        if (source == null) {
            throw new IllegalArgumentException("entry missing: " + path);
        }
        try (java.io.InputStream in = source.openStream()) {
            com.apkstoapk.app.util.IoUtils.copy(in, outFile);
        }
        if (logger != null) {
            logger.ok("已导出条目", "Entry extracted", path + " → " + outFile.getAbsolutePath());
        }
        return outFile;
    }

    public static byte[] readBytes(ApkModule module, String entryPath) throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        String path = normalizeEntryPath(entryPath);
        InputSource source = module.getInputSource(path);
        if (source == null) throw new IllegalArgumentException("entry missing: " + path);
        try (java.io.InputStream in = source.openStream();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            com.apkstoapk.app.util.IoUtils.copy(in, bos);
            return bos.toByteArray();
        }
    }

    public static String readStringUtf8(ApkModule module, String entryPath) throws Exception {
        return new String(readBytes(module, entryPath), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Mark entry compression: stored=true → STORED (no deflate), false → DEFLATED.
     * Useful for already-compressed assets / some native libs.
     */
    public static boolean setStored(ApkModule module, String entryPath, boolean stored,
                                    SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        String path = normalizeEntryPath(entryPath);
        InputSource source = module.getInputSource(path);
        if (source == null) {
            if (logger != null) logger.bi("条目不存在", "Entry missing", path);
            return false;
        }
        source.setUncompressed(stored);
        if (logger != null) {
            logger.ok("压缩方式已设", "Entry method set",
                    path + " → " + (stored ? "STORED" : "DEFLATED"));
        }
        return true;
    }

    /** Remove whole zip directory prefix via module map, e.g. \"assets/old/\". */
    public static void removeDir(ApkModule module, String dirName, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        String p = normalizeEntryPath(dirName);
        module.removeDir(p);
        if (logger != null) {
            logger.ok("目录已删", "Dir removed", p);
        }
    }

    public static int setStoredByPrefix(ApkModule module, String prefix, boolean stored,
                                        SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        String p = normalizeEntryPath(prefix);
        if (!p.endsWith("/")) p = p + "/";
        int n = 0;
        for (InputSource src : module.getInputSources()) {
            if (src == null) continue;
            String name = src.getAlias();
            if (name == null || name.isEmpty()) name = src.getName();
            if (name != null && name.startsWith(p)) {
                src.setUncompressed(stored);
                n++;
            }
        }
        if (logger != null) {
            logger.ok("前缀压缩方式已设", "Prefix method set",
                    n + " @ " + p + " → " + (stored ? "STORED" : "DEFLATED"));
        }
        return n;
    }

    /**
     * Inject .so into lib/&lt;abi&gt;/&lt;fileName&gt;.
     * abi examples: arm64-v8a, armeabi-v7a, x86_64
     */
    public static String injectSo(ApkModule module, File soFile, String abi, SimpleApkLogger logger) {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (soFile == null || !soFile.isFile()) {
            throw new IllegalArgumentException("so file missing: " + soFile);
        }
        String abiDir = normalizeAbi(abi);
        String name = sanitizeSoName(soFile.getName());
        String entryPath = "lib/" + abiDir + "/" + name;
        return putFile(module, soFile, entryPath, logger);
    }

    public static Result injectSos(ApkModule module, List<File> soFiles, String abi,
                                   SimpleApkLogger logger) {
        if (soFiles == null || soFiles.isEmpty()) {
            return new Result(0, new ArrayList<String>());
        }
        List<String> paths = new ArrayList<>();
        for (File f : soFiles) {
            if (f == null || !f.isFile()) continue;
            paths.add(injectSo(module, f, abi, logger));
        }
        return new Result(paths.size(), paths);
    }

    public static String normalizeEntryPath(String entryPath) {
        if (entryPath == null) throw new IllegalArgumentException("entryPath is null");
        String p = entryPath.replace('\\', '/').trim();
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty() || p.contains("..")) {
            throw new IllegalArgumentException("invalid entryPath: " + entryPath);
        }
        return p;
    }

    public static String normalizeAbi(String abi) {
        if (abi == null || abi.trim().isEmpty()) {
            return "arm64-v8a";
        }
        String a = abi.trim().replace('\\', '/');
        if (a.startsWith("lib/")) a = a.substring(4);
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (a.isEmpty() || a.contains("..") || a.contains("/")) {
            throw new IllegalArgumentException("invalid abi: " + abi);
        }
        return a;
    }

    private static String sanitizeSoName(String name) {
        if (name == null) throw new IllegalArgumentException("so name is null");
        String n = name;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("blank so name");
        int q = n.indexOf('?');
        if (q > 0) n = n.substring(0, q);
        if (!n.toLowerCase(Locale.US).endsWith(".so")) n = n + ".so";
        if (n.contains("..")) n = n.replace("..", "_");
        return n;
    }
}