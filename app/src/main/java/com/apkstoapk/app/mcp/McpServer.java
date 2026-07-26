package com.apkstoapk.app.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Local JSON-RPC MCP HTTP server (127.0.0.1 only).
 * Transport: Android Service → Ktor CIO → single endpoint /mcp.
 * Protocol: hand-written JSON-RPC (initialize / tools/list / tools/call / …).
 * Tool surface unchanged (registerTools + McpToolRegistry).
 */
class McpServer {
    private final int port;
    private final android.content.Context context;
    private final Gson gson = new Gson();
    private final McpCapabilityStore capabilityStore;
    private final McpToolRegistry toolRegistry;
    private final McpPathTools pathTools = new McpPathTools();
    private final McpReadTools readTools = new McpReadTools(this.pathTools);
    private final McpWriteTools writeTools = new McpWriteTools(this.pathTools);
    private final McpShellTools shellTools = new McpShellTools(this.pathTools);
    private final McpApkTools apkTools;
    private final McpRuntimeTools runtimeTools;
    private McpExtraTools extraTools;
    private final McpResultTrimmer resultTrimmer = new McpResultTrimmer();
    private volatile boolean running;
    private McpKtorTransport transport;

    McpServer(android.content.Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.capabilityStore = new McpCapabilityStore(this.context);
        this.toolRegistry = new McpToolRegistry(this.capabilityStore);
        this.apkTools = new McpApkTools(this.context);
        this.runtimeTools = new McpRuntimeTools(this.context, this.pathTools);
        registerTools();
    }

    private void registerTools() {
        this.extraTools = new McpExtraTools(this, this.context, this.pathTools, this.readTools, this.writeTools, this.shellTools);

        // path
        toolRegistry.register("pwd", "查看当前目录", schema(), args -> this.pathTools.toolPwd());
        toolRegistry.register("cd", "切换当前目录", schema(props(prop("dir", "string", "目录路径")), req("dir")), args -> this.pathTools.toolCd(args));
        toolRegistry.register("set_root", "设置工作根目录", schema(props(prop("file_path", "string", "项目路径")), req("file_path")), args -> this.pathTools.toolSetRoot(args));
        toolRegistry.register("exists", "检查路径是否存在", schema(props(prop("file_path", "string", "路径")), req("file_path")), args -> this.pathTools.toolExists(args));
        toolRegistry.register("stat", "查看路径基本信息", schema(props(prop("file_path", "string", "路径")), req("file_path")), args -> this.pathTools.toolStat(args));
        toolRegistry.register("ls", "查看目录内容", schema(props(prop("file_path", "string", "目录路径")), req("file_path")), args -> this.pathTools.toolLs(args));
        toolRegistry.register("list_all", "查看目录全部内容", schema(props(prop("file_path", "string", "目录路径")), req("file_path")), args -> this.pathTools.toolListAll(args));

        // read
        toolRegistry.register("read", "读取文本文件", schema(props(prop("file_path", "string", "文件路径")), req("file_path")), args -> this.readTools.toolRead(args));
        toolRegistry.register("head", "读取开头几行", schema(props(prop("file_path", "string", "文件路径"), prop("lines", "integer", "行数")), req("file_path")), args -> this.readTools.toolHead(args));
        toolRegistry.register("tail", "读取末尾几行", schema(props(prop("file_path", "string", "文件路径"), prop("lines", "integer", "行数")), req("file_path")), args -> this.readTools.toolTail(args));
        toolRegistry.register("read_lines", "读取指定行", schema(props(prop("file_path", "string", "文件路径"), prop("start", "integer", "开始行"), prop("end", "integer", "结束行")), req("file_path", "start", "end")), args -> this.readTools.toolReadLines(args));
        toolRegistry.register("batch_read", "批量读取多个文件", schema(props(prop("files", "array", "文件路径数组")), req("files")), args -> this.readTools.toolBatchRead(args));
        toolRegistry.register("read_base64", "读取二进制为 Base64", schema(props(prop("file_path", "string", "文件路径")), req("file_path")), args -> this.readTools.toolReadBase64(args));
        toolRegistry.register("find", "按文件名查找（支持 glob：*.java；无通配符时为子串匹配）", schema(props(prop("file_path", "string", "搜索根路径"), prop("name", "string", "名称关键词或 glob，如 *.java / McpServer")), req("file_path", "name")), args -> this.readTools.toolFind(args));
        toolRegistry.register("grep", "搜索文件内容", schema(props(prop("file_path", "string", "搜索根路径"), prop("query", "string", "搜索文本")), req("file_path", "query")), args -> this.readTools.toolGrep(args));
        toolRegistry.register("tree", "查看目录树", schema(props(prop("file_path", "string", "目录路径")), req("file_path")), args -> this.readTools.toolTree(args));

        // write
        toolRegistry.register("write", "写入文本文件", schema(props(prop("file_path", "string", "文件路径"), prop("content", "string", "文件内容")), req("file_path", "content")), args -> this.writeTools.toolWrite(args));
        toolRegistry.register("append", "追加文本文件", schema(props(prop("file_path", "string", "文件路径"), prop("content", "string", "追加内容")), req("file_path", "content")), args -> this.writeTools.toolAppend(args));
        toolRegistry.register("touch", "创建空文件或更新时间", schema(props(prop("file_path", "string", "文件路径")), req("file_path")), args -> this.writeTools.toolTouch(args));
        toolRegistry.register("empty", "清空文件内容", schema(props(prop("file_path", "string", "文件路径")), req("file_path")), args -> this.writeTools.toolEmpty(args));
        toolRegistry.register("copy", "复制文件或目录", schema(props(prop("source", "string", "源路径"), prop("destination", "string", "目标路径")), req("source", "destination")), args -> this.writeTools.toolCopy(args));
        toolRegistry.register("rename", "重命名或移动", schema(props(prop("source", "string", "源路径"), prop("destination", "string", "目标路径")), req("source", "destination")), args -> this.writeTools.toolRename(args));
        toolRegistry.register("mkdir", "创建目录", schema(props(prop("file_path", "string", "目录路径")), req("file_path")), args -> this.writeTools.toolMkdir(args));
        toolRegistry.register("delete", "删除文件或目录", schema(props(prop("file_path", "string", "路径")), req("file_path")), args -> this.writeTools.toolDelete(args));
        toolRegistry.register("edit", "替换文件中的文本", schema(props(prop("file_path", "string", "文件路径"), prop("find", "string", "查找文本"), prop("replace", "string", "替换文本")), req("file_path", "find", "replace")), args -> this.writeTools.toolEdit(args));
        toolRegistry.register("code_replace", "严格替换代码片段，默认要求唯一匹配（参数 file_path/old_text/new_text）", schema(props(prop("file_path", "string", "文件路径"), prop("old_text", "string", "原始代码片段"), prop("new_text", "string", "替换后的代码片段"), prop("expected_count", "integer", "期望匹配次数；默认1；设为0可断言不得出现"), prop("all", "boolean", "是否替换全部匹配")), req("file_path", "old_text", "new_text")), args -> this.writeTools.toolCodeReplace(args));
        toolRegistry.register("write_base64", "写入 Base64 二进制文件", schema(props(prop("file_path", "string", "文件路径"), prop("content", "string", "Base64 内容")), req("file_path", "content")), args -> this.writeTools.toolWriteBase64(args));

        // service
        toolRegistry.register("history", "查看运行历史", schema(), args -> this.writeTools.toolHistory(args));
        toolRegistry.register("clear_log", "清空运行日志", schema(), args -> this.writeTools.toolClearLog());
        toolRegistry.register("health", "查看服务健康状态", schema(), args -> toolHealth());
        toolRegistry.register("service_info", "查看服务详情", schema(), args -> toolHealth());
                toolRegistry.register("help", "帮助", schema(props(
                prop("action", "string", "tool|file|system；默认 tool"),
                prop("topic", "string", "可选，等同 action")
        ), req()), args -> toolHelpUnified(args));

        // extra
        toolRegistry.register("batch_ops", "一次执行多个工具", schema(props(prop("items", "array", "工具调用列表"), prop("ops", "array", "兼容参数，同items"), prop("stop_on_error", "boolean", "出错时停止")), req()), args -> this.extraTools.toolBatchOps(args));
                toolRegistry.register("file_text", "文本处理", schema(props(
                prop("action", "string", "convert|json|compare；默认 convert"),
                prop("mode", "string", "convert 子动作或 json 模式；可选"),
                prop("text", "string", "文本"),
                prop("file_path", "string", "文件路径"),
                prop("output", "string", "输出文件"),
                prop("left", "string", "compare 左文件"),
                prop("right", "string", "compare 右文件"),
                prop("compact", "boolean", "json 是否压缩")
        ), req()), args -> this.extraTools.toolFileText(args));
        toolRegistry.register("battery", "查看电池/保活状态", schema(), args -> this.extraTools.toolBatteryStatus());
        toolRegistry.register("battery_fix", "尝试设为省电无限制", schema(props(prop("mode", "string", "system/shizuku")), req()), args -> this.extraTools.toolBatteryFix(args));
        toolRegistry.register("shizuku", "查看 Shizuku 状态", schema(), args -> this.extraTools.toolShizukuStatus());
        toolRegistry.register("http", "HTTP 请求与下载", schema(props(
                prop("action", "string", "get|post|put|delete|patch|head|options|json|download_text|download_file|upload；默认 get"),
                prop("method", "string", "可选，等同 action（GET/POST/...）"),
                prop("url", "string", "请求地址"),
                prop("body", "string", "请求体（post/put/patch/delete）"),
                prop("body_file", "string", "从本地文件读取请求体"),
                prop("body_base64", "string", "Base64 请求体"),
                prop("content_type", "string", "内容类型"),
                prop("headers", "object", "可选请求头"),
                prop("include_headers", "boolean", "响应是否包含 headers"),
                prop("follow_redirects", "boolean", "是否跟随重定向，默认 true"),
                prop("connect_timeout", "integer", "连接超时毫秒"),
                prop("read_timeout", "integer", "读取超时毫秒"),
                prop("output", "string", "下载保存路径（download_text/download_file）"),
                prop("file_path", "string", "上传本地文件路径（upload）"),
                prop("field", "string", "multipart 字段名，默认 file"),
                prop("filename", "string", "上传文件名"),
                prop("mime", "string", "上传 MIME，默认 application/octet-stream"),
                prop("form", "object", "upload 附加表单字段"),
                prop("http_method", "string", "json/upload 时的实际 HTTP 方法")
        ), req("url")), args -> this.extraTools.toolHttp(args));
        toolRegistry.register("image", "图片信息与处理", schema(props(
                prop("action", "string", "info|resize|convert|crop|rotate|flip|grayscale|compress|thumbnail|to_base64|from_base64；默认 info"),
                prop("mode", "string", "可选，等同 action"),
                prop("file_path", "string", "图片路径（from_base64 时为输出路径）"),
                prop("output", "string", "输出路径"),
                prop("width", "integer", "宽度（resize/crop）"),
                prop("height", "integer", "高度（resize/crop）"),
                prop("keep_aspect", "boolean", "resize 是否保持比例，默认 true"),
                prop("max_size", "integer", "thumbnail 最长边，默认 256"),
                prop("x", "integer", "裁剪起始 X"),
                prop("y", "integer", "裁剪起始 Y"),
                prop("degrees", "number", "旋转角度"),
                prop("axis", "string", "flip 轴向 horizontal|vertical"),
                prop("quality", "integer", "压缩质量 1-100"),
                prop("content", "string", "Base64 内容（from_base64）")
        ), req()), args -> this.extraTools.toolImage(args));
        toolRegistry.register("shell", "执行 shell 命令", schema(props(
                prop("action", "string", "local|shizuku；默认 local"),
                prop("cmd", "string", "命令"),
                prop("cwd", "string", "工作目录"),
                prop("timeout", "integer", "超时毫秒（shizuku）")
        ), req("cmd")), args -> this.extraTools.toolShellUnified(args));

        // language runtimes (full embedded engines — not shell wrappers)
                toolRegistry.register("runtime", "运行时执行与状态", schema(props(
                prop("action", "string", "info|python|lua|java|cpp|lua_dex|install_cpp；默认 info"),
                prop("lang", "string", "可选，等同 action"),
                prop("code", "string", "源码"),
                prop("source", "string", "code 别名"),
                prop("file_path", "string", "源文件路径"),
                prop("cwd", "string", "工作目录"),
                prop("env", "object", "环境变量/表"),
                prop("main_class", "string", "Java 主类"),
                prop("args", "array", "Java main 参数"),
                prop("output", "string", "lua_dex 输出路径"),
                prop("obfuscate", "boolean", "lua_dex 混淆"),
                prop("std", "string", "C++ 标准"),
                prop("cxxflags", "array", "C++ 编译参数"),
                prop("libs", "array", "C++ 链接库"),
                prop("auto_install", "boolean", "C++ 缺工具链时自动安装"),
                prop("url", "string", "C++ NDK 下载地址"),
                prop("force", "boolean", "强制重装工具链")
        ), req()), args -> this.runtimeTools.toolRuntime(args));

        // apk domain (ApksToApk)
        toolRegistry.register("list_splits", "列出 APKS/XAPK/APKM/zip 容器内的 .apk 分包名", schema(props(prop("file_path", "string", "容器文件路径")), req("file_path")), apkTools::listSplits);
        toolRegistry.register("merge_apks", "合并分包容器或分包目录为单个 APK（默认 profile=agent：自动按设备排除分包）", schema(props(
                prop("file_path", "string", "APKS/XAPK/APKM/zip 路径，与 split_dir 二选一"),
                prop("split_dir", "string", "已解出的分包目录，与 file_path 二选一"),
                prop("output", "string", "输出 APK 路径，可选"),
                prop("profile", "string", "agent|ui|raw，默认 agent"),
                prop("auto_exclude_device", "boolean", "按当前设备排除 ABI/DPI/语言分包；默认随 profile"),
                prop("sign", "boolean", "是否 debug 签名，默认 true"),
                prop("force", "boolean", "versionCode 不一致仍合并，默认 false"),
                prop("auto_edit_manifest", "boolean", "清理 split 字段，默认 true"),
                prop("force_extract_native_libs", "boolean", "强制 extractNativeLibs=true，默认 true"),
                prop("patch_dex", "boolean", "是否跑 DEX 补丁；默认 agent/raw=false，ui=true"),
                prop("clear_methods", "array", "自定义清空方法：字符串 Lcls;->name()V 或 {class,method,proto}"),
                prop("use_default_clear", "boolean", "是否合并默认 DetectionPopup 清空列表"),
                prop("remove_invoke_p", "boolean", "删除 ApplicationMain ->p 调用，默认 true"),
                prop("inject_load_library", "boolean", "Unity onCreate 注入 loadLibrary(Widget)，默认 true"),
                prop("package_name", "string", "可选新包名"),
                prop("version_name", "string", "可选 versionName"),
                prop("version_code", "integer", "可选 versionCode"),
                prop("app_label", "string", "可选应用名"),
                prop("exclude_splits", "array", "要排除的分包名数组"),
                prop("so_paths", "array", "要注入的 .so 本地路径数组"),
                prop("so_abi", "string", "so 注入 ABI，默认 arm64-v8a"),
                prop("verbose_logs", "boolean", "true 时返回完整日志，默认截断")
        ), req()), apkTools::mergeApks);
        toolRegistry.register("patch_apk", "单 APK 改身份：默认真改包(DEX+manifest)；可改版本/应用名/权限/debuggable 等", schema(props(
                prop("file_path", "string", "输入 APK 路径"),
                prop("output", "string", "输出路径，可选"),
                prop("package_name", "string", "新包名"),
                prop("rename_dex", "boolean", "true=真改包DEX+manifest(默认)；false=仅manifest package"),
                prop("version_name", "string", "新 versionName"),
                prop("version_code", "integer", "新 versionCode"),
                prop("app_label", "string", "新应用名（优先写@string资源）"),
                prop("debuggable", "boolean", "设置 android:debuggable"),
                prop("extract_native_libs", "boolean", "设置 extractNativeLibs"),
                prop("patch_dex", "boolean", "跑 DEX 补丁（默认真策略），默认 false"),
                prop("clear_methods", "array", "自定义清空方法：Lcls;->name()V 或 {class,method,proto}"),
                prop("use_default_clear", "boolean", "有 clear_methods 时默认 false；否则随 patch_dex"),
                prop("remove_invoke_p", "boolean", "删除 ApplicationMain ->p；patch_dex 时默认 true"),
                prop("inject_load_library", "boolean", "注入 loadLibrary；patch_dex 时默认 true"),
                prop("add_permissions", "array", "追加 uses-permission 列表"),
                prop("remove_permissions", "array", "删除 uses-permission 列表"),
                prop("sign", "boolean", "是否 debug 签名，默认 true"),
                prop("verbose_logs", "boolean", "true 时返回完整日志")
        ), req("file_path")), apkTools::patchApk);
        toolRegistry.register("rename_package", "真改包：DEX RenameTypes + manifest package/组件绝对名", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("package_name", "string", "新包名"),
                prop("output", "string", "输出路径，可选"),
                prop("sign", "boolean", "是否 debug 签名，默认 true"),
                prop("verbose_logs", "boolean", "完整日志")
        ), req("file_path", "package_name")), apkTools::renamePackage);
        toolRegistry.register("inspect_apk", "只读检查 APK", schema(props(prop("file_path", "string", "APK 路径")), req("file_path")), apkTools::inspectApk);
        toolRegistry.register("list_components", "列出 activity/service/receiver/provider", schema(props(prop("file_path", "string", "APK 路径")), req("file_path")), apkTools::listComponents);
        toolRegistry.register("set_component_name", "设置 application 类名 / MainActivity 类名", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("output", "string", "输出路径，可选"),
                prop("application_class", "string", "Application 全类名"),
                prop("main_activity", "string", "MainActivity 全类名"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path")), apkTools::setComponentName);
        toolRegistry.register("list_permissions", "列出 uses-permission", schema(props(prop("file_path", "string", "APK 路径")), req("file_path")), apkTools::listPermissions);
        toolRegistry.register("edit_permissions", "增减 uses-permission", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("output", "string", "输出路径，可选"),
                prop("add", "array", "追加权限"),
                prop("remove", "array", "删除权限"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path")), apkTools::editPermissions);
        toolRegistry.register("list_metadata", "列出 application meta-data", schema(props(prop("file_path", "string", "APK 路径")), req("file_path")), apkTools::listMetaData);
        toolRegistry.register("edit_metadata", "写/删 application meta-data", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("name", "string", "meta-data android:name"),
                prop("value", "string", "字符串值"),
                prop("remove", "boolean", "true 则删除"),
                prop("output", "string", "输出路径，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path", "name")), apkTools::editMetaData);
        toolRegistry.register("inject_entry", "向 APK 注入/覆盖 zip 条目（文件或文本）", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("entry_path", "string", "zip 内路径，如 assets/a.json"),
                prop("file", "string", "本地文件路径"),
                prop("text", "string", "直接写入的 UTF-8 文本"),
                prop("output", "string", "输出路径，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path", "entry_path")), apkTools::injectEntry);
        toolRegistry.register("remove_entry", "删除 APK 内条目或前缀目录", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("entry_path", "string", "精确条目"),
                prop("prefix", "string", "前缀，如 lib/armeabi-v7a/"),
                prop("output", "string", "输出路径，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path")), apkTools::removeEntry);
        toolRegistry.register("export_manifest_xml", "导出可读 AndroidManifest.xml", schema(props(
                prop("file_path", "string", "APK 路径"),
                prop("output", "string", "写出 xml 路径；省略则回传 xml 字段")
        ), req("file_path")), apkTools::exportManifestXml);
        toolRegistry.register("apply_manifest_xml", "用可读 XML 写回 AndroidManifest", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("xml", "string", "XML 文本"),
                prop("xml_file", "string", "XML 文件路径"),
                prop("output", "string", "输出路径，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path")), apkTools::applyManifestXml);
        toolRegistry.register("patch_dex", "对单 APK 跑 DexPatcher（可自定义清空目标/开关）", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("output", "string", "输出路径，可选"),
                prop("sign", "boolean", "debug 签名，默认 true"),
                prop("clear_methods", "array", "自定义清空：Lcls;->name()V 或 {class,method,proto}"),
                prop("use_default_clear", "boolean", "合并默认 DetectionPopup 列表；有 clear_methods 时默认 false"),
                prop("remove_invoke_p", "boolean", "删除 ApplicationMain ->p，默认 true"),
                prop("inject_load_library", "boolean", "Unity onCreate 注入 loadLibrary(Widget)，默认 true")
        ), req("file_path")), apkTools::patchDex);
        toolRegistry.register("compile_smali", "任意 smali 编译写回 APK（整类替换/注入）", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("smali", "string", "完整 smali 类文本（含 .class）"),
                prop("smali_file", "string", "smali 文件路径，与 smali 二选一"),
                prop("mode", "string", "replace=必须已有类（默认）；upsert=无则注入"),
                prop("dex", "string", "目标 classes*.dex 名，可选"),
                prop("output", "string", "输出 APK 路径，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path")), apkTools::compileSmali);
        toolRegistry.register("export_smali_class", "导出 APK 内单个类为 smali 文本（ARSCLib）", schema(props(
                prop("file_path", "string", "APK 路径"),
                prop("class", "string", "类名：com.foo.Bar 或 Lcom/foo/Bar;"),
                prop("output", "string", "写出 .smali 路径；省略则回传 smali 字段")
        ), req("file_path", "class")), apkTools::exportSmaliClass);
        toolRegistry.register("list_dex", "列出 APK / DEX 内的 classes*.dex 条目", schema(props(
                prop("file_path", "string", "APK 或 .dex 路径")
        ), req("file_path")), apkTools::listDex);
        toolRegistry.register("list_dex_classes", "列出 DEX 中的类（可筛选）", schema(props(
                prop("file_path", "string", "APK 或 .dex 路径"),
                prop("dex", "string", "APK 内 dex 条目名，默认第一个"),
                prop("filter", "string", "类名子串筛选"),
                prop("query", "string", "filter 别名"),
                prop("limit", "integer", "最多返回条数，默认 2000")
        ), req("file_path")), apkTools::listDexClasses);
        toolRegistry.register("decompile_smali", "baksmali 反编译单个类为 smali", schema(props(
                prop("file_path", "string", "APK 或 .dex 路径"),
                prop("class", "string", "类名"),
                prop("dex", "string", "APK 内 dex 条目，可选"),
                prop("output", "string", "写出 .smali；省略则回传 smali 字段")
        ), req("file_path", "class")), apkTools::decompileSmali);
        toolRegistry.register("decompile_java", "jadx 反编译单个类为 Java（失败回退 smali）", schema(props(
                prop("file_path", "string", "APK 或 .dex 路径"),
                prop("class", "string", "类名"),
                prop("dex", "string", "APK 内 dex 条目，可选"),
                prop("output", "string", "写出 .java；省略则回传 java 字段")
        ), req("file_path", "class")), apkTools::decompileJava);
        toolRegistry.register("extract_dex", "从 APK 抽出单个 classes*.dex 文件", schema(props(
                prop("file_path", "string", "APK 路径"),
                prop("dex", "string", "条目名，默认 classes.dex"),
                prop("output", "string", "输出 .dex 路径，可选")
        ), req("file_path")), apkTools::extractDex);
        toolRegistry.register("export_smali_dir", "导出某个 DEX 为 smali 目录树", schema(props(
                prop("file_path", "string", "APK 路径"),
                prop("dex", "string", "dex 条目，默认第一个"),
                prop("output", "string", "输出目录，可选")
        ), req("file_path")), apkTools::exportSmaliDir);
        toolRegistry.register("import_smali_dir", "将 smali 目录整包编译写回 APK", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("smali_dir", "string", "含 .smali 的目录"),
                prop("dir", "string", "smali_dir 别名"),
                prop("mode", "string", "replace|upsert，默认 upsert"),
                prop("dex", "string", "优先写入的 classes*.dex"),
                prop("output", "string", "输出 APK，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path")), apkTools::importSmaliDir);
        toolRegistry.register("rename_dex_method", "跨 DEX 重命名方法（含引用，尽力）", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("class", "string", "类名"),
                prop("old_method", "string", "原方法名"),
                prop("new_method", "string", "新方法名"),
                prop("proto", "string", "方法原型，如 ()V；可省略则按名匹配"),
                prop("output", "string", "输出 APK，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path", "class", "old_method", "new_method")), apkTools::renameDexMethod);
        toolRegistry.register("rename_dex_field", "跨 DEX 重命名字段（含引用，尽力）", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("class", "string", "类名"),
                prop("old_field", "string", "原字段名"),
                prop("new_field", "string", "新字段名"),
                prop("field_type", "string", "字段类型描述符，可选"),
                prop("output", "string", "输出 APK，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path", "class", "old_field", "new_field")), apkTools::renameDexField);
        toolRegistry.register("clear_dex_methods", "仅清空指定方法体为 return-void（不跑默认侧策略）", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("clear_methods", "array", "Lcls;->name()V 或 {class,method,proto}"),
                prop("output", "string", "输出 APK，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path", "clear_methods")), apkTools::clearDexMethods);
        toolRegistry.register("set_string_res", "写 resources.arsc 字符串资源", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("name", "string", "资源名，如 app_name"),
                prop("value", "string", "新字符串"),
                prop("type", "string", "类型，默认 string"),
                prop("output", "string", "输出路径，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path", "name", "value")), apkTools::setStringRes);
        toolRegistry.register("sanitize_apk", "清理 split 字段并 force extractNativeLibs=true", schema(props(
                prop("file_path", "string", "输入 APK"),
                prop("output", "string", "输出路径，可选"),
                prop("sign", "boolean", "debug 签名，默认 true")
        ), req("file_path")), apkTools::sanitizeApk);
        toolRegistry.register("sign_apk", "使用内嵌 debug 密钥签名 APK", schema(props(prop("file_path", "string", "输入 APK"), prop("output", "string", "输出路径，可选")), req("file_path")), apkTools::signApk);
        toolRegistry.register("verify_apk", "校验 APK 签名", schema(props(prop("file_path", "string", "APK 路径")), req("file_path")), apkTools::verifyApk);
        // keep both: shizuku install + FileProvider install
        toolRegistry.register("install_apk_shizuku", "使用 Shizuku 安装 APK", schema(props(prop("file_path", "string", "APK 文件路径"), prop("replace", "boolean", "是否覆盖安装，默认 true"), prop("grant_all", "boolean", "安装时授予所有运行时权限，默认 false")), req("file_path")), args -> this.extraTools.toolInstallApk(args));
        toolRegistry.register("install_apk", "通过 FileProvider 调起系统安装器安装 APK", schema(props(prop("file_path", "string", "APK 路径")), req("file_path")), apkTools::installApk);
        toolRegistry.register("export_apk", "复制 APK 到目标路径", schema(props(prop("file_path", "string", "源 APK"), prop("output", "string", "目标路径")), req("file_path", "output")), apkTools::exportApk);
        toolRegistry.register("hash_file", "计算文件 MD5 / SHA-256", schema(props(prop("file_path", "string", "文件路径")), req("file_path")), apkTools::hashFile);
    }

    synchronized void start() throws Exception {
        if (running) return;
        McpKtorTransport ktor = new McpKtorTransport(port, this);
        ktor.start();
        transport = ktor;
        running = true;
    }

    synchronized void stop() {
        running = false;
        McpKtorTransport ktor = transport;
        transport = null;
        if (ktor != null) {
            try {
                ktor.stop();
            } catch (Exception ignored) {
            }
        }
    }

    boolean isRunning() { return running; }
    int getPort() { return port; }

    /** Package-visible for Ktor transport: POST /mcp body → JSON-RPC response. */
    String dispatch(String body) {
        JsonElement element;
        try {
            element = JsonParser.parseString(body);
        } catch (Exception e) {
            return errorResponse(null, -32700, "JSON 解析失败: " + e.getMessage());
        }
        if (element.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    output.add(JsonParser.parseString(errorResponse(null, -32600, "批量请求中存在无效项")));
                } else {
                    output.add(JsonParser.parseString(dispatchOne(item.getAsJsonObject())));
                }
            }
            return gson.toJson(output);
        }
        if (!element.isJsonObject()) return errorResponse(null, -32600, "无效请求");
        return dispatchOne(element.getAsJsonObject());
    }

    private String dispatchOne(JsonObject request) {
        JsonElement id = request.get("id");
        String method = request.has("method") ? request.get("method").getAsString() : "";
        if (method.length() == 0) return errorResponse(id, -32600, "缺少 method");
        try {
            if ("initialize".equals(method)) {
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", "2024-11-05");
                JsonObject capabilities = new JsonObject();
                capabilities.add("tools", new JsonObject());
                capabilities.add("resources", new JsonObject());
                capabilities.add("prompts", new JsonObject());
                result.add("capabilities", capabilities);
                JsonObject serverInfo = new JsonObject();
                serverInfo.addProperty("name", "apkstoapk-mcp");
                serverInfo.addProperty("version", "1.0.0");
                result.add("serverInfo", serverInfo);
                return successResponse(id, result);
            }
            if ("ping".equals(method)) return successResponse(id, new JsonObject());
            if ("tools/list".equals(method)) {
                JsonObject result = new JsonObject();
                result.add("tools", toolRegistry.listTools());
                return successResponse(id, result);
            }
            if ("resources/list".equals(method)) {
                JsonObject result = new JsonObject();
                result.add("resources", new JsonArray());
                return successResponse(id, result);
            }
            if ("prompts/list".equals(method)) {
                JsonObject result = new JsonObject();
                result.add("prompts", new JsonArray());
                return successResponse(id, result);
            }
            if ("tools/call".equals(method)) {
                JsonObject params = request.has("params") && request.get("params").isJsonObject()
                        ? request.getAsJsonObject("params") : new JsonObject();
                String name = params.has("name") ? params.get("name").getAsString() : "";
                JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                        ? params.getAsJsonObject("arguments") : new JsonObject();
                McpService.addLog("tools/call " + name);
                JsonObject toolResult = toolRegistry.call(name, arguments);
                JsonObject contentItem = new JsonObject();
                contentItem.addProperty("type", "text");
                contentItem.addProperty("text", this.resultTrimmer.trim(name, toolResult));
                JsonArray content = new JsonArray();
                content.add(contentItem);
                JsonObject result = new JsonObject();
                result.add("content", content);
                result.add("structuredContent", toolResult);
                return successResponse(id, result);
            }
            return errorResponse(id, -32601, "未知方法:" + method);
        } catch (Exception e) {
            McpService.addLog("tools error: " + e.getMessage());
            return errorResponse(id, -32000, e.getMessage());
        }
    }

    /** Package-visible for Ktor transport: GET /mcp health payload. */
    String healthJson() {
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.addProperty("name", "ApksToApkMcp");
        result.addProperty("port", port);
        result.addProperty("url", "http://127.0.0.1:" + port + "/mcp");
        result.addProperty("running", running);
        result.addProperty("tools", toolRegistry.enabledSize());
        result.addProperty("tools_registered", toolRegistry.size());
        if (capabilityStore != null) {
            result.addProperty("preset", capabilityStore.getPreset().id);
            result.addProperty("capabilities", capabilityStore.summaryText(toolRegistry.allNames()));
        }
        result.addProperty("transport", "ktor-cio");
        return gson.toJson(result);
    }

    private String successResponse(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return gson.toJson(response);
    }

    private String errorResponse(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message == null ? "unknown error" : message);
        response.add("error", error);
        return gson.toJson(response);
    }

    private JsonObject toolHealth() {
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.addProperty("port", port);
        result.addProperty("running", running);
        result.addProperty("url", "http://127.0.0.1:" + port + "/mcp");
        result.addProperty("tools", toolRegistry.enabledSize());
        result.addProperty("tools_registered", toolRegistry.size());
        result.addProperty("work_dir", this.pathTools.getWorkDir().getAbsolutePath());
        result.addProperty("self_check", McpService.getSelfCheckText());
        if (capabilityStore != null) {
            result.addProperty("preset", capabilityStore.getPreset().id);
            result.addProperty("capabilities", capabilityStore.summaryText(toolRegistry.allNames()));
        }
        result.addProperty("transport", "ktor-cio");
        return result;
    }

    private JsonObject toolHelpUnified(JsonObject args) {
        String action = "";
        if (args != null) {
            if (args.has("action") && !args.get("action").isJsonNull()) {
                action = args.get("action").getAsString();
            } else if (args.has("topic") && !args.get("topic").isJsonNull()) {
                action = args.get("topic").getAsString();
            }
        }
        if (action == null) action = "";
        action = action.trim().toLowerCase(java.util.Locale.US);
        if (action.length() == 0 || "tool".equals(action) || "tools".equals(action) || "overview".equals(action)) {
            return toolHelp();
        }
        if ("file".equals(action) || "files".equals(action)) {
            return fileHelp();
        }
        if ("system".equals(action) || "sys".equals(action)) {
            return systemHelp();
        }
        JsonObject result = new JsonObject();
        result.addProperty("content", "未知 help action: " + action + "（可用 tool/file/system）");
        return result;
    }

    private JsonObject toolHelp() {
        String preset = capabilityStore != null ? capabilityStore.getPreset().id : "agent";
        JsonObject result = new JsonObject();
        result.addProperty("content",
                "ApksToApk MCP\\n\\n"
                        + "当前预设：" + preset
                        + "（agent≈33 / full=分类内全部 / safe≈31 无安装与 eval）\\n"
                        + "tools/list 只返回当前可见工具；切换预设在 App「MCP」页。\\n\\n"
                        + "Agent 精简主路径：\\n"
                        + "- 文件：pwd/ls/read/write/find/grep/code_replace/batch_ops\\n"
                        + "- APK：list_splits / merge_apks / patch_apk / inspect_apk\\n"
                        + "  export_manifest_xml / apply_manifest_xml / sign_apk / verify_apk / hash_file\\n"
                        + "- 安装：install_apk\\n"
                        + "- 编排：runtime action=python（长尾逻辑交给 Python）\\n\\n"
                        + "完整预设下额外能力：\n"
                        + "- help(action=tool|file|system)\n"
                        + "- APK 细粒度：rename_package / components / meta / entry / patch_dex / clear_dex_methods\n"
                        + "- DEX：list_dex / list_dex_classes / decompile_smali / decompile_java / extract_dex\n"
                        + "  export_smali_dir / import_smali_dir / compile_smali / export_smali_class\n"
                        + "  rename_dex_method / rename_dex_field\n"
                        + "- 运行时：runtime（python/lua/java/cpp/lua_dex）\n"
                        + "- 高风险：shell / shizuku_shell / delete（danger 分类默认关）\n");
        return result;
    }

    private JsonObject fileHelp() {
        JsonObject result = new JsonObject();
        result.addProperty("content",
                "文件工具\\n\\n"
                        + "路径：pwd / cd / set_root / exists / stat / ls / tree\\n"
                        + "读取：read / head / tail / read_lines / batch_read / read_base64 / find / grep\\n"
                        + "写入：write / append / touch / empty / mkdir / copy / rename / edit / write_base64\\n\\n"
                        + "code_replace\\n"
                        + "提示：/data 等私有目录会在 Shizuku 可用时尝试提权访问。");
        return result;
    }

    private JsonObject systemHelp() {
        JsonObject result = new JsonObject();
        result.addProperty("content",
                "系统工具\\n\\n"
                        + "状态：health / service_info / history / clear_log / battery / shizuku\\n"
                        + "高风险：shell / shizuku_shell / install_apk_shizuku / delete / battery_fix\\n");
        return result;
    }

    private JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.add("required", new JsonArray());
        return schema;
    }

    private JsonObject schema(JsonObject properties, JsonArray required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    private JsonObject prop(String name, String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        JsonObject wrapper = new JsonObject();
        wrapper.add(name, property);
        return wrapper;
    }

    private JsonObject props(JsonObject... items) {
        JsonObject result = new JsonObject();
        for (JsonObject item : items) {
            for (String key : item.keySet()) result.add(key, item.get(key));
        }
        return result;
    }

    private JsonArray req(String... names) {
        JsonArray array = new JsonArray();
        for (String name : names) array.add(name);
        return array;
    }

    JsonObject callToolObject(String name, JsonObject arguments) throws Exception {
        return this.toolRegistry.call(name, arguments == null ? new JsonObject() : arguments);
    }

    String prettyJson(JsonElement element) {
        return new Gson().newBuilder().setPrettyPrinting().create().toJson(element);
    }
}
