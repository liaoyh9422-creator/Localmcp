package com.apkstoapk.app.core;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import com.apkstoapk.app.util.IoUtils;
import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.archive.FileInputSource;
import com.reandroid.archive.InputSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Inject selected .so files into APK path:
 *   lib/arm64-v8a/<filename>.so
 */
public final class SoInjector {
    public static final String ABI_DIR = "lib/arm64-v8a/";

    public static class Result {
        public final int injected;
        public final List<String> paths;

        public Result(int injected, List<String> paths) {
            this.injected = injected;
            this.paths = paths;
        }
    }

    private SoInjector() {}

    public static Result injectUris(
            Context context,
            ApkModule module,
            List<Uri> soUris,
            File cacheDir,
            SimpleApkLogger logger
    ) throws Exception {
        if (module == null || soUris == null || soUris.isEmpty()) {
            if (logger != null) {
                logger.bi("未选择 .so 文件", "No .so files selected");
            }
            return new Result(0, new ArrayList<>());
        }
        if (logger != null) {
            logger.stage("注入 .so 到 lib/arm64-v8a", "Inject .so into lib/arm64-v8a");
        }

        if (cacheDir != null && !cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }

        int count = 0;
        List<String> paths = new ArrayList<>();
        for (Uri uri : soUris) {
            if (uri == null) continue;
            String display = queryDisplayName(context, uri);
            String fileName = sanitizeSoFileName(display);
            if (fileName == null) {
                if (logger != null) {
                    logger.warn("跳过无效 .so 名", "Skip invalid .so name", String.valueOf(display));
                }
                continue;
            }

            File local = new File(cacheDir, System.currentTimeMillis() + "_" + fileName);
            IoUtils.copy(uri, context, local);
            String entryPath = ABI_DIR + fileName;

            // Replace if exists
            if (module.containsFile(entryPath)) {
                module.removeInputSource(entryPath);
                if (logger != null) {
                    logger.item("覆盖已有 so", "Overwrite existing so", entryPath);
                }
            }

            FileInputSource source = new FileInputSource(local, entryPath);
            // With extractNativeLibs=true, compressed is OK.
            // Keep default DEFLATED unless module policy changes later.
            module.add(source);
            // Track path in uncompressed list only if needed; with true we leave compressed.

            count++;
            paths.add(entryPath);
            if (logger != null) {
                logger.ok("已放入 so", "Injected so",
                        entryPath + " (" + local.length() + " bytes)");
            }
        }

        if (logger != null) {
            logger.ok("so 注入完成", "so injection finished", count + " file(s)");
        }
        return new Result(count, paths);
    }

    public static Result injectFiles(
            ApkModule module,
            List<File> soFiles,
            SimpleApkLogger logger
    ) {
        if (module == null || soFiles == null || soFiles.isEmpty()) {
            return new Result(0, new ArrayList<>());
        }
        if (logger != null) {
            logger.stage("注入 .so 到 lib/arm64-v8a", "Inject .so into lib/arm64-v8a");
        }
        int count = 0;
        List<String> paths = new ArrayList<>();
        for (File file : soFiles) {
            if (file == null || !file.isFile()) continue;
            String fileName = sanitizeSoFileName(file.getName());
            if (fileName == null) continue;
            String entryPath = ABI_DIR + fileName;
            if (module.containsFile(entryPath)) {
                module.removeInputSource(entryPath);
            }
            InputSource source = new FileInputSource(file, entryPath);
            module.add(source);
            count++;
            paths.add(entryPath);
            if (logger != null) {
                logger.ok("已放入 so", "Injected so", entryPath + " (" + file.length() + " bytes)");
            }
        }
        if (logger != null) {
            logger.ok("so 注入完成", "so injection finished", count + " file(s)");
        }
        return new Result(count, paths);
    }

    private static String sanitizeSoFileName(String name) {
        if (TextUtils.isEmpty(name)) return null;
        String n = name;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.trim();
        if (n.isEmpty()) return null;
        // strip weird query suffixes from providers
        int q = n.indexOf('?');
        if (q > 0) n = n.substring(0, q);
        if (!n.toLowerCase(Locale.US).endsWith(".so")) {
            n = n + ".so";
        }
        // very basic path traversal guard
        if (n.contains("..")) {
            n = n.replace("..", "_");
        }
        return n;
    }

    private static String queryDisplayName(Context context, Uri uri) {
        String result = uri.getLastPathSegment();
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (!TextUtils.isEmpty(n)) result = n;
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
