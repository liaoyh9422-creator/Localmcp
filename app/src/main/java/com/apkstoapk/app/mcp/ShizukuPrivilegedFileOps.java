package com.apkstoapk.app.mcp;

import android.util.Base64;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Shizuku 的统一提权文件操作封装。
 *
 * 说明：
 * 1. 只负责“能力层”，不直接面向 MCP 协议；
 * 2. 供 Path/Read/Write 工具复用；
 * 3. 尽量返回结构化数据，避免把 shell 细节暴露到上层；
 * 4. 当前实现优先覆盖私有目录常见需求：exists/stat/list/read/write/mkdir/delete/rename/copy。
 */
final class ShizukuPrivilegedFileOps {
    private static final int DEFAULT_TIMEOUT_MS = 15000;
    private static final int LARGE_IO_TIMEOUT_MS = 30000;

    private ShizukuPrivilegedFileOps() {
    }

    static boolean isAvailable() {
        return ShizukuFileAccess.isAvailable();
    }

    static boolean exists(File file) {
        return isAvailable() && ShizukuFileAccess.exists(file);
    }

    static boolean isDirectory(File file) {
        return isAvailable() && ShizukuFileAccess.isDirectory(file);
    }

    static boolean isRegularFile(File file) {
        return isAvailable() && ShizukuFileAccess.isRegularFile(file);
    }
    static FileStat stat(File file) throws Exception {
        requireAvailable();
        String path = file.getAbsolutePath();
        String cmd = "if [ ! -e " + q(path) + " ]; then printf '__MCP_MISSING__'; exit 0; fi; "
                + "printf '__MCP_EXISTS__\n'; "
                + "if [ -d " + q(path) + " ]; then "
                + "printf 'dir\n'; "
                + "printf '0\n'; "
                + "mtime=$(stat -c %Y " + q(path) + " 2>/dev/null || stat -f %m " + q(path) + " 2>/dev/null || printf 0); "
                + "printf '%s\\n' \"$mtime\"; "
                + "elif [ -f " + q(path) + " ]; then "
                + "printf 'file\n'; "
                + "size=$(wc -c < " + q(path) + " 2>/dev/null || printf 0); "
                + "mtime=$(stat -c %Y " + q(path) + " 2>/dev/null || stat -f %m " + q(path) + " 2>/dev/null || printf 0); "
                + "printf '%s\\n%s\\n' \"$size\" \"$mtime\"; "
                + "else "
                + "printf 'other\n0\n0\n'; "
                + "fi";
        String out = McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(file), DEFAULT_TIMEOUT_MS).trim();
        if ("__MCP_MISSING__".equals(out)) {
            return new FileStat(path, false, false, false, 0L, 0L);
        }
        String[] lines = out.split("\r?\n");
        boolean isDir = lines.length > 1 && "dir".equals(lines[1]);
        boolean isFile = lines.length > 1 && "file".equals(lines[1]);
        long size = lines.length > 2 ? parseLong(lines[2], 0L) : 0L;
        long modified = lines.length > 3 ? parseLong(lines[3], 0L) * 1000L : 0L;
        return new FileStat(path, true, isFile, isDir, size, modified);
    }

    static List<DirEntry> list(File dir) throws Exception {
        requireAvailable();
        String path = dir.getAbsolutePath();
        String cmd = "if [ ! -d " + q(path) + " ]; then printf '__MCP_NOT_DIR__'; exit 0; fi; "
                + "for p in " + q(path) + "/* " + q(path) + "/.[!.]* " + q(path) + "/..?*; do "
                + "[ -e \"$p\" ] || continue; "
                + "name=$(basename \"$p\"); "
                + "if [ -d \"$p\" ]; then typ=d; elif [ -f \"$p\" ]; then typ=f; else typ=o; fi; "
                + "size=$(wc -c < \"$p\" 2>/dev/null || printf 0); "
                + "mtime=$(stat -c %Y \"$p\" 2>/dev/null || stat -f %m \"$p\" 2>/dev/null || printf 0); "
                + "printf '%s\\t%s\\t%s\\t%s\\n' \"$typ\" \"$name\" \"$size\" \"$mtime\"; "
                + "done";
        String out = McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(dir), DEFAULT_TIMEOUT_MS);
        if ("__MCP_NOT_DIR__".equals(out.trim())) {
            throw new Exception("目录不存在或不可访问:" + path);
        }
        List<DirEntry> items = new ArrayList<>();
        String[] lines = out.split("\\r?\\n");
        for (String line : lines) {
            if (line == null || line.trim().length() == 0) {
                continue;
            }
            String[] parts = line.split("\\t", 4);
            if (parts.length < 4) {
                continue;
            }
            String type = parts[0];
            String name = parts[1];
            long size = parseLong(parts[2], 0L);
            long modified = parseLong(parts[3], 0L) * 1000L;
            boolean isDir = "d".equals(type);
            boolean isFile = "f".equals(type);
            items.add(new DirEntry(name, new File(dir, name).getAbsolutePath(), isFile, isDir, size, modified));
        }
        return items;
    }

    static String readText(File file) throws Exception {
        requireAvailable();
        return ShizukuFileAccess.readText(file);
    }

    static byte[] readBytes(File file) throws Exception {
        requireAvailable();
        return ShizukuFileAccess.readBytes(file);
    }

    static String readHead(File file, int lines) throws Exception {
        requireAvailable();
        int safeLines = Math.max(1, lines);
        String cmd = "head -n " + safeLines + " -- " + q(file.getAbsolutePath());
        return McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(file), DEFAULT_TIMEOUT_MS);
    }

    static String readTail(File file, int lines) throws Exception {
        requireAvailable();
        int safeLines = Math.max(1, lines);
        String cmd = "tail -n " + safeLines + " -- " + q(file.getAbsolutePath());
        return McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(file), DEFAULT_TIMEOUT_MS);
    }

    static List<String> findByName(File root, String keyword) throws Exception {
        requireAvailable();
        String nameExpr;
        if (keyword != null && (keyword.indexOf('*') >= 0 || keyword.indexOf('?') >= 0)) {
            // glob：直接交给 find -name
            nameExpr = escapeGlobForFindName(keyword);
        } else {
            // 子串：*keyword*
            nameExpr = "*" + escapeForFindPattern(keyword) + "*";
        }
        String cmd = "find " + q(root.getAbsolutePath()) + " -name " + q(nameExpr) + " 2>/dev/null || true";
        String out = McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(root), LARGE_IO_TIMEOUT_MS);
        List<String> items = new ArrayList<>();
        for (String line : out.split("\\r?\\n")) {
            if (line != null && line.trim().length() > 0) {
                items.add(line.trim());
            }
        }
        return items;
    }

    static String grep(File root, String query) throws Exception {
        requireAvailable();
        String cmd = "grep -R -n -- " + q(query) + " " + q(root.getAbsolutePath()) + " 2>/dev/null || true";
        return McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(root), LARGE_IO_TIMEOUT_MS);
    }

    static String tree(File root) throws Exception {
        requireAvailable();
        String cmd = "if command -v tree >/dev/null 2>&1; then tree " + q(root.getAbsolutePath())
                + "; else find " + q(root.getAbsolutePath()) + "; fi";
        return McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(root), LARGE_IO_TIMEOUT_MS);
    }

    static void mkdirs(File dir) throws Exception {
        requireAvailable();
        String cmd = "mkdir -p -- " + q(dir.getAbsolutePath());
        McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(dir), DEFAULT_TIMEOUT_MS);
    }

    static void deleteRecursive(File file) throws Exception {
        requireAvailable();
        String cmd = "rm -rf -- " + q(file.getAbsolutePath());
        McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(file), LARGE_IO_TIMEOUT_MS);
    }

    static void rename(File from, File to) throws Exception {
        requireAvailable();
        String cmd = "mkdir -p -- " + q(parentPath(to)) + " && mv -f -- "
                + q(from.getAbsolutePath()) + " " + q(to.getAbsolutePath());
        McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(from), LARGE_IO_TIMEOUT_MS);
    }

    static void copyRecursive(File from, File to) throws Exception {
        requireAvailable();
        String cmd = "mkdir -p -- " + q(parentPath(to)) + " && cp -R -- "
                + q(from.getAbsolutePath()) + " " + q(to.getAbsolutePath());
        McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(from), LARGE_IO_TIMEOUT_MS);
    }

    static void writeText(File file, String content, boolean append) throws Exception {
        requireAvailable();
        String base64 = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        writeBase64Internal(file, base64, append);
    }

    static void writeBytes(File file, byte[] content, boolean append) throws Exception {
        requireAvailable();
        String base64 = Base64.encodeToString(content, Base64.NO_WRAP);
        writeBase64Internal(file, base64, append);
    }

    private static void writeBase64Internal(File file, String base64, boolean append) throws Exception {
        String marker = "__MCP_BASE64__";
        String redirect = append ? ">>" : ">";
        String cmd = "mkdir -p -- " + q(parentPath(file))
                + " && base64 -d " + redirect + " " + q(file.getAbsolutePath())
                + " <<'" + marker + "'\n"
                + base64 + "\n"
                + marker;
        McpSystemCompat.runShizukuCommandRaw(cmd, safeCwd(file), LARGE_IO_TIMEOUT_MS);
    }

    private static void requireAvailable() throws Exception {
        if (!isAvailable()) {
            throw new Exception("Shizuku 不可用或未授权");
        }
    }

    private static File safeCwd(File file) {
        File base = file == null ? null : (file.isDirectory() ? file : file.getParentFile());
        if (base != null && base.exists() && base.isDirectory()) {
            return base;
        }
        return new File("/sdcard");
    }

    private static String parentPath(File file) {
        File parent = file.getParentFile();
        return parent == null ? "/" : parent.getAbsolutePath();
    }

    private static String q(String text) {
        return "'" + (text == null ? "" : text.replace("'", "'\"'\"'")) + "'";
    }

    /** find -name 的 glob：保留 * ?，仅处理引号。 */
    private static String escapeGlobForFindName(String glob) {
        if (glob == null) {
            return "*";
        }
        return glob.replace("'", "'\"'\"'");
    }

    private static String escapeForFindPattern(String text) {
        if (text == null || text.length() == 0) {
            return "";
        }
        return text
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("*", "\\*")
                .replace("?", "\\?")
                .replace("'", "'");
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    static final class FileStat {
        final String path;
        final boolean exists;
        final boolean isFile;
        final boolean isDir;
        final long size;
        final long modified;

        FileStat(String path, boolean exists, boolean isFile, boolean isDir, long size, long modified) {
            this.path = path;
            this.exists = exists;
            this.isFile = isFile;
            this.isDir = isDir;
            this.size = size;
            this.modified = modified;
        }
    }

    static final class DirEntry {
        final String name;
        final String path;
        final boolean isFile;
        final boolean isDir;
        final long size;
        final long modified;

        DirEntry(String name, String path, boolean isFile, boolean isDir, long size, long modified) {
            this.name = name;
            this.path = path;
            this.isFile = isFile;
            this.isDir = isDir;
            this.size = size;
            this.modified = modified;
        }
    }
}
