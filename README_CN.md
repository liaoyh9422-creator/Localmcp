# ApksToApk

> Android 设备上的全能 APK 处理工具包，内置 MCP 协议服务，支持 AI Agent 远程操控。

[**📖 English Version**](./README.md)

## 目录

- [项目概述](#项目概述)
- [系统架构](#系统架构)
- [模块详解](#模块详解)
  - [UI 层](#1-ui-层-compose)
  - [MCP 服务层](#2-mcp-服务层)
  - [核心操作层](#3-核心操作层-core-ops)
  - [运行时层](#4-运行时层-runtime)
  - [文件访问层](#5-文件访问层)
- [依赖关系](#依赖关系)
- [构建与运行](#构建与运行)
- [MCP API 参考](#mcp-api-参考)
- [项目结构](#项目结构)

---

## 项目概述

**ApksToApk** 是一个运行在 Android 设备上的 APK 多功能处理工具，通过 **Shizuku** 获取系统级权限，以 **MCP (Model Context Protocol)** 协议对外暴露 70+ 工具接口，支持 AI Agent 远程执行 APK 分析、修改、签名、多语言代码注入等操作。

### 核心能力

| 领域 | 功能 |
|------|------|
| 📦 **APK 合并** | APKS/XAPK/APKM → 单一 APK，自动设备适配分包选择 |
| ✍️ **APK 签名** | V1/V2/V3/V4 签名、验证、签名块 dump/load |
| 🔍 **DEX 分析** | jadx 反编译为 Java、baksmali/smali 汇编/反汇编、DEX 重命名 |
| 📝 **Manifest 编辑** | 包名、权限、组件、meta-data、uses-feature 增删改查 |
| 🔧 **资源管理** | ARSC 字符串资源读写、资源文件增删、图标替换 |
| 🚀 **代码注入** | Lua/Java/Python/C++ 多语言运行时注入，Lua→DEX 编译管线 |
| 🔌 **MCP 服务** | Ktor CIO HTTP 传输，JSON-RPC 2.0，预设白名单（AGENT/FULL/SAFE） |

---

## 系统架构

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer (Compose)                   │
│  MainActivity → AppRoot → Home/Merge/Manifest/Lua2Dex/   │
│                           Mcp/Log/Settings                │
├─────────────────────────────────────────────────────────┤
│                    MCP Service Layer                      │
│  McpService (Foreground) → McpServer (JSON-RPC)          │
│       ↓                                                   │
│  McpKtorTransport (HTTP CIO, 127.0.0.1:8800)            │
│       ↓                                                   │
│  McpToolRegistry ← McpCapabilityStore (AGENT/FULL/SAFE)  │
├──────────┬──────────┬──────────┬──────────┬──────────────┤
│ McpApk   │ McpPath  │ McpRead  │ McpWrite │ McpShell     │
│ Tools    │ Tools    │ Tools    │ Tools    │ Tools        │
├──────────┴──────────┴──────────┴──────────┴──────────────┤
│                   Core Ops Layer                          │
│  Merge │ Sign │ Verify │ Split │ DEX │ Manifest │ Res    │
│  Package │ Component │ Permission │ SoInject │ ...       │
├─────────────────────────────────────────────────────────┤
│                  Runtime Layer                            │
│  LuaRuntime │ JavaRuntime │ PythonRuntime │ CppRuntime   │
│  LuaToDexRuntime (Lua→DEX compilation pipeline)          │
├─────────────────────────────────────────────────────────┤
│                File Access Layer                          │
│  Standard IO  ←→  ShizukuPrivilegedFileOps (root bypass) │
├─────────────────────────────────────────────────────────┤
│              Third-party Libraries                        │
│  apksig │ pseudoapksigner │ ARSCLib │ APKEditor           │
└─────────────────────────────────────────────────────────┘
```

### 数据流

1. **用户 / AI Agent** → HTTP POST `127.0.0.1:8800/mcp`（JSON-RPC）
2. **McpKtorTransport** → 解析请求 → **McpServer.dispatch()**
3. **McpServer** → **McpToolRegistry** → 查找工具 → 执行
4. 工具调用底层 **Ops** 类（文件操作可通过 Shizuku 提权）
5. 结果经 **McpResultTrimmer** 格式化后原路返回

---

## 模块详解

### 1. UI 层 (Compose)

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | Compose 宿主，持有 Logger、Executor、McpBootstrap，处理 Intent |
| `AppRoot.kt` | 顶层路由分发，Tab 导航，MCP 状态/日志实时监听 |
| `AppNav.kt` | 路由定义：`Home/Mcp/Log/Settings/Merge/Lua2Dex/Manifest` |
| `HomeScreen.kt` | 主页，工具卡片入口 |
| `MergeScreen.kt` / `MergeRoute.kt` | APK 合并向导（选择文件 → 进度 → 结果） |
| `ManifestScreen.kt` / `ManifestRoute.kt` | Manifest XML 编辑器 + 语法高亮 |
| `Lua2DexScreen.kt` / `Lua2DexRoute.kt` | Lua→DEX 编译界面 |
| `McpScreen.kt` | MCP 服务管理（启动/停止/端口/预设/能力开关） |
| `SettingsScreen.kt` | Shizuku 状态、电池优化、权限管理 |

### 2. MCP 服务层

#### 核心类

| 类 | 职责 |
|----|------|
| `McpService` | Android Foreground Service，管理 MCP 生命周期，持有日志缓冲区 |
| `McpServer` | JSON-RPC 2.0 服务器：`initialize`、`tools/list`、`tools/call`、`health` |
| `McpBootstrap` | 启动入口，Shizuku 初始化 → 存储权限 → 启动 McpService |
| `McpKtorTransport` | Ktor CIO HTTP 传输，绑定 `127.0.0.1`，GET=health，POST=mcp |
| `McpToolRegistry` | 工具注册中心，根据能力预设动态过滤 |
| `McpCapabilityStore` | 能力预设管理，三种模式：

| 预设 | 说明 |
|------|------|
| `AGENT` | 白名单模式，仅开放指定工具 |
| `FULL` | 开放全部工具 |
| `SAFE` | 排除危险操作（如 shell 执行） |

#### 工具分组

| 工具类 | 数量 | 职责 |
|--------|------|------|
| `McpApkTools` | ~18 | APK 合并、签名、DEX、Smali、Manifest 操作 |
| `McpPathTools` | ~6 | pwd/cd/set_root/exists/stat/ls 路径导航 |
| `McpReadTools` | ~8 | read/head/tail/find/grep/tree/read_base64 |
| `McpWriteTools` | ~9 | write/append/mkdir/touch/copy/rename/edit/code_replace |
| `McpShellTools` | ~3 | 本地 Shell、Shizuku Shell |
| `McpRuntimeTools` | ~5 | Lua/Python/Java/C++/Lua2Dex 调度 |
| `McpExtraTools` | ~8 | batch_ops、file_text、shell_unified、图片/压缩等 |
| `McpSystemCompat` | ~5 | 电池优化、Shizuku 状态、安装 APK |

### 3. 核心操作层 (Core Ops)

#### 3.1 APK 合并

| 类 | 职责 |
|----|------|
| `ApksMerger` | **核心合并器**：解包 → 提取 base + 分包 → 处理 Manifest → 签名。支持 apks/xapk/apkm/zip |
| `MergeOps` | 基于目录的合并操作（无 UI 依赖），供 MCP 调用 |
| `MergeResult` | 合并结果 DTO：outputApk、signed、logs、elapsedMs |

**合并流程**：
```
输入 APKS → SplitOps 提取 → SplitSelector 选择合适分包
         → ApksMerger 合并 → ManifestOps 处理 Manifest
         → SignOps 签名 → 输出 APK
```

#### 3.2 签名与验证

| 类 | 职责 |
|----|------|
| `SignOps` | 签名入口，支持 debug/PKCS12/BKS 密钥 |
| `SignHelper` | 底层 apksig 实现，使用内嵌 `debug23.keystore` |
| `VerifyOps` | V1/V2/V3/V31/V4 签名验证 |
| `SignatureBlockOps` | APK 签名块 dump/load/clear |

#### 3.3 分包处理

| 类 | 职责 |
|----|------|
| `SplitOps` | 分包容器解包（apks/xapk/apkm/zip） |
| `SplitFilterOps` | 按设备特征或 token 过滤分包 |
| `SplitSelector` | 智能分包选择：匹配 ABI（arm64-v8a/armeabi-v7a/x86_64）、Density（xxhdpi/mdpi）、Language（zh/en） |
| `ModuleSanitizeOps` | 分包 Manifest 清理 + extractNativeLibs 策略调整 |

#### 3.4 DEX 操作

| 类 | 职责 |
|----|------|
| `DexBrowserOps` | baksmali → smali 反汇编、jadx → Java 反编译 |
| `DexOps` | DEX 文件基础操作 |
| `DexPatcher` | DEX 补丁注入 |
| `DexRenameOps` | DEX 类型/包名重命名（配合 PackageRenameOps） |
| `SmaliCompileOps` | **Smali 编译应用**：replace/upsert 模式，反射兼容多种 ARSCLib API，60KB+ 核心代码 |

#### 3.5 Manifest 操作

| 类 | 职责 |
|----|------|
| `ManifestOps` | 低级 Manifest 操作：包名、版本、Application、MainActivity、SDK 版本 |
| `ManifestXmlOps` | XML 级别的 Manifest 读写 |
| `ManifestWorkflow` | 组合多个 Manifest 操作的高级工作流 |
| `PermissionOps` | 批量权限管理（addAll/removeAll/replaceAll） |
| `MetaDataOps` | `<meta-data>` 元素读写 |
| `ComponentOps` | Activity/Service/Receiver/Provider 组件查询与导出设置 |
| `UsesFeatureOps` | `<uses-feature>` 元素管理 |

#### 3.6 包名重命名

| 类 | 职责 |
|----|------|
| `PackageRenameOps` | **真正改包名**：DEX 类型引用 + Manifest 包名 + 绝对路径引用全量重写 |
| `EntryOps` | 入口 Activity 调整 |

#### 3.7 资源操作

| 类 | 职责 |
|----|------|
| `ResFileOps` | 资源文件管理（list/get/remove/refreshTable） |
| `StringResOps` | resources.arsc 字符串资源读写 |
| `IconOps` | 应用图标替换 |

#### 3.8 其他

| 类 | 职责 |
|----|------|
| `SoInjector` | `.so` 文件注入（Uri/File → `lib/arm64-v8a/`） |
| `ApkInspect` | APK 结构检查（zip entries、签名状态、分包类型） |
| `FileHashOps` | 文件哈希计算 |
| `InstallOps` / `InstallHelper` | APK 安装（通过 Shizuku） |
| `FrameworkHelper` | Android 框架资源引用辅助 |
| `InputApkResolver` | 统一输入路径解析 |
| `AndroidAttrNames` | Android 属性名常量 |

### 4. 运行时层 (Runtime)

#### 4.1 多语言运行时

| 类 | 语言 | 实现方式 |
|----|------|----------|
| `LuaRuntime` | Lua 5.2 | LuaJ 3.0.1 进程内解释器，支持沙箱 |
| `JavaRuntime` | Java | Janino 编译 `.java` → R8/D8 → `.dex` → InMemoryDexClassLoader |
| `PythonRuntime` | Python 3.11 | Chaquopy CPython，原生 `libpython3.11.so` |
| `CppRuntime` | C++ | NDK clang++ 编译 → PIE ELF → `Runtime.exec()` |

**CppRuntime 工作流**：
```
源码 .cpp → 下载/解压 NDK (android-ndk-r29-aarch64.7z)
         → clang++ -std=c++17 -fPIE -pie → PIE ELF
         → Runtime.exec(elf_path) → 捕获 stdout/stderr
```

#### 4.2 Lua → DEX 编译管线

| 类 | 职责 |
|----|------|
| `LuaToDexRuntime` | 编译管线主控：LuaJC → .class → D8 → DexClassLoader |
| `AggLuajCompat` | ASM 类重写：LuaInteger→LuaLong，GG 魔改 luaj 兼容 |
| `GgLuajTarget` | 目标枚举：`STOCK`（标准 LuaJ）、`MODDED_GG`（GG 魔改版） |
| `LuaClassObfuscator` | ASM 类名混淆：入口类保留，辅助类 → `_a`/`_b` 等短名 |
| `LuaStringObfuscator` | 字符串字面量编译期混淆（XOR key + 解码函数 preamble） |
| `LuaSourceExtras` | 源码级混淆：A1 数字拆分、A3 死代码注入、A5 长字符串分割 |

### 5. 文件访问层

| 类 | 权限级别 | 职责 |
|----|----------|------|
| `McpPathTools` | 普通 | pwd、cd、set_root、exists、stat、ls |
| `McpReadTools` | 普通 | read、head、tail、find、grep、tree、read_base64 |
| `McpWriteTools` | 普通 | write、append、mkdir、touch、copy、rename、edit |
| `ShizukuFileAccess` | Shizuku | 通过 Shizuku 读取私有目录文件 |
| `ShizukuPrivilegedFileOps` | Shizuku | 完整文件操作（stat/list/read/write/mkdir/delete/copy/rename/find/grep/tree），绕过 Android 沙箱 |
| `FileAccessHelper` | - | 权限检查、异常规范化、路径安全校验 |

---

## 依赖关系

### Gradle 插件

| 插件 | 版本 | 用途 |
|------|------|------|
| Android Application | 8.7.3 | Android 构建 |
| Kotlin Android | 2.0.21 | Kotlin 编译 |
| Kotlin Compose | 2.0.21 | Compose 编译器 |
| Chaquopy Python | 16.0.0 | Python 运行时嵌入 |

### 核心依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| **Compose BOM** | 2024.10.01 | Jetpack Compose UI |
| **Material3** | - | Material Design 3 |
| **Shizuku API** | 13.1.5 | 系统级权限提权 |
| **Shizuku Provider** | 13.1.5 | Shizuku ContentProvider |
| **Ktor CIO Server** | 2.3.13 | MCP HTTP 传输层 |
| **ARSCLib** | 1.3.9 (本地 JAR) | resources.arsc 解析/编辑 |
| **LuaJ** | 3.0.1 | Lua 5.2 运行时 |
| **BCEL** | 6.5.0 | Java 字节码操作 |
| **ASM** | 9.7 | 字节码编辑（混淆/兼容） |
| **Janino** | 3.1.12 | Java 源码编译 |
| **R8** | 8.7.18 | DEX 转换/优化 |
| **jadx-core** | 1.5.1 | DEX → Java 反编译 |
| **smali** | 3.0.8 | smali/baksmali 汇编/反汇编 |
| **Guava** | 33.0.0-android | 通用工具库 |
| **Gson** | 2.10.1 | JSON 序列化 |

### 内嵌第三方库（源码级）

| 包 | 来源 | 用途 |
|----|------|------|
| `com.android.apksig` | AOSP apksig | APK V1-V4 签名/验证 |
| `com.aefyr.pseudoapksigner` | pseudoapksigner | 轻量签名实现 |
| `com.reandroid.apkeditor` | APKEditor | Manifest 辅助 |

---

## 构建与运行

### 环境要求

- **Android Studio**: Hedgehog (2023.1.1) 或更高
- **JDK**: 17+
- **Gradle**: 8.7+
- **目标设备**: Android 7.0+ (API 24)，ARM64

### 构建步骤

```bash
# 1. 克隆仓库
git clone https://github.com/liaoyh9422-creator/Localmcp.git
cd Localmcp

# 2. 构建 Debug APK
./gradlew assembleDebug

# 3. APK 位置
# app/build/outputs/apk/debug/app-debug.apk
# dist/app-debug.apk          (自动复制)
# dist/ApksToApk_v1.0.0.apk  (带版本号)
```

### 运行要求

1. **安装 Shizuku** 并启动服务
2. 安装 ApksToApk APK
3. 首次启动：授权 Shizuku → 授权存储权限
4. MCP 服务自动在 `127.0.0.1:8800` 启动

### MCP 端点

```
GET  http://127.0.0.1:8800/mcp    → 健康检查
POST http://127.0.0.1:8800/mcp    → JSON-RPC 2.0
```

### 调试密钥

- 密钥库：`app/debug.keystore.p12`（PKCS12）
- 密码/别名密码：`android`
- 别名：`androiddebugkey`

---

## MCP API 参考

### 初始化

```json
{
  "jsonrpc": "2.0",
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {"name": "my-agent", "version": "1.0.0"}
  },
  "id": 1
}
```

### 列出工具

```json
{"jsonrpc":"2.0","method":"tools/list","params":{},"id":2}
```

### 调用工具

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "<工具名>",
    "arguments": {}
  },
  "id": 3
}
```

### 主要工具分类

| 类别 | 典型工具 | 说明 |
|------|----------|------|
| 路径 | `pwd`、`cd`、`set_root`、`ls`、`stat` | 工作目录导航，set_root 可切换沙箱根 |
| 读取 | `read`、`head`、`tail`、`find`、`grep`、`tree` | 文件内容读取与搜索 |
| 写入 | `write`、`append`、`mkdir`、`copy`、`rename`、`edit` | 文件创建与修改 |
| Shell | `shell` (local/shizuku) | 命令执行，action=shizuku 可提权 |
| APK | `merge`、`sign`、`verify`、`split_list` | APK 合并、签名、验证、分包预览 |
| DEX | `dex_browse`、`smali_compile`、`dex_rename` | DEX 浏览、Smali 编译、重命名 |
| Manifest | `manifest_get`、`manifest_edit`、`permission_*`、`component_*` | Manifest 增删改查 |
| 运行时 | `lua_run`、`python_run`、`java_run`、`cpp_run`、`lua2dex` | 多语言代码执行 |

---

## 项目结构

```
ApksToApk/
├── app/
│   ├── build.gradle.kts          # 应用级构建配置（依赖、签名、Chaquopy）
│   ├── libs/
│   │   ├── ARSCLib-1.3.9.jar     # ARSC 资源解析库
│   │   └── ARSCLib-1.3.9.zip     # ARSCLib 源码
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/                   # 资源文件（图标、主题、字符串）
│       └── java/com/
│           ├── apkstoapk/app/
│           │   ├── core/          # ★ 核心操作层（22+ 类）
│           │   │   ├── ApksMerger.java         # APK 合并
│           │   │   ├── SignOps.java            # 签名
│           │   │   ├── VerifyOps.java          # 签名验证
│           │   │   ├── SplitOps.java           # 分包解包
│           │   │   ├── SmaliCompileOps.java    # Smali 编译
│           │   │   ├── DexBrowserOps.java      # DEX 浏览
│           │   │   ├── ManifestOps.java        # Manifest 编辑
│           │   │   ├── PermissionOps.java      # 权限管理
│           │   │   ├── PackageRenameOps.java   # 包名重命名
│           │   │   ├── SoInjector.java         # SO 注入
│           │   │   └── ...                     # 更多 Ops 类
│           │   ├── mcp/           # ★ MCP 服务层（18 类）
│           │   │   ├── McpServer.java          # JSON-RPC 服务器
│           │   │   ├── McpService.java         # Foreground Service
│           │   │   ├── McpBootstrap.java       # 启动引导
│           │   │   ├── McpKtorTransport.kt     # HTTP 传输
│           │   │   ├── McpToolRegistry.java    # 工具注册
│           │   │   ├── McpCapabilityStore.java # 能力预设
│           │   │   ├── McpApkTools.java        # APK 工具集
│           │   │   ├── McpPathTools.java       # 路径工具
│           │   │   ├── McpReadTools.java       # 读取工具
│           │   │   ├── McpWriteTools.java      # 写入工具
│           │   │   ├── McpShellTools.java      # Shell 工具
│           │   │   ├── McpRuntimeTools.java    # 运行时工具
│           │   │   ├── McpExtraTools.java      # 扩展工具
│           │   │   ├── McpSystemCompat.java    # 系统兼容
│           │   │   ├── McpResultTrimmer.java   # 结果格式化
│           │   │   ├── ShizukuFileAccess.java  # Shizuku 文件访问
│           │   │   └── ShizukuPrivilegedFileOps.java
│           │   ├── runtime/       # ★ 运行时层（10 类）
│           │   │   ├── LuaRuntime.java         # Lua 5.2
│           │   │   ├── JavaRuntime.java        # Java 编译执行
│           │   │   ├── PythonRuntime.java      # CPython 3.11
│           │   │   ├── CppRuntime.java         # C++ 编译执行
│           │   │   ├── LuaToDexRuntime.java    # Lua→DEX 编译
│           │   │   ├── AggLuajCompat.java      # GG 魔改兼容
│           │   │   ├── GgLuajTarget.java       # 目标枚举
│           │   │   ├── LuaClassObfuscator.java # 类名混淆
│           │   │   ├── LuaStringObfuscator.java# 字符串混淆
│           │   │   └── LuaSourceExtras.java    # 源码混淆
│           │   ├── ui/            # UI 层（Compose）
│           │   │   ├── MainActivity.kt
│           │   │   ├── editor/                  # 编辑器（语法高亮、校验）
│           │   │   └── compose/
│           │   │       ├── AppRoot.kt
│           │   │       ├── AppNav.kt
│           │   │       ├── components/CommonUi.kt
│           │   │       └── screens/             # 各页面
│           │   └── util/
│           │       ├── IoUtils.java
│           │       └── SimpleApkLogger.java
│           ├── android/apksig/    # AOSP apksig（签名库）
│           ├── aefyr/pseudoapksigner/  # 轻量签名
│           └── reandroid/apkeditor/    # Manifest 辅助
├── build.gradle.kts              # 根构建配置
├── settings.gradle.kts           # 项目设置（仓库、Chaquopy 解析）
├── gradle/
├── gradle.properties
├── gradlew / gradlew.bat
└── local.properties.example
```

---

## 技术亮点

1. **Shizuku 深度集成**：通过 Shizuku UserService + newProcess 反射实现完整的文件系统绕过，突破 Android 11+ 的存储沙箱限制
2. **MCP 协议**：JSON-RPC 2.0 标准，Ktor CIO HTTP 异步传输，支持预设白名单模式（AGENT/FULL/SAFE）的安全分级
3. **多语言运行时链**：Lua→DEX 编译管线支持源码混淆（数字拆分、死代码注入、字符串 XOR）+ 字节码混淆（类名、字符串），适配 GG 魔改 LuaJ
4. **设备感知的分包选择**：ABI/Density/Language 三维匹配，自动选出最适合当前设备的分包组合
5. **内嵌签名引擎**：完整 AOSP apksig 库（V1-V4），无需外部工具即可完成 APK 签名与验证
