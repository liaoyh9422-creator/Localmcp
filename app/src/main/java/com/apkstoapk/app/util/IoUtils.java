package com.apkstoapk.app.util;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public final class IoUtils {
    private IoUtils() {}

    public static InputStream getInputStream(File file) throws IOException {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return Files.newInputStream(file.toPath(), StandardOpenOption.READ);
            } catch (Exception ignored) {
            }
        }
        return new FileInputStream(file);
    }

    public static OutputStream getOutputStream(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            // ignore, write may still fail later with a clearer error
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return Files.newOutputStream(
                        file.toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (Exception ignored) {
            }
        }
        return new FileOutputStream(file);
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int length;
        while ((length = in.read(buffer)) != -1) {
            out.write(buffer, 0, length);
        }
    }

    public static void copy(InputStream in, File dest) throws IOException {
        try (OutputStream out = getOutputStream(dest)) {
            copy(in, out);
        }
    }

    public static void copy(File src, File dest) throws IOException {
        try (InputStream in = getInputStream(src); OutputStream out = getOutputStream(dest)) {
            copy(in, out);
        }
    }

    public static void copy(File src, OutputStream out) throws IOException {
        try (InputStream in = getInputStream(src)) {
            copy(in, out);
        }
    }

    public static void copy(Uri uri, Context context, File dest) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Unable to open uri: " + uri);
            }
            copy(in, dest);
        }
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
