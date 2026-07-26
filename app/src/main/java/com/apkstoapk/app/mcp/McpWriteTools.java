package com.apkstoapk.app.mcp;

import android.util.Base64;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

class McpWriteTools {
    private final McpPathTools pathTools;

    McpWriteTools(McpPathTools pathTools) {
        this.pathTools = pathTools;
    }

    JsonObject toolWrite(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        String content = args.has("content") ? args.get("content").getAsString() : "";
        File file = this.pathTools.resolve(path);
        writeFile(file, content, false);
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("bytes", fileLengthSafe(file));
        return result;
    }

    JsonObject toolAppend(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        String content = args.has("content") ? args.get("content").getAsString() : "";
        File file = this.pathTools.resolve(path);
        writeFile(file, content, true);
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("bytes", fileLengthSafe(file));
        return result;
    }

    JsonObject toolMkdir(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        File file = this.pathTools.resolve(path);
        if (!file.exists()) {
            boolean ok = file.mkdirs();
            if (!ok) {
                if (ShizukuPrivilegedFileOps.isAvailable()) {
                    ShizukuPrivilegedFileOps.mkdirs(file);
                } else {
                    throw new Exception("创建目录失败:" + file.getAbsolutePath());
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("exists", file.exists() || (ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.isDirectory(file)));
        return result;
    }

    JsonObject toolTouch(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        File file = this.pathTools.resolve(path);
        ensureParent(file);
        if (!file.exists()) {
            try {
                writeFile(file, "", false);
            } catch (Exception e) {
                if (ShizukuPrivilegedFileOps.isAvailable()) {
                    ShizukuPrivilegedFileOps.writeText(file, "", false);
                } else {
                    throw e;
                }
            }
        }
        boolean modified = file.setLastModified(System.currentTimeMillis());
        if (!modified && ShizukuPrivilegedFileOps.isAvailable()) {
            McpSystemCompat.runShizukuCommandRaw("touch -- '" + file.getAbsolutePath().replace("'", "'\"'\"'") + "'", new File("/sdcard"), 15000);
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("exists", file.exists() || (ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.isRegularFile(file)));
        return result;
    }

    JsonObject toolEmpty(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        File file = this.pathTools.resolve(path);
        writeFile(file, "", false);
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("bytes", fileLengthSafe(file));
        return result;
    }

    JsonObject toolCopy(JsonObject args) throws Exception {
        String source = args.has("source") ? args.get("source").getAsString() : "";
        String destination = args.has("destination") ? args.get("destination").getAsString() : "";
        File from = this.pathTools.resolve(source);
        File to = this.pathTools.resolve(destination);
        if (!from.exists() && !(ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.exists(from))) {
            throw new Exception("源路径不存在:" + from.getAbsolutePath());
        }
        try {
            copyRecursive(from, to);
        } catch (Exception e) {
            if (ShizukuPrivilegedFileOps.isAvailable()) {
                ShizukuPrivilegedFileOps.copyRecursive(from, to);
            } else {
                throw e;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("source", from.getAbsolutePath());
        result.addProperty("path", to.getAbsolutePath());
        return result;
    }

    JsonObject toolRename(JsonObject args) throws Exception {
        String source = args.has("source") ? args.get("source").getAsString() : "";
        String destination = args.has("destination") ? args.get("destination").getAsString() : "";
        File from = this.pathTools.resolve(source);
        File to = this.pathTools.resolve(destination);
        if (!from.exists() && !(ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.exists(from))) {
            throw new Exception("源路径不存在:" + from.getAbsolutePath());
        }
        try {
            ensureParent(to);
            if (!from.renameTo(to)) {
                copyRecursive(from, to);
                deleteRecursive(from);
            }
        } catch (Exception e) {
            if (ShizukuPrivilegedFileOps.isAvailable()) {
                ShizukuPrivilegedFileOps.rename(from, to);
            } else {
                throw e;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("source", from.getAbsolutePath());
        result.addProperty("path", to.getAbsolutePath());
        return result;
    }

    JsonObject toolDelete(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        File file = this.pathTools.resolve(path);
        if (!file.exists() && !(ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.exists(file))) {
            throw new Exception("路径不存在:" + file.getAbsolutePath());
        }
        try {
            deleteRecursive(file);
            if (ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.exists(file)) {
                ShizukuPrivilegedFileOps.deleteRecursive(file);
            }
        } catch (Exception e) {
            if (ShizukuPrivilegedFileOps.isAvailable()) {
                ShizukuPrivilegedFileOps.deleteRecursive(file);
            } else {
                throw e;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("deleted", !(file.exists() || (ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.exists(file))));
        return result;
    }

    JsonObject toolEdit(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        String find = args.has("find") ? args.get("find").getAsString() : "";
        String replace = args.has("replace") ? args.get("replace").getAsString() : "";
        if (find.length() == 0) {
            throw new Exception("缺少 find");
        }
        File file = FileAccessHelper.requireReadableFile(this.pathTools.resolve(path));
        String text = readText(file);
        String newText = text.replace(find, replace);
        writeFile(file, newText, false);
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("changed", !text.equals(newText));
        return result;
    }

    JsonObject toolCodeReplace(JsonObject args) throws Exception {
        // 文件路径：标准 file_path；兼容少数客户端仍传 path
        String path = firstString(args, "file_path", "path");
        // 替换文本：兼容 old_text/old_string/find 与 new_text/new_string/replace
        String oldText = firstString(args, "old_text", "old_string", "oldText", "find", "old");
        String newTextPart = firstString(args, "new_text", "new_string", "newText", "replace", "new");
        boolean all = args.has("all") && args.get("all").getAsBoolean();
        int expectedCount = args.has("expected_count") ? args.get("expected_count").getAsInt() : (all ? -1 : 1);
        if (oldText.length() == 0) {
            throw new Exception("缺少 old_text（也可用 old_string/find）；收到参数: " + argKeys(args));
        }
        if (path.length() == 0) {
            throw new Exception("缺少 file_path；收到参数: " + argKeys(args));
        }
        File file = FileAccessHelper.requireReadableFile(this.pathTools.resolve(path));
        String text = readText(file);
        int count = countOccurrences(text, oldText);
        if (expectedCount >= 0 && count != expectedCount) {
            throw new Exception("匹配次数不符合预期: expected=" + expectedCount + ", actual=" + count);
        }
        if (!all && expectedCount < 0 && count != 1) {
            throw new Exception("匹配次数不唯一: " + count);
        }
        String newText = all ? text.replace(oldText, newTextPart) : replaceFirst(text, oldText, newTextPart);
        writeFile(file, newText, false);
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("changed", !text.equals(newText));
        result.addProperty("replacements", all ? count : (count > 0 ? 1 : 0));
        result.addProperty("matches", count);
        result.addProperty("bytes", fileLengthSafe(file));
        return result;
    }

    JsonObject toolWriteBase64(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        String content = args.has("content") ? args.get("content").getAsString() : "";
        File file = this.pathTools.resolve(path);
        byte[] bytes = Base64.decode(content, Base64.DEFAULT);
        ensureParent(file);
        try {
            FileOutputStream outputStream = new FileOutputStream(file, false);
            try {
                outputStream.write(bytes);
            } finally {
                outputStream.close();
            }
        } catch (Exception e) {
            if (ShizukuPrivilegedFileOps.isAvailable()) {
                ShizukuPrivilegedFileOps.writeBytes(file, bytes, false);
            } else {
                throw FileAccessHelper.normalizeWriteException(file, e);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("bytes", fileLengthSafe(file));
        return result;
    }

    JsonObject toolHistory(JsonObject args) {
        JsonArray items = McpService.getHistoryItems();
        JsonObject result = new JsonObject();
        result.add("items", items);
        return result;
    }

    JsonObject toolClearLog() {
        McpService.clearLog();
        JsonObject result = new JsonObject();
        result.addProperty("message", "日志已清空");
        return result;
    }

    private String firstString(JsonObject args, String... names) {
        if (args == null || names == null) {
            return "";
        }
        for (String name : names) {
            String value = stringArg(args, name, "");
            if (value != null && value.length() > 0) {
                return value;
            }
        }
        return "";
    }

    private String argKeys(JsonObject args) {
        if (args == null || args.entrySet().isEmpty()) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : args.keySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(key);
        }
        return sb.toString();
    }

    private String stringArg(JsonObject args, String name, String defaultValue) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsString() : defaultValue;
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while (true) {
            int next = text.indexOf(needle, index);
            if (next < 0) {
                return count;
            }
            count++;
            index = next + needle.length();
        }
    }

    private String replaceFirst(String text, String oldText, String newText) throws Exception {
        int index = text.indexOf(oldText);
        if (index < 0) {
            throw new Exception("未找到待替换文本");
        }
        return text.substring(0, index) + newText + text.substring(index + oldText.length());
    }

    private void writeFile(File file, String content, boolean append) throws Exception {
        ensureParent(file);
        try {
            FileAccessHelper.requireWritableFileParent(file);
            FileOutputStream outputStream = new FileOutputStream(file, append);
            try {
                outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            } finally {
                outputStream.close();
            }
        } catch (Exception e) {
            if (ShizukuPrivilegedFileOps.isAvailable()) {
                ShizukuPrivilegedFileOps.writeText(file, content, append);
                return;
            }
            throw FileAccessHelper.normalizeWriteException(file, e);
        }
    }

    private void ensureParent(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            boolean ok = parent.mkdirs();
            if (!ok && ShizukuPrivilegedFileOps.isAvailable()) {
                ShizukuPrivilegedFileOps.mkdirs(parent);
            }
        }
    }

    private String readText(File file) throws Exception {
        try {
            java.io.FileInputStream inputStream = new java.io.FileInputStream(file);
            try {
                java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                while (true) {
                    int read = inputStream.read(buffer);
                    if (read < 0) {
                        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
                    }
                    outputStream.write(buffer, 0, read);
                }
            } finally {
                inputStream.close();
            }
        } catch (Exception e) {
            if (ShizukuFileAccess.isAvailable()) {
                return ShizukuFileAccess.readText(file);
            }
            throw FileAccessHelper.normalizeReadException(file, e);
        }
    }

    private void deleteRecursive(File file) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            throw new Exception("删除失败:" + file.getAbsolutePath());
        }
    }

    private void copyRecursive(File from, File to) throws Exception {
        if (from.isDirectory()) {
            if (!to.exists() && !to.mkdirs()) {
                throw new Exception("创建目录失败:" + to.getAbsolutePath());
            }
            File[] children = from.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursive(child, new File(to, child.getName()));
                }
            }
            return;
        }
        ensureParent(to);
        java.io.FileInputStream inputStream = new java.io.FileInputStream(from);
        try {
            FileOutputStream outputStream = new FileOutputStream(to, false);
            try {
                byte[] buffer = new byte[4096];
                while (true) {
                    int read = inputStream.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    outputStream.write(buffer, 0, read);
                }
            } finally {
                outputStream.close();
            }
        } finally {
            inputStream.close();
        }
    }

    private long fileLengthSafe(File file) {
        long local = 0L;
        if (file.exists()) {
            local = file.length();
        }
        if (ShizukuPrivilegedFileOps.isAvailable()) {
            try {
                long privileged = ShizukuPrivilegedFileOps.stat(file).size;
                return Math.max(local, privileged);
            } catch (Exception ignored) {
            }
        }
        return local;
    }
}

