package com.apkstoapk.app.mcp;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.List;

class McpPathTools {
    private File workDir = new File("/sdcard");

    JsonObject toolPwd() {
        JsonObject result = new JsonObject();
        result.addProperty("path", this.workDir.getAbsolutePath());
        return result;
    }

    JsonObject toolExists(JsonObject args) {
        File file = resolve(args.has("file_path") ? args.get("file_path").getAsString() : "");
        boolean exists = file.exists();
        boolean isFile = file.isFile();
        boolean isDir = file.isDirectory();
        if (!exists && ShizukuPrivilegedFileOps.isAvailable()) {
            exists = ShizukuPrivilegedFileOps.exists(file);
            if (exists) {
                isFile = ShizukuPrivilegedFileOps.isRegularFile(file);
                isDir = ShizukuPrivilegedFileOps.isDirectory(file);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("exists", exists);
        result.addProperty("is_file", isFile);
        result.addProperty("is_dir", isDir);
        return result;
    }

    JsonObject toolStat(JsonObject args) throws Exception {
        File file = resolve(args.has("file_path") ? args.get("file_path").getAsString() : "");
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());

        boolean exists = file.exists();
        if (exists) {
            result.addProperty("exists", true);
            result.addProperty("is_file", file.isFile());
            result.addProperty("is_dir", file.isDirectory());
            result.addProperty("size", file.length());
            result.addProperty("modified", file.lastModified());
            return result;
        }

        if (ShizukuPrivilegedFileOps.isAvailable()) {
            ShizukuPrivilegedFileOps.FileStat stat = ShizukuPrivilegedFileOps.stat(file);
            result.addProperty("exists", stat.exists);
            if (stat.exists) {
                result.addProperty("is_file", stat.isFile);
                result.addProperty("is_dir", stat.isDir);
                result.addProperty("size", stat.size);
                result.addProperty("modified", stat.modified);
            }
            return result;
        }

        result.addProperty("exists", false);
        return result;
    }

    JsonObject toolLs(JsonObject args) throws Exception {
        File file = resolve(args.has("file_path") ? args.get("file_path").getAsString() : "");

        if (file.exists() && file.isDirectory()) {
            File[] children = file.listFiles();
            JsonObject result = new JsonObject();
            result.addProperty("path", file.getAbsolutePath());
            StringBuilder sb = new StringBuilder();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    sb.append(children[i].isDirectory() ? "[D] " : "[F] ").append(children[i].getName());
                    if (i < children.length - 1) {
                        sb.append('\n');
                    }
                }
            }
            result.addProperty("content", sb.toString());
            return result;
        }

        if (ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.isDirectory(file)) {
            List<ShizukuPrivilegedFileOps.DirEntry> items = ShizukuPrivilegedFileOps.list(file);
            JsonObject result = new JsonObject();
            result.addProperty("path", file.getAbsolutePath());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                ShizukuPrivilegedFileOps.DirEntry item = items.get(i);
                sb.append(item.isDir ? "[D] " : "[F] ").append(item.name);
                if (i < items.size() - 1) {
                    sb.append('\n');
                }
            }
            result.addProperty("content", sb.toString());
            return result;
        }

        throw new Exception("目录不存在:" + file.getAbsolutePath());
    }

    JsonObject toolListAll(JsonObject args) throws Exception {
        return toolLs(args);
    }

    JsonObject toolCd(JsonObject args) throws Exception {
        String dir = args.has("dir") ? args.get("dir").getAsString() : "";
        File target = resolve(dir);
        boolean ok = target.exists() && target.isDirectory();
        if (!ok && ShizukuPrivilegedFileOps.isAvailable()) {
            ok = ShizukuPrivilegedFileOps.isDirectory(target);
        }
        if (!ok) {
            throw new Exception("目录不存在:" + target.getAbsolutePath());
        }
        this.workDir = target.getAbsoluteFile();
        JsonObject result = new JsonObject();
        result.addProperty("path", this.workDir.getAbsolutePath());
        return result;
    }

    JsonObject toolSetRoot(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        File target = resolve(path);
        boolean ok = target.exists() && target.isDirectory();
        if (!ok && ShizukuPrivilegedFileOps.isAvailable()) {
            ok = ShizukuPrivilegedFileOps.isDirectory(target);
        }
        if (!ok) {
            throw new Exception("项目路径不存在:" + target.getAbsolutePath());
        }
        this.workDir = target.getAbsoluteFile();
        JsonObject result = new JsonObject();
        result.addProperty("root", this.workDir.getAbsolutePath());
        return result;
    }

    File getWorkDir() {
        return this.workDir;
    }

    File resolve(String path) {
        if (path == null || path.trim().length() == 0) {
            return this.workDir.getAbsoluteFile();
        }
        File file = new File(path);
        return file.isAbsolute() ? file.getAbsoluteFile() : new File(this.workDir, path).getAbsoluteFile();
    }
}
