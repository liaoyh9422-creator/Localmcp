package com.apkstoapk.app.mcp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MCP 能力过滤：分类开关 + 预设（agent / full / safe）。
 *
 * <p>CORE 分类始终开启且不可关。
 * <p>{@link Preset#AGENT} 默认：只暴露精简工具集，适合 LLM。
 * <p>{@link Preset#FULL}：分类内全部工具。
 * <p>{@link Preset#SAFE}：比 agent 更紧，去掉安装等高风险写操作。
 */
public final class McpCapabilityStore {
    public static final String PREFS = "mcp_capabilities";
    private static final String KEY_PRESET = "preset";

    public enum Preset {
        AGENT("agent", "Agent", "精简工具集，适合 LLM 日常编排"),
        FULL("full", "完整", "分类内全部工具"),
        SAFE("safe", "安全", "无安装/Shell/删除，只读与基础改包");

        public final String id;
        public final String title;
        public final String desc;

        Preset(String id, String title, String desc) {
            this.id = id;
            this.title = title;
            this.desc = desc;
        }

        public static Preset fromId(String id) {
            if (id == null) return AGENT;
            for (Preset p : values()) {
                if (p.id.equalsIgnoreCase(id.trim())) return p;
            }
            // 兼容旧值
            if ("default".equalsIgnoreCase(id) || "minimal".equalsIgnoreCase(id)) {
                return AGENT;
            }
            return AGENT;
        }
    }

    public enum Category {
        CORE("core", "基础", "状态/帮助/路径导航", true, true),
        FILE("file", "文件", "读写、搜索、编辑、批处理", true, false),
        APK("apk", "APK", "合并、改包、签名、清单/资源", true, false),
        INSTALL("install", "安装", "系统安装 / Shizuku 安装", true, false),
        RUNTIME("runtime", "运行时", "Lua/Python/Java/C++/Lua转DEX", true, false),
        NETWORK("network", "网络", "HTTP 与下载", false, false),
        IMAGE("image", "图片", "缩放/裁剪/转换", false, false),
        DANGER("danger", "高风险", "Shell、删除、提权保活", false, false);

        public final String id;
        public final String title;
        public final String desc;
        public final boolean defaultEnabled;
        public final boolean locked;

        Category(String id, String title, String desc, boolean defaultEnabled, boolean locked) {
            this.id = id;
            this.title = title;
            this.desc = desc;
            this.defaultEnabled = defaultEnabled;
            this.locked = locked;
        }

        public static Category fromId(String id) {
            if (id == null) return null;
            for (Category c : values()) {
                if (c.id.equals(id)) return c;
            }
            return null;
        }
    }

    private static final Map<String, Category> TOOL_CATEGORY = new LinkedHashMap<>();
    private static final Map<Category, List<String>> CATEGORY_TOOLS = new LinkedHashMap<>();

    /**
     * Agent 精简白名单（约 30 个）。
     * 有 Python 后长尾交给 eval_python，领域核心仍保留专用工具。
     */
    private static final Set<String> AGENT_TOOLS = new LinkedHashSet<>();

    /**
     * Safe：在 agent 基础上再去掉安装与可执行运行时写能力。
     * 保留 inspect/sign/verify 与基础文件，适合只读探查 + 轻量改文件。
     */
    private static final Set<String> SAFE_TOOLS = new LinkedHashSet<>();

    static {
        // CORE — 始终可用
        putAll(Category.CORE,
                "pwd", "cd", "set_root", "exists", "stat", "ls", "list_all",
                "history", "clear_log", "health", "service_info",
                "help",
                "battery", "shizuku");

        // FILE
        putAll(Category.FILE,
                "read", "head", "tail", "read_lines", "batch_read", "read_base64",
                "find", "grep", "tree",
                "write", "append", "touch", "empty", "copy", "rename", "mkdir",
                "edit", "code_replace", "write_base64",
                "batch_ops", "file_text");

        // APK
        putAll(Category.APK,
                "list_splits", "merge_apks", "patch_apk", "rename_package", "inspect_apk",
                "list_components", "set_component_name",
                "list_permissions", "edit_permissions",
                "list_metadata", "edit_metadata",
                "inject_entry", "remove_entry",
                "export_manifest_xml", "apply_manifest_xml",
                // DEX full surface — no downgrade
                "list_dex", "list_dex_classes", "decompile_smali", "decompile_java", "extract_dex",
                "export_smali_dir", "import_smali_dir",
                "compile_smali", "export_smali_class",
                "patch_dex", "clear_dex_methods",
                "rename_dex_method", "rename_dex_field",
                "set_string_res", "sanitize_apk",
                "sign_apk", "verify_apk", "export_apk", "hash_file");

        // INSTALL
        putAll(Category.INSTALL, "install_apk", "install_apk_shizuku");

        // RUNTIME
        putAll(Category.RUNTIME, "runtime");

        // NETWORK
        putAll(Category.NETWORK, "http");

        // IMAGE
        putAll(Category.IMAGE, "image");

        // DANGER
        putAll(Category.DANGER, "delete", "shell", "battery_fix");

        // Agent allowlist
        putAgent(
                // nav / status
                "pwd", "cd", "set_root", "ls", "exists", "stat",
                "health", "help", "history", "clear_log", "shizuku", "battery",
                // file minimal
                "read", "write", "append", "find", "grep",
                "mkdir", "copy", "rename", "code_replace", "batch_ops", "file_text",
                // apk core
                "list_splits", "merge_apks", "patch_apk", "rename_package", "inspect_apk",
                "list_components", "set_component_name",
                "list_permissions", "edit_permissions",
                "list_metadata", "edit_metadata",
                "inject_entry", "remove_entry",
                "export_manifest_xml", "apply_manifest_xml",
                "set_string_res", "sanitize_apk", "export_apk",
                // DEX full surface — agent 不降级
                "list_dex", "list_dex_classes", "decompile_smali", "decompile_java", "extract_dex",
                "export_smali_dir", "import_smali_dir",
                "compile_smali", "export_smali_class",
                "patch_dex", "clear_dex_methods",
                "rename_dex_method", "rename_dex_field",
                "sign_apk", "verify_apk", "hash_file",
                // install + python
                "install_apk",
                "runtime"
        );

        // Safe = agent without install / eval_python (no execution side effects beyond file+apk patch)
        for (String name : AGENT_TOOLS) {
            if ("install_apk".equals(name) || "runtime".equals(name)) {
                continue;
            }
            SAFE_TOOLS.add(name);
        }
    }

    private static void putAll(Category category, String... names) {
        List<String> list = new ArrayList<>();
        for (String name : names) {
            TOOL_CATEGORY.put(name, category);
            list.add(name);
        }
        CATEGORY_TOOLS.put(category, Collections.unmodifiableList(list));
    }

    private static void putAgent(String... names) {
        AGENT_TOOLS.clear();
        Collections.addAll(AGENT_TOOLS, names);
    }

    private final SharedPreferences prefs;

    public McpCapabilityStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Category categoryOf(String toolName) {
        if (toolName == null) return Category.CORE;
        Category c = TOOL_CATEGORY.get(toolName);
        return c != null ? c : Category.CORE;
    }

    public static List<String> toolsOf(Category category) {
        List<String> list = CATEGORY_TOOLS.get(category);
        return list == null ? Collections.<String>emptyList() : list;
    }

    public static List<Category> uiCategories() {
        return Arrays.asList(Category.values());
    }

    public static List<Preset> uiPresets() {
        return Arrays.asList(Preset.values());
    }

    public static Set<String> agentToolNames() {
        return Collections.unmodifiableSet(AGENT_TOOLS);
    }

    public static Set<String> safeToolNames() {
        return Collections.unmodifiableSet(SAFE_TOOLS);
    }

    public Preset getPreset() {
        return Preset.fromId(prefs.getString(KEY_PRESET, Preset.AGENT.id));
    }

    public void setPreset(Preset preset) {
        if (preset == null) preset = Preset.AGENT;
        prefs.edit().putString(KEY_PRESET, preset.id).apply();
    }

    /**
     * 应用预设时同步分类开关到推荐默认，减少「预设是 agent 但 danger 开着」的困惑。
     * 用户仍可之后手动改分类。
     */
    public void applyPreset(Preset preset) {
        if (preset == null) preset = Preset.AGENT;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_PRESET, preset.id);

        // 所有非锁定分类先回到各自 defaultEnabled，再按预设收紧
        for (Category c : Category.values()) {
            if (c.locked) continue;
            boolean enabled = c.defaultEnabled;
            if (preset == Preset.SAFE) {
                // safe：关安装/运行时/网络/图片/高风险
                if (c == Category.INSTALL
                        || c == Category.RUNTIME
                        || c == Category.NETWORK
                        || c == Category.IMAGE
                        || c == Category.DANGER) {
                    enabled = false;
                } else if (c == Category.FILE || c == Category.APK) {
                    enabled = true;
                }
            } else if (preset == Preset.AGENT) {
                // agent：主路径开，长尾关
                if (c == Category.NETWORK || c == Category.IMAGE || c == Category.DANGER) {
                    enabled = false;
                } else if (c == Category.FILE
                        || c == Category.APK
                        || c == Category.INSTALL
                        || c == Category.RUNTIME) {
                    enabled = true;
                }
            } else {
                // full：恢复分类默认（NETWORK/IMAGE/DANGER 默认关）
                enabled = c.defaultEnabled;
            }
            editor.putBoolean(key(c), enabled);
        }
        editor.apply();
    }

    public boolean isCategoryEnabled(Category category) {
        if (category == null) return true;
        if (category.locked) return true;
        return prefs.getBoolean(key(category), category.defaultEnabled);
    }

    public void setCategoryEnabled(Category category, boolean enabled) {
        if (category == null || category.locked) return;
        prefs.edit().putBoolean(key(category), enabled).apply();
    }

    /** 工具是否在当前预设白名单中（full 恒 true）。 */
    public boolean isToolAllowedByPreset(String toolName) {
        Preset preset = getPreset();
        if (preset == Preset.FULL) return true;
        if (toolName == null) return false;
        if (preset == Preset.SAFE) return SAFE_TOOLS.contains(toolName);
        return AGENT_TOOLS.contains(toolName);
    }

    public boolean isToolEnabled(String toolName) {
        if (!isCategoryEnabled(categoryOf(toolName))) return false;
        return isToolAllowedByPreset(toolName);
    }

    public int enabledCategoryCount() {
        int n = 0;
        for (Category c : Category.values()) {
            if (isCategoryEnabled(c)) n++;
        }
        return n;
    }

    /** 在「已注册工具全集」上统计当前可见数。 */
    public int countEnabledTools(Set<String> allRegistered) {
        if (allRegistered == null || allRegistered.isEmpty()) {
            // 无注册表时按白名单估算
            if (getPreset() == Preset.FULL) {
                int n = 0;
                for (Category c : Category.values()) {
                    if (!isCategoryEnabled(c)) continue;
                    n += toolsOf(c).size();
                }
                return n;
            }
            int n = 0;
            for (String name : (getPreset() == Preset.SAFE ? SAFE_TOOLS : AGENT_TOOLS)) {
                if (isCategoryEnabled(categoryOf(name))) n++;
            }
            return n;
        }
        int n = 0;
        for (String name : allRegistered) {
            if (isToolEnabled(name)) n++;
        }
        return n;
    }

    public String summaryText() {
        return summaryText(null);
    }

    public String summaryText(Set<String> allRegistered) {
        Preset preset = getPreset();
        int on = 0;
        int total = 0;
        for (Category c : Category.values()) {
            if (c == Category.CORE) continue;
            total++;
            if (isCategoryEnabled(c)) on++;
        }
        int tools = countEnabledTools(allRegistered);
        return "预设 " + preset.title + " · 可见约 " + tools + " 个工具 · 分类 " + on + "/" + total;
    }

    public Set<String> enabledToolNames(Set<String> allRegistered) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (allRegistered == null) return out;
        for (String name : allRegistered) {
            if (isToolEnabled(name)) out.add(name);
        }
        return out;
    }

    /** 分类下、当前预设可见的工具列表文案。 */
    public String toolsJoinedForUi(Category category) {
        List<String> tools = toolsOf(category);
        if (tools.isEmpty()) return "";
        Preset preset = getPreset();
        List<String> visible = new ArrayList<>();
        for (String name : tools) {
            if (preset == Preset.FULL || isToolAllowedByPreset(name)) {
                visible.add(name);
            }
        }
        if (visible.isEmpty()) {
            return "（当前预设下无可见工具）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < visible.size(); i++) {
            if (i > 0) sb.append("  ·  ");
            sb.append(visible.get(i));
            if (i >= 11 && visible.size() > 12) {
                sb.append("  ·  …共").append(visible.size()).append("个");
                break;
            }
        }
        if (preset != Preset.FULL && visible.size() < tools.size()) {
            sb.append("\n（完整分类共 ").append(tools.size())
                    .append(" 个，切换到「完整」预设可全部暴露）");
        }
        return sb.toString();
    }

    /** @deprecated 使用 {@link #toolsJoinedForUi(Category)} 以尊重预设 */
    @Deprecated
    public static String toolsJoined(Category category) {
        List<String> tools = toolsOf(category);
        if (tools.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) sb.append("  ·  ");
            sb.append(tools.get(i));
            if (i >= 11 && tools.size() > 12) {
                sb.append("  ·  …共").append(tools.size()).append("个");
                break;
            }
        }
        return sb.toString();
    }

    public static String statusLabel(boolean enabled, boolean locked) {
        if (locked) return "始终开";
        return enabled ? "开" : "关";
    }

    public static String normalizeToolKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.US);
    }

    private static String key(Category category) {
        return "cat_" + category.id;
    }
}
