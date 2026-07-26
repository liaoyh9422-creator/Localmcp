package com.apkstoapk.app.mcp;

import android.util.Base64;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

class McpReadTools {
    private final McpPathTools pathTools;

    McpReadTools(McpPathTools pathTools) {
        this.pathTools = pathTools;
    }

    JsonObject toolRead(JsonObject args) throws Exception {
        File file = requireTextFile(args);
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("content", readText(file));
        return result;
    }

    JsonObject toolHead(JsonObject args) throws Exception {
        File file = requireTextFile(args);
        int lines = readLineCount(args, 20);
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("content", readHead(file, lines));
        return result;
    }

    JsonObject toolTail(JsonObject args) throws Exception {
        File file = requireTextFile(args);
        int lines = readLineCount(args, 20);
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("content", readTail(file, lines));
        return result;
    }

    JsonObject toolReadLines(JsonObject args) throws Exception {
        File file = requireTextFile(args);
        String text = readText(file);
        String[] split = text.split("\\n", -1);
        int start = args.has("start") ? args.get("start").getAsInt() : 1;
        int end = args.has("end") ? args.get("end").getAsInt() : start;
        if (start < 1) {
            start = 1;
        }
        if (end < start) {
            throw new Exception("end 不能小于 start");
        }
        if (start > split.length) {
            throw new Exception("start 超出范围，文件共 " + split.length + " 行");
        }
        if (end > split.length) {
            end = split.length;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            sb.append(i).append(": ").append(split[i - 1]);
            if (i < end) {
                sb.append('\n');
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("content", sb.toString());
        return result;
    }

    JsonObject toolBatchRead(JsonObject args) throws Exception {
        if (!args.has("files") || !args.get("files").isJsonArray()) {
            throw new Exception("缺少 files");
        }
        StringBuilder sb = new StringBuilder();
        for (com.google.gson.JsonElement item : args.getAsJsonArray("files")) {
            String path = item.getAsString();
            File file = FileAccessHelper.requireReadableFile(this.pathTools.resolve(path));
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("===== ").append(file.getAbsolutePath()).append(" =====\n");
            sb.append(readText(file));
        }
        JsonObject result = new JsonObject();
        result.addProperty("content", sb.toString());
        return result;
    }

    JsonObject toolReadBase64(JsonObject args) throws Exception {
        File file = requireTextOrBinaryFile(args);
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("content", Base64.encodeToString(readBytes(file), Base64.NO_WRAP));
        return result;
    }

    JsonObject toolFind(JsonObject args) throws Exception {
        String rootPath = args.has("file_path") ? args.get("file_path").getAsString() : "";
        String name = args.has("name") ? args.get("name").getAsString() : "";
        if (name.length() == 0) {
            throw new Exception("缺少 name");
        }
        File root = this.pathTools.resolve(rootPath);
        JsonArray hits = new JsonArray();
        if (root.exists()) {
            findWalk(root, name, hits);
        } else if (ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.exists(root)) {
            List<String> items = ShizukuPrivilegedFileOps.findByName(root, name);
            for (String item : items) {
                hits.add(item);
            }
        } else {
            throw new Exception("路径不存在:" + root.getAbsolutePath());
        }
        JsonObject result = new JsonObject();
        result.add("items", hits);
        return result;
    }

    JsonObject toolGrep(JsonObject args) throws Exception {
        String rootPath = args.has("file_path") ? args.get("file_path").getAsString() : "";
        String query = args.has("query") ? args.get("query").getAsString() : "";
        if (query.length() == 0) {
            throw new Exception("缺少 query");
        }
        File root = this.pathTools.resolve(rootPath);
        StringBuilder sb = new StringBuilder();
        if (root.exists()) {
            grepWalk(root, query, sb);
        } else if (ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.exists(root)) {
            sb.append(ShizukuPrivilegedFileOps.grep(root, query));
        } else {
            throw new Exception("路径不存在:" + root.getAbsolutePath());
        }
        JsonObject result = new JsonObject();
        result.addProperty("content", sb.toString());
        return result;
    }

    JsonObject toolTree(JsonObject args) throws Exception {
        File file = this.pathTools.resolve(args.has("file_path") ? args.get("file_path").getAsString() : "");
        JsonObject result = new JsonObject();
        result.addProperty("path", file.getAbsolutePath());

        if (file.exists()) {
            StringBuilder sb = new StringBuilder();
            String name = file.getName().length() == 0 ? file.getAbsolutePath() : file.getName();
            sb.append(name);
            if (file.isDirectory()) {
                sb.append('/');
            }
            sb.append('\n');
            treeWalk(file, "", sb);
            result.addProperty("content", sb.toString());
            return result;
        }

        if (ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.exists(file)) {
            result.addProperty("content", ShizukuPrivilegedFileOps.tree(file));
            return result;
        }

        throw new Exception("路径不存在:" + file.getAbsolutePath());
    }

    private File requireTextFile(JsonObject args) throws Exception {
        File file = this.pathTools.resolve(args.has("file_path") ? args.get("file_path").getAsString() : "");
        return FileAccessHelper.requireReadableFile(file);
    }

    private File requireTextOrBinaryFile(JsonObject args) throws Exception {
        File file = this.pathTools.resolve(args.has("file_path") ? args.get("file_path").getAsString() : "");
        return FileAccessHelper.requireReadableFile(file);
    }

    private int readLineCount(JsonObject args, int fallback) {
        if (!args.has("lines")) {
            return fallback;
        }
        try {
            int lines = args.get("lines").getAsInt();
            return lines < 1 ? fallback : lines;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String readHead(File file, int lines) throws Exception {
        if (file.exists()) {
            String[] split = readText(file).split("\\n", -1);
            int end = Math.min(lines, split.length);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < end; i++) {
                sb.append(split[i]);
                if (i < end - 1) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }
        if (ShizukuPrivilegedFileOps.isAvailable()) {
            return ShizukuPrivilegedFileOps.readHead(file, lines);
        }
        throw new Exception("路径不存在:" + file.getAbsolutePath());
    }

    private String readTail(File file, int lines) throws Exception {
        if (file.exists()) {
            String[] split = readText(file).split("\\n", -1);
            int start = Math.max(0, split.length - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < split.length; i++) {
                sb.append(split[i]);
                if (i < split.length - 1) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }
        if (ShizukuPrivilegedFileOps.isAvailable()) {
            return ShizukuPrivilegedFileOps.readTail(file, lines);
        }
        throw new Exception("路径不存在:" + file.getAbsolutePath());
    }

    String readTextPublic(File file) throws Exception {
        return readText(file);
    }

    private String readText(File file) throws Exception {
        try {
            FileInputStream inputStream = new FileInputStream(file);
            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
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

    private byte[] readBytes(File file) throws Exception {
        try {
            FileInputStream inputStream = new FileInputStream(file);
            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                while (true) {
                    int read = inputStream.read(buffer);
                    if (read < 0) {
                        return outputStream.toByteArray();
                    }
                    outputStream.write(buffer, 0, read);
                }
            } finally {
                inputStream.close();
            }
        } catch (Exception e) {
            if (ShizukuFileAccess.isAvailable()) {
                return ShizukuFileAccess.readBytes(file);
            }
            throw FileAccessHelper.normalizeReadException(file, e);
        }
    }

    private void treeWalk(File file, String prefix, StringBuilder sb) {
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            boolean last = i == children.length - 1;
            sb.append(prefix).append(last ? "└── " : "├── ").append(child.getName());
            if (child.isDirectory()) {
                sb.append('/').append('\n');
                treeWalk(child, prefix + (last ? "    " : "│   "), sb);
            } else {
                sb.append('\n');
            }
        }
    }

    private void findWalk(File file, String name, JsonArray hits) {
        if (nameMatches(file.getName(), name)) {
            hits.add(file.getAbsolutePath());
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    findWalk(child, name, hits);
                }
            }
        }
    }

    /**
     * 文件名匹配：
     * - 含 * 或 ? 时按 glob（* = 任意长度，? = 单字符）
     * - 否则保持子串 contains（兼容 name=".java" / "McpServer"）
     */
    private boolean nameMatches(String fileName, String pattern) {
        if (pattern == null || pattern.length() == 0) {
            return false;
        }
        if (fileName == null) {
            return false;
        }
        if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0) {
            return fileName.matches(globToRegex(pattern));
        }
        return fileName.contains(pattern);
    }

    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        sb.append('^');
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append('.');
                    break;
                case '\\':
                case '.':
                case '[':
                case ']':
                case '{':
                case '}':
                case '(':
                case ')':
                case '+':
                case '^':
                case '$':
                case '|':
                    sb.append('\\').append(c);
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        sb.append('$');
        return sb.toString();
    }

    private void grepWalk(File file, String query, StringBuilder sb) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    grepWalk(child, query, sb);
                }
            }
            return;
        }
        String[] lines = readText(file).split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(query)) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(file.getAbsolutePath()).append(':').append(i + 1).append(": ").append(lines[i]);
            }
        }
    }
}
