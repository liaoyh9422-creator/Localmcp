package com.apkstoapk.app.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Minimal MCP tool registry with capability filtering. */
class McpToolRegistry {
    interface ToolHandler {
        JsonObject call(JsonObject arguments) throws Exception;
    }

    static class ToolSpec {
        final String name;
        final String description;
        final JsonObject inputSchema;
        final ToolHandler handler;
        final McpCapabilityStore.Category category;

        ToolSpec(String name, String description, JsonObject inputSchema, ToolHandler handler) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.handler = handler;
            this.category = McpCapabilityStore.categoryOf(name);
        }
    }

    private final Map<String, ToolSpec> tools = new LinkedHashMap<>();
    private final McpCapabilityStore capabilityStore;

    McpToolRegistry(McpCapabilityStore capabilityStore) {
        this.capabilityStore = capabilityStore;
    }

    void register(String name, String description, JsonObject inputSchema, ToolHandler handler) {
        tools.put(name, new ToolSpec(name, description, inputSchema, handler));
    }

    JsonArray listTools() {
        JsonArray array = new JsonArray();
        for (ToolSpec spec : tools.values()) {
            if (capabilityStore != null && !capabilityStore.isToolEnabled(spec.name)) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("name", spec.name);
            item.addProperty("description", spec.description);
            item.add("inputSchema", spec.inputSchema);
            array.add(item);
        }
        return array;
    }

    /** 已注册总数（含关闭分类）。 */
    int size() {
        return tools.size();
    }

    /** 当前对客户端可见的工具数。 */
    int enabledSize() {
        if (capabilityStore == null) return tools.size();
        int n = 0;
        for (String name : tools.keySet()) {
            if (capabilityStore.isToolEnabled(name)) n++;
        }
        return n;
    }

    Set<String> allNames() {
        return tools.keySet();
    }

    JsonObject call(String name, JsonObject arguments) throws Exception {
        ToolSpec spec = tools.get(name);
        if (spec == null) {
            throw new Exception("未知工具:" + name);
        }
        if (capabilityStore != null && !capabilityStore.isToolEnabled(name)) {
            McpCapabilityStore.Category cat = McpCapabilityStore.categoryOf(name);
            String preset = capabilityStore.getPreset().id;
            if (!capabilityStore.isCategoryEnabled(cat)) {
                throw new Exception("能力已关闭：" + cat.title + "（工具 " + name + "）");
            }
            throw new Exception("当前预设不可用：" + preset
                    + "（工具 " + name + "，分类 " + cat.title
                    + "）。可在 App「MCP」页切换到 full，或开启对应能力。");
        }
        return spec.handler.call(arguments == null ? new JsonObject() : arguments);
    }
}
