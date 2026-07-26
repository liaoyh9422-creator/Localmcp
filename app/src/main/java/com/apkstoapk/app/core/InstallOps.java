package com.apkstoapk.app.core;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.apkstoapk.app.util.SimpleApkLogger;

import java.io.File;

/**
 * Install intent helpers. Wraps / extends {@link InstallHelper}.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class InstallOps {
    private InstallOps() {}

    public static void install(Context context, File apkFile, SimpleApkLogger logger) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (apkFile == null || !apkFile.isFile()) {
            throw new IllegalArgumentException("apk missing: " + apkFile);
        }
        if (logger != null) {
            logger.stage("安装 APK", "Install APK");
            logger.bi("文件", "File", apkFile.getAbsolutePath());
        }
        InstallHelper.installApk(context, apkFile);
        if (logger != null) {
            logger.ok("已调起安装器", "Installer launched", apkFile.getName());
        }
    }

    public static void install(Context context, Uri uri, SimpleApkLogger logger) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (uri == null) throw new IllegalArgumentException("uri is null");
        if (logger != null) {
            logger.stage("安装 APK", "Install APK");
            logger.bi("uri", "uri", String.valueOf(uri));
        }
        InstallHelper.installApk(context, uri);
        if (logger != null) {
            logger.ok("已调起安装器", "Installer launched", String.valueOf(uri));
        }
    }

    /**
     * Install via FileProvider with explicit authority.
     * Default authority used by {@link InstallHelper} is applicationId + ".fileprovider".
     */
    public static void installWithAuthority(
            Context context,
            File apkFile,
            String fileProviderAuthority,
            SimpleApkLogger logger
    ) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (apkFile == null || !apkFile.isFile()) {
            throw new IllegalArgumentException("apk missing: " + apkFile);
        }
        String authority = fileProviderAuthority;
        if (authority == null || authority.trim().isEmpty()) {
            authority = context.getPackageName() + ".fileprovider";
        }
        Uri uri = FileProvider.getUriForFile(context, authority.trim(), apkFile);
        if (logger != null) {
            logger.stage("安装 APK", "Install APK");
            logger.bi("authority", "authority", authority.trim());
            logger.bi("文件", "File", apkFile.getAbsolutePath());
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        if (logger != null) {
            logger.ok("已调起安装器", "Installer launched", apkFile.getName());
        }
    }

    public static Uri toContentUri(Context context, File apkFile) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (apkFile == null) throw new IllegalArgumentException("apkFile is null");
        return FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                apkFile
        );
    }
}