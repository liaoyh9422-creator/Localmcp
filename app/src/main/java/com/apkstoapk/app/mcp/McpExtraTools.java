package com.apkstoapk.app.mcp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Base64;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

class McpExtraTools {
    private static final String ADB_BIN = "/opt/android-sdk/platform-tools/adb";
    private final McpPathTools pathTools;
    private final McpReadTools readTools;
    private final McpWriteTools writeTools;
    private final McpShellTools shellTools;
    private final McpServer server;
    private final Context context;

    McpExtraTools(McpServer server, Context context, McpPathTools pathTools, McpReadTools readTools, McpWriteTools writeTools, McpShellTools shellTools) {
        this.server = server;
        this.context = context;
        this.pathTools = pathTools;
        this.readTools = readTools;
        this.writeTools = writeTools;
        this.shellTools = shellTools;
    }

    JsonObject toolBatchOps(JsonObject args) throws Exception {
        JsonArray items = args.has("items") && args.get("items").isJsonArray()
                ? args.getAsJsonArray("items")
                : (args.has("ops") && args.get("ops").isJsonArray() ? args.getAsJsonArray("ops") : null);
        if (items == null) {
            throw new Exception("缺少 items");
        }
        boolean stopOnError = args.has("stop_on_error") && args.get("stop_on_error").getAsBoolean();
        JsonArray results = new JsonArray();
        int ok = 0;
        int fail = 0;
        for (int i = 0; i < items.size(); i++) {
            JsonObject row = new JsonObject();
            JsonElement item = items.get(i);
            if (!item.isJsonObject()) {
                row.addProperty("index", i);
                row.addProperty("ok", false);
                row.addProperty("error", "item 不是对象");
                results.add(row);
                fail++;
                if (stopOnError) break;
                continue;
            }
            JsonObject obj = item.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            JsonObject arguments = obj.has("arguments") && obj.get("arguments").isJsonObject()
                    ? obj.getAsJsonObject("arguments")
                    : (obj.has("args") && obj.get("args").isJsonObject() ? obj.getAsJsonObject("args") : new JsonObject());
            row.addProperty("index", i);
            row.addProperty("name", name);
            try {
                JsonObject result = this.server.callToolObject(name, arguments);
                row.addProperty("ok", true);
                row.add("result", result);
                ok++;
            } catch (Exception e) {
                row.addProperty("ok", false);
                row.addProperty("error", e.getMessage());
                fail++;
                if (stopOnError) {
                    results.add(row);
                    break;
                }
            }
            results.add(row);
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", ok);
        result.addProperty("fail", fail);
        result.add("items", results);
        return result;
    }

    JsonObject toolFileText(JsonObject args) throws Exception {
        String action = normalizeSimpleAction(args, "convert");
        if ("convert".equals(action) || "text".equals(action)) {
            return toolTextConvert(args);
        }
        if ("json".equals(action)) {
            return toolJsonFormat(args);
        }
        if ("compare".equals(action) || "diff".equals(action)) {
            return toolCompareFiles(args);
        }
        throw new Exception("未知 file_text action: " + action + "（可用 convert/json/compare）");
    }

    JsonObject toolShellUnified(JsonObject args) throws Exception {
        String action = normalizeSimpleAction(args, "local");
        if ("local".equals(action) || "app".equals(action) || "sh".equals(action)) {
            return this.shellTools.toolShell(args);
        }
        if ("shizuku".equals(action)) {
            return toolShizukuShell(args);
        }
        throw new Exception("未知 shell action: " + action + "（可用 local/shizuku）");
    }

    private String normalizeSimpleAction(JsonObject args, String defaultAction) {
        String raw = "";
        if (args != null) {
            if (args.has("action") && !args.get("action").isJsonNull()) {
                raw = args.get("action").getAsString();
            } else if (args.has("mode") && !args.get("mode").isJsonNull()) {
                raw = args.get("mode").getAsString();
            }
        }
        if (raw == null) raw = "";
        raw = raw.trim().toLowerCase(java.util.Locale.US);
        return raw.length() == 0 ? defaultAction : raw;
    }

    JsonObject toolCompareFiles(JsonObject args) throws Exception {
        File left = resolveRequiredFile(args, "left");
        File right = resolveRequiredFile(args, "right");
        String leftText = this.readTools.readTextPublic(left);
        String rightText = this.readTools.readTextPublic(right);
        JsonObject result = new JsonObject();
        result.addProperty("left", left.getAbsolutePath());
        result.addProperty("right", right.getAbsolutePath());
        result.addProperty("same", leftText.equals(rightText));
        result.addProperty("left_sha256", sha256(leftText));
        result.addProperty("right_sha256", sha256(rightText));
        if (!leftText.equals(rightText)) {
            result.addProperty("content", diffPreview(leftText, rightText));
        }
        return result;
    }

    JsonObject toolTextConvert(JsonObject args) throws Exception {
        String text = inputText(args);
        String action = args.has("action") ? args.get("action").getAsString()
                : (args.has("mode") ? args.get("mode").getAsString() : "trim_lines");
        String output;
        if ("upper".equals(action)) {
            output = text.toUpperCase();
        } else if ("lower".equals(action)) {
            output = text.toLowerCase();
        } else if ("remove_empty_lines".equals(action)) {
            output = removeEmptyLines(text);
        } else if ("normalize_newlines".equals(action)) {
            output = text.replace("\r\n", "\n").replace('\r', '\n');
        } else {
            output = trimLines(text);
        }
        return outputOrFile(args, output, "text_convert");
    }

    JsonObject toolJsonFormat(JsonObject args) throws Exception {
        String text = inputText(args);
        String mode = args.has("mode") ? args.get("mode").getAsString()
                : (args.has("compact") && args.get("compact").getAsBoolean() ? "minify" : "pretty");
        JsonElement element = JsonParser.parseString(text);
        String output;
        if ("validate".equals(mode)) {
            output = "JSON 有效";
        } else if ("minify".equals(mode) || "compact".equals(mode)) {
            output = element.toString();
        } else {
            output = this.server.prettyJson(element);
        }
        return outputOrFile(args, output, "json_format");
    }

    JsonObject toolBatteryStatus() {
        return McpSystemCompat.batteryStatus(this.context);
    }

    JsonObject toolBatteryFix(JsonObject args) throws Exception {
        return McpSystemCompat.batteryFix(this.context, args);
    }

    JsonObject toolShizukuStatus() {
        return McpSystemCompat.shizukuStatus();
    }

    JsonObject toolShizukuShell(JsonObject args) throws Exception {
        return McpSystemCompat.shizukuShell(args);
    }

    JsonObject toolInstallApk(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        if (path.length() == 0) {
            throw new Exception("缺少 file_path");
        }
        File apk = this.pathTools.resolve(path);
        boolean exists = apk.exists() || (ShizukuPrivilegedFileOps.isAvailable() && ShizukuPrivilegedFileOps.exists(apk));
        if (!exists) {
            throw new Exception("APK 不存在:" + apk.getAbsolutePath());
        }
        boolean replace = !args.has("replace") || args.get("replace").getAsBoolean();
        boolean grantAll = args.has("grant_all") && args.get("grant_all").getAsBoolean();
        StringBuilder cmd = new StringBuilder("pm install ");
        if (replace) cmd.append("-r ");
        if (grantAll) cmd.append("-g ");
        cmd.append("'").append(apk.getAbsolutePath().replace("'", "'\"'\"'")).append("'");

        JsonObject shellArgs = new JsonObject();
        shellArgs.addProperty("cmd", cmd.toString());
        shellArgs.addProperty("cwd", "/sdcard");
        shellArgs.addProperty("timeout", 120000);
        JsonObject shellResult = McpSystemCompat.shizukuShell(shellArgs);

        String content = shellResult.has("content") ? shellResult.get("content").getAsString() : "";
        boolean success = content.contains("exit_code:0") && content.contains("Success");

        JsonObject result = new JsonObject();
        result.addProperty("path", apk.getAbsolutePath());
        result.addProperty("replace", replace);
        result.addProperty("grant_all", grantAll);
        result.addProperty("success", success);
        result.addProperty("content", content);
        return result;
    }

    JsonObject toolHttp(JsonObject args) throws Exception {
        String action = normalizeHttpAction(args);
        String url = args.has("url") ? args.get("url").getAsString() : "";
        if (url.length() == 0) {
            throw new Exception("缺少 url");
        }

        if ("head".equals(action)) {
            return requestUrl("HEAD", url, args, (byte[]) null);
        }
        if ("options".equals(action)) {
            return requestUrl("OPTIONS", url, args, (byte[]) null);
        }
        if ("patch".equals(action)) {
            return requestUrl("PATCH", url, args, requestBodyBytes(args));
        }
        if ("get".equals(action)) {
            return requestUrl("GET", url, args, (byte[]) null);
        }
        if ("post".equals(action)) {
            return requestUrl("POST", url, args, requestBodyBytes(args));
        }
        if ("put".equals(action)) {
            return requestUrl("PUT", url, args, requestBodyBytes(args));
        }
        if ("delete".equals(action)) {
            // DELETE may carry optional body
            byte[] body = null;
            if (args.has("body") || args.has("body_file") || args.has("body_base64")) {
                body = requestBodyBytes(args);
            }
            return requestUrl("DELETE", url, args, body);
        }
        if ("json".equals(action)) {
            String method = stringArg(args, "http_method", "GET").trim().toUpperCase(java.util.Locale.US);
            if (method.length() == 0) method = "GET";
            byte[] body = null;
            if (!"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method)) {
                body = requestBodyBytes(args);
            }
            JsonObject response = requestUrl(method, url, args, body);
            String responseBody = response.has("body") ? response.get("body").getAsString() : "";
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(responseBody);
            } catch (Exception e) {
                throw new Exception("响应不是合法 JSON: " + e.getMessage());
            }
            JsonObject result = new JsonObject();
            result.addProperty("url", response.get("url").getAsString());
            result.addProperty("status", response.get("status").getAsInt());
            result.addProperty("method", response.has("method") ? response.get("method").getAsString() : method);
            result.add("json", parsed);
            if (response.has("headers")) {
                result.add("headers", response.get("headers"));
            }
            return result;
        }
        if ("download_text".equals(action)) {
            String output = stringArg(args, "output", "");
            if (output.length() == 0) {
                throw new Exception("缺少 output");
            }
            JsonObject response = requestUrl("GET", url, args, (byte[]) null);
            JsonObject writeArgs = new JsonObject();
            writeArgs.addProperty("file_path", output);
            writeArgs.addProperty("content", response.get("body").getAsString());
            JsonObject saved = this.writeTools.toolWrite(writeArgs);
            JsonObject result = new JsonObject();
            result.addProperty("url", url);
            result.addProperty("status", response.get("status").getAsInt());
            result.addProperty("path", saved.get("path").getAsString());
            result.addProperty("bytes", saved.get("bytes").getAsLong());
            return result;
        }
        if ("download_file".equals(action) || "download".equals(action)) {
            String output = stringArg(args, "output", "");
            if (output.length() == 0) {
                throw new Exception("缺少 output");
            }
            HttpURLConnection connection = openConnection("GET", url, args);
            int status = connection.getResponseCode();
            if (status >= 400) {
                String errorBody = readConnectionBody(connection, status);
                throw new Exception("下载失败 HTTP " + status + " : " + errorBody);
            }
            File target = this.pathTools.resolve(output);
            ensureParent(target);
            InputStream inputStream = connection.getInputStream();
            long total = 0L;
            try {
                FileOutputStream out = new FileOutputStream(target, false);
                try {
                    byte[] buffer = new byte[8192];
                    while (true) {
                        int read = inputStream.read(buffer);
                        if (read < 0) break;
                        out.write(buffer, 0, read);
                        total += read;
                    }
                } finally {
                    out.close();
                }
            } finally {
                inputStream.close();
            }
            JsonObject result = new JsonObject();
            result.addProperty("url", url);
            result.addProperty("status", status);
            result.addProperty("path", target.getAbsolutePath());
            result.addProperty("bytes", total);
            result.addProperty("content_type", connection.getContentType() == null ? "" : connection.getContentType());
            return result;
        }
        if ("upload".equals(action)) {
            String filePath = stringArg(args, "file_path", stringArg(args, "upload_file", ""));
            if (filePath.length() == 0) {
                throw new Exception("缺少 file_path（待上传本地文件）");
            }
            File file = FileAccessHelper.requireReadableFile(this.pathTools.resolve(filePath));
            String field = stringArg(args, "field", "file");
            String filename = stringArg(args, "filename", file.getName());
            String method = stringArg(args, "http_method", "POST").trim().toUpperCase(java.util.Locale.US);
            if (method.length() == 0) method = "POST";
            return multipartUpload(method, url, args, file, field, filename);
        }

        throw new Exception("未知 http action: " + action
                + "（可用 get/post/put/delete/patch/head/options/json/download_text/download_file/upload）");
    }

    private String normalizeHttpAction(JsonObject args) {
        String raw = "";
        if (args != null) {
            if (args.has("action") && !args.get("action").isJsonNull()) {
                raw = args.get("action").getAsString();
            } else if (args.has("method") && !args.get("method").isJsonNull()) {
                raw = args.get("method").getAsString();
            }
        }
        if (raw == null) raw = "";
        raw = raw.trim().toLowerCase(java.util.Locale.US);
        if (raw.length() == 0) return "get";
        if ("download".equals(raw)) return "download_file";
        return raw;
    }

    JsonObject toolImage(JsonObject args) throws Exception {
        String action = normalizeImageAction(args);
        if ("info".equals(action)) {
            File file = resolveRequiredFile(args, "file_path");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw new Exception("无法识别图片:" + file.getAbsolutePath());
            }
            JsonObject result = new JsonObject();
            result.addProperty("path", file.getAbsolutePath());
            result.addProperty("width", options.outWidth);
            result.addProperty("height", options.outHeight);
            result.addProperty("mime_type", options.outMimeType == null ? "" : options.outMimeType);
            result.addProperty("bytes", file.length());
            return result;
        }
        if ("resize".equals(action)) {
            File source = resolveRequiredFile(args, "file_path");
            String outputPath = requireOutput(args);
            int width = args.has("width") ? args.get("width").getAsInt() : 0;
            int height = args.has("height") ? args.get("height").getAsInt() : 0;
            boolean keepAspect = !args.has("keep_aspect") || args.get("keep_aspect").getAsBoolean();
            Bitmap bitmap = decodeBitmap(source);
            int srcW = bitmap.getWidth();
            int srcH = bitmap.getHeight();
            if (width <= 0 && height <= 0) {
                bitmap.recycle();
                throw new Exception("width 或 height 至少指定一个且大于 0");
            }
            if (keepAspect) {
                if (width <= 0) {
                    width = Math.max(1, Math.round(srcW * (height / (float) srcH)));
                } else if (height <= 0) {
                    height = Math.max(1, Math.round(srcH * (width / (float) srcW)));
                }
            } else {
                if (width <= 0) width = srcW;
                if (height <= 0) height = srcH;
            }
            int quality = imageQuality(args, 95);
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            File output = writeBitmap(scaled, outputPath, quality);
            if (scaled != bitmap) scaled.recycle();
            bitmap.recycle();
            JsonObject result = new JsonObject();
            result.addProperty("path", output.getAbsolutePath());
            result.addProperty("width", width);
            result.addProperty("height", height);
            result.addProperty("bytes", output.length());
            return result;
        }
        if ("convert".equals(action)) {
            File source = resolveRequiredFile(args, "file_path");
            String outputPath = requireOutput(args);
            int quality = imageQuality(args, 95);
            Bitmap bitmap = decodeBitmap(source);
            File output = writeBitmap(bitmap, outputPath, quality);
            bitmap.recycle();
            JsonObject result = new JsonObject();
            result.addProperty("path", output.getAbsolutePath());
            result.addProperty("bytes", output.length());
            return result;
        }
        if ("crop".equals(action)) {
            File source = resolveRequiredFile(args, "file_path");
            String outputPath = requireOutput(args);
            int x = args.has("x") ? args.get("x").getAsInt() : 0;
            int y = args.has("y") ? args.get("y").getAsInt() : 0;
            int width = args.has("width") ? args.get("width").getAsInt() : 0;
            int height = args.has("height") ? args.get("height").getAsInt() : 0;
            if (width <= 0 || height <= 0) throw new Exception("width 和 height 必须大于 0");
            Bitmap bitmap = decodeBitmap(source);
            if (x < 0 || y < 0 || x + width > bitmap.getWidth() || y + height > bitmap.getHeight()) {
                bitmap.recycle();
                throw new Exception("裁剪区域超出图片范围");
            }
            int quality = imageQuality(args, 95);
            Bitmap cropped = Bitmap.createBitmap(bitmap, x, y, width, height);
            File output = writeBitmap(cropped, outputPath, quality);
            cropped.recycle();
            bitmap.recycle();
            JsonObject result = new JsonObject();
            result.addProperty("path", output.getAbsolutePath());
            result.addProperty("width", width);
            result.addProperty("height", height);
            result.addProperty("bytes", output.length());
            return result;
        }
        if ("rotate".equals(action)) {
            File source = resolveRequiredFile(args, "file_path");
            String outputPath = requireOutput(args);
            float degrees = args.has("degrees") ? args.get("degrees").getAsFloat() : 0f;
            int quality = imageQuality(args, 95);
            Bitmap bitmap = decodeBitmap(source);
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            File output = writeBitmap(rotated, outputPath, quality);
            if (rotated != bitmap) rotated.recycle();
            bitmap.recycle();
            JsonObject result = new JsonObject();
            result.addProperty("path", output.getAbsolutePath());
            result.addProperty("bytes", output.length());
            result.addProperty("degrees", degrees);
            return result;
        }
        if ("flip".equals(action)) {
            File source = resolveRequiredFile(args, "file_path");
            String outputPath = requireOutput(args);
            String axis = stringArg(args, "axis", "horizontal").trim().toLowerCase(java.util.Locale.US);
            boolean horizontal = !"vertical".equals(axis) && !"v".equals(axis);
            int quality = imageQuality(args, 95);
            Bitmap bitmap = decodeBitmap(source);
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.preScale(horizontal ? -1f : 1f, horizontal ? 1f : -1f);
            Bitmap flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            File output = writeBitmap(flipped, outputPath, quality);
            if (flipped != bitmap) flipped.recycle();
            bitmap.recycle();
            JsonObject result = new JsonObject();
            result.addProperty("path", output.getAbsolutePath());
            result.addProperty("bytes", output.length());
            result.addProperty("axis", horizontal ? "horizontal" : "vertical");
            return result;
        }
        if ("grayscale".equals(action) || "grey".equals(action)) {
            File source = resolveRequiredFile(args, "file_path");
            String outputPath = requireOutput(args);
            int quality = imageQuality(args, 95);
            Bitmap bitmap = decodeBitmap(source);
            Bitmap gray = toGrayscale(bitmap);
            File output = writeBitmap(gray, outputPath, quality);
            if (gray != bitmap) gray.recycle();
            bitmap.recycle();
            JsonObject result = new JsonObject();
            result.addProperty("path", output.getAbsolutePath());
            result.addProperty("bytes", output.length());
            return result;
        }
        if ("compress".equals(action)) {
            File source = resolveRequiredFile(args, "file_path");
            String outputPath = stringArg(args, "output", source.getAbsolutePath());
            int quality = imageQuality(args, 80);
            Bitmap bitmap = decodeBitmap(source);
            File output = writeBitmap(bitmap, outputPath, quality);
            bitmap.recycle();
            JsonObject result = new JsonObject();
            result.addProperty("path", output.getAbsolutePath());
            result.addProperty("bytes", output.length());
            result.addProperty("quality", quality);
            return result;
        }
        if ("thumbnail".equals(action) || "thumb".equals(action)) {
            File source = resolveRequiredFile(args, "file_path");
            String outputPath = requireOutput(args);
            int maxSize = args.has("max_size") ? args.get("max_size").getAsInt() : 256;
            if (maxSize <= 0) maxSize = 256;
            int quality = imageQuality(args, 85);
            Bitmap bitmap = decodeBitmap(source);
            int srcW = bitmap.getWidth();
            int srcH = bitmap.getHeight();
            float scale = Math.min(maxSize / (float) srcW, maxSize / (float) srcH);
            if (scale > 1f) scale = 1f;
            int width = Math.max(1, Math.round(srcW * scale));
            int height = Math.max(1, Math.round(srcH * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            File output = writeBitmap(scaled, outputPath, quality);
            if (scaled != bitmap) scaled.recycle();
            bitmap.recycle();
            JsonObject result = new JsonObject();
            result.addProperty("path", output.getAbsolutePath());
            result.addProperty("width", width);
            result.addProperty("height", height);
            result.addProperty("bytes", output.length());
            return result;
        }
        if ("to_base64".equals(action)) {
            File file = resolveRequiredFile(args, "file_path");
            byte[] bytes = readBytes(file);
            JsonObject result = new JsonObject();
            result.addProperty("path", file.getAbsolutePath());
            result.addProperty("content", Base64.encodeToString(bytes, Base64.NO_WRAP));
            result.addProperty("bytes", bytes.length);
            return result;
        }
        if ("from_base64".equals(action)) {
            String outputPath = stringArg(args, "file_path", stringArg(args, "output", ""));
            String content = stringArg(args, "content", "");
            if (outputPath.length() == 0) {
                throw new Exception("缺少 file_path");
            }
            if (content.length() == 0) {
                throw new Exception("缺少 content");
            }
            // strip data URL prefix if present
            int comma = content.indexOf(',');
            if (content.startsWith("data:") && comma > 0) {
                content = content.substring(comma + 1);
            }
            File output = this.pathTools.resolve(outputPath);
            ensureParent(output);
            byte[] bytes = Base64.decode(content, Base64.DEFAULT);
            FileOutputStream out = new FileOutputStream(output);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            JsonObject result = new JsonObject();
            result.addProperty("path", output.getAbsolutePath());
            result.addProperty("bytes", output.length());
            return result;
        }
        throw new Exception("未知 image action: " + action
                + "（可用 info/resize/convert/crop/rotate/flip/grayscale/compress/thumbnail/to_base64/from_base64）");
    }

    private String normalizeImageAction(JsonObject args) {
        String raw = "";
        if (args != null) {
            if (args.has("action") && !args.get("action").isJsonNull()) {
                raw = args.get("action").getAsString();
            } else if (args.has("mode") && !args.get("mode").isJsonNull()) {
                raw = args.get("mode").getAsString();
            }
        }
        if (raw == null) raw = "";
        raw = raw.trim().toLowerCase(java.util.Locale.US);
        if (raw.length() == 0) return "info";
        if ("grey".equals(raw)) return "grayscale";
        if ("thumb".equals(raw)) return "thumbnail";
        return raw;
    }

    JsonObject toolAdbStatus() {
        JsonObject result = new JsonObject();
        File adb = new File(ADB_BIN);
        result.addProperty("adb_path", adb.getAbsolutePath());
        result.addProperty("exists", adb.exists());
        result.addProperty("executable", adb.canExecute());
        result.addProperty("message", adb.exists() ? "ADB 已找到" : "ADB 不存在");
        return result;
    }

    JsonObject toolAdbDevices() throws Exception {
        return runAdb(new String[]{ADB_BIN, "devices", "-l"}, null, 15000);
    }

    JsonObject toolAdbExec(JsonObject args) throws Exception {
        if (!args.has("args")) {
            throw new Exception("缺少 args");
        }
        String rawArgs = args.get("args").getAsString().trim();
        if (rawArgs.length() == 0) {
            throw new Exception("args 不能为空");
        }
        int timeout = args.has("timeout") ? args.get("timeout").getAsInt() : 30000;
        String[] parts = rawArgs.split("\\s+");
        String[] command = new String[parts.length + 1];
        command[0] = ADB_BIN;
        System.arraycopy(parts, 0, command, 1, parts.length);
        return runAdb(command, null, timeout);
    }

    JsonObject toolAdbShell(JsonObject args) throws Exception {
        String cmd = args.has("cmd") ? args.get("cmd").getAsString() : "";
        if (cmd.length() == 0) {
            throw new Exception("缺少 cmd");
        }
        int timeout = args.has("timeout") ? args.get("timeout").getAsInt() : 30000;
        return runAdb(new String[]{ADB_BIN, "shell", cmd}, null, timeout);
    }

    JsonObject toolGradleBuild(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : ".";
        String task = args.has("task") ? args.get("task").getAsString() : "assembleDebug";
        int timeout = args.has("timeout") ? args.get("timeout").getAsInt() : 120000;
        File cwd = this.pathTools.resolve(path);
        JsonObject shellArgs = new JsonObject();
        shellArgs.addProperty("cmd", "./gradlew " + task);
        shellArgs.addProperty("cwd", cwd.getAbsolutePath());
        shellArgs.addProperty("timeout", timeout);
        JsonObject result = this.shellTools.toolShell(shellArgs);
        result.addProperty("task", task);
        return result;
    }

    JsonObject toolCheckSyntax(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        File file = this.pathTools.resolve(path);
        if (!file.exists()) {
            throw new Exception("路径不存在:" + file.getAbsolutePath());
        }
        StringBuilder sb = new StringBuilder();
        if (file.isDirectory()) {
            ArrayList<File> files = new ArrayList<>();
            collectFiles(file, files, args.has("max") ? args.get("max").getAsInt() : 200);
            for (int i = 0; i < files.size(); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(checkOneFile(files.get(i)));
            }
        } else {
            sb.append(checkOneFile(file));
        }
        JsonObject result = new JsonObject();
        result.addProperty("content", sb.toString());
        return result;
    }

    JsonObject toolZipPath(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        File source = this.pathTools.resolve(path);
        if (!source.exists()) {
            throw new Exception("路径不存在:" + source.getAbsolutePath());
        }
        File output = args.has("output") ? this.pathTools.resolve(args.get("output").getAsString()) : new File(source.getParentFile(), source.getName() + ".zip");
        ensureParent(output);
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output));
        try {
            zipWalk(source, source.getName(), zip);
        } finally {
            zip.close();
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", output.getAbsolutePath());
        result.addProperty("source", source.getAbsolutePath());
        result.addProperty("bytes", output.length());
        return result;
    }

    JsonObject toolUnzipFile(JsonObject args) throws Exception {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "";
        String destination = args.has("destination") ? args.get("destination").getAsString() : "";
        if (destination.length() == 0) {
            throw new Exception("缺少 destination");
        }
        File zipFile = this.pathTools.resolve(path);
        File destDir = this.pathTools.resolve(destination);
        unzipTo(zipFile, destDir, args.has("overwrite") && args.get("overwrite").getAsBoolean());
        JsonObject result = new JsonObject();
        result.addProperty("path", destDir.getAbsolutePath());
        result.addProperty("source", zipFile.getAbsolutePath());
        result.addProperty("exists", destDir.exists());
        return result;
    }

    private File resolveRequiredFile(JsonObject args, String key) throws Exception {
        if (!args.has(key)) {
            throw new Exception("缺少 " + key);
        }
        File file = this.pathTools.resolve(args.get(key).getAsString());
        return FileAccessHelper.requireReadableFile(file);
    }

    private String inputText(JsonObject args) throws Exception {
        if (args.has("file_path")) {
            File file = FileAccessHelper.requireReadableFile(this.pathTools.resolve(args.get("file_path").getAsString()));
            return this.readTools.readTextPublic(file);
        }
        return args.has("text") ? args.get("text").getAsString() : "";
    }

    private JsonObject outputOrFile(JsonObject args, String text, String action) throws Exception {
        if (args.has("output")) {
            JsonObject writeArgs = new JsonObject();
            writeArgs.addProperty("file_path", args.get("output").getAsString());
            writeArgs.addProperty("content", text);
            JsonObject result = this.writeTools.toolWrite(writeArgs);
            result.addProperty("action", action);
            return result;
        }
        JsonObject result = new JsonObject();
        result.addProperty("content", text);
        return result;
    }

    private String trimLines(String text) {
        String[] split = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < split.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(split[i].trim());
        }
        return sb.toString();
    }

    private String removeEmptyLines(String text) {
        String[] split = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : split) {
            if (line.trim().length() == 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    private String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private JsonObject requestUrl(String method, String urlText, JsonObject args, byte[] body) throws Exception {
        HttpURLConnection connection = openConnection(method, urlText, args);
        if (body != null) {
            connection.setDoOutput(true);
            OutputStream outputStream = connection.getOutputStream();
            try {
                outputStream.write(body);
            } finally {
                outputStream.close();
            }
        }
        int status = connection.getResponseCode();
        String responseBody = "HEAD".equalsIgnoreCase(method) ? "" : readConnectionBody(connection, status);
        JsonObject result = new JsonObject();
        result.addProperty("url", urlText);
        result.addProperty("method", method);
        result.addProperty("status", status);
        result.addProperty("content_type", connection.getContentType() == null ? "" : connection.getContentType());
        result.addProperty("body", responseBody);
        if (args != null && args.has("include_headers") && args.get("include_headers").getAsBoolean()) {
            result.add("headers", responseHeaders(connection));
        }
        return result;
    }

    private HttpURLConnection openConnection(String method, String urlText, JsonObject args) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(args != null && args.has("connect_timeout") ? args.get("connect_timeout").getAsInt() : 5000);
        connection.setReadTimeout(args != null && args.has("read_timeout") ? args.get("read_timeout").getAsInt() : 10000);
        connection.setInstanceFollowRedirects(!(args != null && args.has("follow_redirects") && !args.get("follow_redirects").getAsBoolean()));
        if (args != null && args.has("content_type") && !args.get("content_type").isJsonNull()) {
            connection.setRequestProperty("Content-Type", args.get("content_type").getAsString());
        }
        if (args != null && args.has("headers") && args.get("headers").isJsonObject()) {
            JsonObject headers = args.getAsJsonObject("headers");
            for (String key : headers.keySet()) {
                connection.setRequestProperty(key, headers.get(key).getAsString());
            }
        }
        return connection;
    }

    private JsonObject responseHeaders(HttpURLConnection connection) {
        JsonObject headers = new JsonObject();
        java.util.Map<String, java.util.List<String>> map = connection.getHeaderFields();
        if (map != null) {
            for (java.util.Map.Entry<String, java.util.List<String>> e : map.entrySet()) {
                if (e.getKey() == null) continue;
                StringBuilder sb = new StringBuilder();
                java.util.List<String> values = e.getValue();
                if (values != null) {
                    for (int i = 0; i < values.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(values.get(i));
                    }
                }
                headers.addProperty(e.getKey(), sb.toString());
            }
        }
        return headers;
    }

    private byte[] requestBodyBytes(JsonObject args) throws Exception {
        if (args == null) return new byte[0];
        if (args.has("body_file") && !args.get("body_file").isJsonNull()) {
            String path = args.get("body_file").getAsString();
            File file = FileAccessHelper.requireReadableFile(this.pathTools.resolve(path));
            return readBytes(file);
        }
        if (args.has("body_base64") && !args.get("body_base64").isJsonNull()) {
            return Base64.decode(args.get("body_base64").getAsString(), Base64.DEFAULT);
        }
        if (args.has("body") && !args.get("body").isJsonNull()) {
            return args.get("body").getAsString().getBytes(StandardCharsets.UTF_8);
        }
        return new byte[0];
    }

    private JsonObject multipartUpload(String method, String urlText, JsonObject args, File file, String field, String filename) throws Exception {
        String boundary = "----ApksToApkHttp" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setRequestMethod(method);
        connection.setDoOutput(true);
        connection.setConnectTimeout(args != null && args.has("connect_timeout") ? args.get("connect_timeout").getAsInt() : 5000);
        connection.setReadTimeout(args != null && args.has("read_timeout") ? args.get("read_timeout").getAsInt() : 30000);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (args != null && args.has("headers") && args.get("headers").isJsonObject()) {
            JsonObject headers = args.getAsJsonObject("headers");
            for (String key : headers.keySet()) {
                connection.setRequestProperty(key, headers.get(key).getAsString());
            }
        }
        OutputStream outputStream = connection.getOutputStream();
        try {
            // extra form fields
            if (args != null && args.has("form") && args.get("form").isJsonObject()) {
                JsonObject form = args.getAsJsonObject("form");
                for (String key : form.keySet()) {
                    writeMultipartText(outputStream, boundary, key, form.get(key).getAsString());
                }
            }
            String mime = stringArg(args, "mime", "application/octet-stream");
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + mime + "\r\n\r\n";
            outputStream.write(header.getBytes(StandardCharsets.UTF_8));
            FileInputStream inputStream = new FileInputStream(file);
            try {
                byte[] buffer = new byte[8192];
                while (true) {
                    int read = inputStream.read(buffer);
                    if (read < 0) break;
                    outputStream.write(buffer, 0, read);
                }
            } finally {
                inputStream.close();
            }
            outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
            String end = "--" + boundary + "--\r\n";
            outputStream.write(end.getBytes(StandardCharsets.UTF_8));
        } finally {
            outputStream.close();
        }
        int status = connection.getResponseCode();
        String responseBody = readConnectionBody(connection, status);
        JsonObject result = new JsonObject();
        result.addProperty("url", urlText);
        result.addProperty("method", method);
        result.addProperty("status", status);
        result.addProperty("path", file.getAbsolutePath());
        result.addProperty("bytes", file.length());
        result.addProperty("body", responseBody);
        return result;
    }

    private void writeMultipartText(OutputStream out, String boundary, String name, String value) throws Exception {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        out.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private String stringArg(JsonObject args, String name, String defaultValue) {
        if (args == null || !args.has(name) || args.get(name).isJsonNull()) {
            return defaultValue;
        }
        try {
            return args.get(name).getAsString();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String requireOutput(JsonObject args) throws Exception {
        String outputPath = stringArg(args, "output", "");
        if (outputPath.length() == 0) {
            throw new Exception("缺少 output");
        }
        return outputPath;
    }

    private int imageQuality(JsonObject args, int fallback) {
        if (args == null || !args.has("quality")) return fallback;
        try {
            int q = args.get("quality").getAsInt();
            if (q < 1) return 1;
            if (q > 100) return 100;
            return q;
        } catch (Exception e) {
            return fallback;
        }
    }

    private Bitmap decodeBitmap(File source) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (bitmap == null) {
            throw new Exception("无法解码图片:" + source.getAbsolutePath());
        }
        return bitmap;
    }

    private File writeBitmap(Bitmap bitmap, String outputPath, int quality) throws Exception {
        File output = this.pathTools.resolve(outputPath);
        ensureParent(output);
        FileOutputStream out = new FileOutputStream(output);
        try {
            Bitmap.CompressFormat format = guessCompressFormat(output.getName());
            bitmap.compress(format, quality, out);
        } finally {
            out.close();
        }
        return output;
    }

    private Bitmap toGrayscale(Bitmap src) {
        int width = src.getWidth();
        int height = src.getHeight();
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint();
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(0f);
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
    }

    private JsonObject runAdb(String[] command, File cwd, int timeout) throws Exception {
        File adb = new File(ADB_BIN);
        if (!adb.exists()) {
            throw new Exception("ADB 不存在: " + adb.getAbsolutePath());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null && cwd.exists() && cwd.isDirectory()) {
            builder.directory(cwd);
        }
        long startAt = System.currentTimeMillis();
        Process process = builder.start();
        String stdout = readProcessStream(process.getInputStream());
        String stderr = readProcessStream(process.getErrorStream());
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("ADB 执行被中断");
        }
        JsonObject result = new JsonObject();
        result.addProperty("cmd", String.join(" ", command));
        result.addProperty("cwd", cwd == null ? "" : cwd.getAbsolutePath());
        result.addProperty("timeout", timeout);
        result.addProperty("elapsed_ms", System.currentTimeMillis() - startAt);
        result.addProperty("exit_code", exitCode);
        result.addProperty("stdout", stdout);
        result.addProperty("stderr", stderr);
        return result;
    }

    private String readConnectionBody(HttpURLConnection connection, int status) throws Exception {
        InputStream inputStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (inputStream == null) {
            return "";
        }
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
    }

    private String readProcessStream(InputStream inputStream) throws Exception {
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
    }

    private String diffPreview(String left, String right) {
        String[] a = left.split("\n", -1);
        String[] b = right.split("\n", -1);
        int max = Math.max(a.length, b.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            String la = i < a.length ? a[i] : null;
            String lb = i < b.length ? b[i] : null;
            boolean same = la == null ? lb == null : la.equals(lb);
            if (!same) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("@@ line ").append(i + 1);
                if (la != null) sb.append("\n- ").append(la);
                if (lb != null) sb.append("\n+ ").append(lb);
                if (sb.length() > 4000) {
                    sb.append("\n...diff too long...");
                    break;
                }
            }
        }
        return sb.length() == 0 ? "(无差异)" : sb.toString();
    }

    private void collectFiles(File file, ArrayList<File> files, int max) {
        if (files.size() >= max) {
            return;
        }
        if (file.isFile()) {
            files.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (files.size() >= max) {
                return;
            }
            collectFiles(child, files, max);
        }
    }

    private String checkOneFile(File file) throws Exception {
        String text = this.readTools.readTextPublic(file);
        int round = 0;
        int square = 0;
        int curly = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') round++;
            else if (ch == ')') round--;
            else if (ch == '[') square++;
            else if (ch == ']') square--;
            else if (ch == '{') curly++;
            else if (ch == '}') curly--;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(file.getAbsolutePath()).append('\n');
        sb.append("括号检查:").append((round == 0 && square == 0 && curly == 0) ? "通过" : "可能异常").append('\n');
        if (round != 0) sb.append("圆括号差值:").append(round).append('\n');
        if (square != 0) sb.append("方括号差值:").append(square).append('\n');
        if (curly != 0) sb.append("花括号差值:").append(curly).append('\n');
        return sb.toString().trim();
    }

    private void ensureParent(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private byte[] readBytes(File file) throws Exception {
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
    }

    private Bitmap.CompressFormat guessCompressFormat(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) {
            return Bitmap.CompressFormat.PNG;
        }
        if (lower.endsWith(".webp")) {
            return Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        }
        return Bitmap.CompressFormat.JPEG;
    }

    private void zipWalk(File file, String name, ZipOutputStream zip) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null || children.length == 0) {
                zip.putNextEntry(new ZipEntry(name + "/"));
                zip.closeEntry();
                return;
            }
            for (File child : children) {
                zipWalk(child, name + "/" + child.getName(), zip);
            }
            return;
        }
        zip.putNextEntry(new ZipEntry(name));
        FileInputStream inputStream = new FileInputStream(file);
        try {
            byte[] buffer = new byte[4096];
            while (true) {
                int read = inputStream.read(buffer);
                if (read < 0) {
                    break;
                }
                zip.write(buffer, 0, read);
            }
        } finally {
            inputStream.close();
            zip.closeEntry();
        }
    }

    private void unzipTo(File zipFile, File destination, boolean overwrite) throws Exception {
        if (!destination.exists()) {
            destination.mkdirs();
        }
        ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile));
        try {
            byte[] buffer = new byte[4096];
            while (true) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) {
                    break;
                }
                File target = safeZipTarget(destination, entry.getName());
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    if (target.exists() && !overwrite) {
                        throw new Exception("目标已存在:" + target.getAbsolutePath());
                    }
                    ensureParent(target);
                    FileOutputStream output = new FileOutputStream(target);
                    try {
                        while (true) {
                            int read = zip.read(buffer);
                            if (read < 0) {
                                break;
                            }
                            output.write(buffer, 0, read);
                        }
                    } finally {
                        output.close();
                    }
                }
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
    }

    private File safeZipTarget(File root, String name) throws Exception {
        File target = new File(root, name);
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator)) {
            return target;
        }
        throw new Exception("zip 路径不安全:" + name);
    }
}
