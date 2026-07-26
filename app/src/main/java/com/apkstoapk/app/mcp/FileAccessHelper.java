package com.apkstoapk.app.mcp;

import java.io.File;
import java.io.FileNotFoundException;

final class FileAccessHelper {
    private FileAccessHelper() {
    }

    static File requireReadableFile(File file) throws Exception {
        boolean exists = file.exists();
        boolean directory = file.isDirectory();
        boolean readable = file.canRead();
        if (!exists && ShizukuFileAccess.isAvailable()) {
            exists = ShizukuFileAccess.exists(file);
            directory = exists && ShizukuFileAccess.isDirectory(file);
            readable = exists && !directory && ShizukuFileAccess.isRegularFile(file);
        }
        if (!exists) {
            throw new Exception("路径不存在:" + file.getAbsolutePath());
        }
        if (directory) {
            throw new Exception("目标是目录，不是文件:" + file.getAbsolutePath());
        }
        if (!readable) {
            throw new Exception("文件存在但当前应用无读取权限，且 Shizuku 也不可用:" + file.getAbsolutePath());
        }
        return file;
    }

    static File requireWritableFileParent(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && parent.exists() && !parent.canWrite()) {
            throw new Exception("目标目录存在但当前应用无写入权限:" + parent.getAbsolutePath());
        }
        return file;
    }

    static Exception normalizeReadException(File file, Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        if (e instanceof FileNotFoundException) {
            if (!file.exists()) {
                return new Exception("路径不存在:" + file.getAbsolutePath(), e);
            }
            if (file.isDirectory()) {
                return new Exception("目标是目录，不是文件:" + file.getAbsolutePath(), e);
            }
            return new Exception("文件存在但读取失败，可能是存储权限不足:" + file.getAbsolutePath() + " | 原因:" + message, e);
        }
        if (e instanceof SecurityException) {
            return new Exception("文件存在但当前应用无读取权限:" + file.getAbsolutePath() + " | 原因:" + message, e);
        }
        return e;
    }

    static Exception normalizeWriteException(File file, Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        if (e instanceof FileNotFoundException || e instanceof SecurityException) {
            return new Exception("写入失败，可能是目标不存在或当前应用无写入权限:" + file.getAbsolutePath() + " | 原因:" + message, e);
        }
        return e;
    }
}
