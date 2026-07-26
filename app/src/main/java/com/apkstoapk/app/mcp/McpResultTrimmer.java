package com.apkstoapk.app.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

class McpResultTrimmer {
    String trim(String toolName, JsonObject result) {
        if (result == null || result.isEmpty()) {
            return "";
        }
        if (("pwd".equals(toolName) || "cd".equals(toolName) || "set_root".equals(toolName))
                && result.has("path")) {
            return primitiveText(result.get("path"));
        }
        if ("exists".equals(toolName) && result.has("path") && result.has("exists")) {
            return primitiveText(result.get("path")) + " => "
                    + (result.get("exists").getAsBoolean() ? "存在" : "不存在");
        }
        if ("stat".equals(toolName) && result.has("path") && result.has("exists")) {
            if (!result.get("exists").getAsBoolean()) {
                return primitiveText(result.get("path")) + " => 不存在";
            }
            String size = result.has("size") ? primitiveText(result.get("size")) : "未知";
            return primitiveText(result.get("path")) + " | size=" + size;
        }
        if (("health".equals(toolName) || "service_info".equals(toolName))
                && result.has("status")) {
            StringBuilder sb = new StringBuilder();
            appendField(sb, "服务状态", result.get("status"), "");
            appendField(sb, "端口", result.get("port"), "");
            if (result.has("running")) {
                appendTextField(sb, "运行中", result.get("running").getAsBoolean() ? "是" : "否", "");
            } else {
                appendTextField(sb, "运行中", "未知", "");
            }
            appendField(sb, "地址", result.get("url"), "");
            appendField(sb, "工作目录", result.get("work_dir"), "");
            return sb.toString();
        }
        if (result.has("content") && result.get("content").isJsonPrimitive()
                && result.get("content").getAsJsonPrimitive().isString()) {
            if ("help".equals(toolName)
                    || "tool_help".equals(toolName) || "script_help".equals(toolName)
                    || "file_help".equals(toolName) || "system_help".equals(toolName)
                    || "read".equals(toolName) || "head".equals(toolName)
                    || "tail".equals(toolName) || "read_lines".equals(toolName)
                    || "batch_read".equals(toolName) || "grep".equals(toolName)
                    || "tree".equals(toolName) || "shell".equals(toolName)
                    || "shizuku_shell".equals(toolName)) {
                return result.get("content").getAsString();
            }
        }
        if ("shell".equals(toolName)) {
            StringBuilder sb = new StringBuilder();
            appendField(sb, "exit_code", result.get("exit_code"), "");
            appendMultilineField(sb, "stdout", result.get("stdout"));
            appendMultilineField(sb, "stderr", result.get("stderr"));
            return sb.toString();
        }
        if ("history".equals(toolName) && result.has("items") && result.get("items").isJsonArray()) {
            JsonArray items = result.getAsJsonArray("items");
            if (items.isEmpty()) {
                return "暂无历史记录";
            }
            StringBuilder sb = new StringBuilder("历史记录 ").append(items.size()).append(" 条");
            for (JsonElement item : items) {
                if (item.isJsonObject()) {
                    JsonObject entry = item.getAsJsonObject();
                    sb.append("\n").append(primitiveText(entry.get("time")))
                            .append("  ").append(primitiveText(entry.get("message")));
                }
            }
            return sb.toString();
        }
        if ("find".equals(toolName) && result.has("items") && result.get("items").isJsonArray()) {
            JsonArray items = result.getAsJsonArray("items");
            if (items.isEmpty()) {
                return "没有找到匹配文件";
            }
            StringBuilder sb = new StringBuilder("找到 ").append(items.size()).append(" 个结果");
            for (JsonElement item : items) {
                sb.append("\n").append(primitiveText(item));
            }
            return sb.toString();
        }
        return renderObject(result, "");
    }

    private String renderObject(JsonObject object, String indent) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            appendField(sb, entry.getKey(), entry.getValue(), indent);
        }
        return sb.toString();
    }

    private void appendField(StringBuilder sb, String key, JsonElement value, String indent) {
        sb.append(indent).append(key).append(": ");
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            sb.append(primitiveText(value));
            return;
        }
        sb.append('\n').append(renderNested(value, indent + "  "));
    }

    private void appendTextField(StringBuilder sb, String key, String value, String indent) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(indent).append(key).append(": ").append(value == null ? "null" : value);
    }

    private void appendMultilineField(StringBuilder sb, String key, JsonElement value) {
        if (value == null || value.isJsonNull() || primitiveText(value).isEmpty()) {
            return;
        }
        sb.append('\n').append(key).append(":\n").append(primitiveText(value));
    }

    private String renderNested(JsonElement value, String indent) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            return indent + primitiveText(value);
        }
        if (value.isJsonObject()) {
            return renderObject(value.getAsJsonObject(), indent);
        }
        JsonArray array = value.getAsJsonArray();
        if (array.isEmpty()) {
            return indent + "[]";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement item : array) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(indent).append("- ");
            if (item.isJsonObject() || item.isJsonArray()) {
                sb.append('\n').append(renderNested(item, indent + "  "));
            } else {
                sb.append(primitiveText(item));
            }
        }
        return sb.toString();
    }

    private String primitiveText(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonPrimitive()) {
            return value.getAsJsonPrimitive().isString()
                    ? value.getAsString() : value.toString();
        }
        return renderNested(value, "");
    }
}