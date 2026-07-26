# ApksToApk

> A comprehensive on-device APK processing toolkit with built-in MCP protocol service, enabling remote AI Agent control.

[**📖 中文版本**](./README_CN.md)

## Table of Contents

- [Overview](#overview)
- [System Architecture](#system-architecture)
- [Module Details](#module-details)
  - [UI Layer](#1-ui-layer-compose)
  - [MCP Service Layer](#2-mcp-service-layer)
  - [Core Ops Layer](#3-core-ops-layer)
  - [Runtime Layer](#4-runtime-layer)
  - [File Access Layer](#5-file-access-layer)
- [Dependencies](#dependencies)
- [Build & Run](#build--run)
- [MCP API Reference](#mcp-api-reference)
- [Project Structure](#project-structure)

---

## Overview

**ApksToApk** is an Android-based APK multi-function processing tool. It leverages **Shizuku** for system-level privileges and exposes **70+ tool interfaces** via the **MCP (Model Context Protocol)** protocol, enabling AI agents to remotely perform APK analysis, modification, signing, multi-language code injection, and more.

### Core Capabilities

| Domain | Features |
|--------|----------|
| 📦 **APK Merging** | APKS/XAPK/APKM → single APK with automatic device-adaptive split selection |
| ✍️ **APK Signing** | V1/V2/V3/V4 signing, verification, signature block dump/load |
| 🔍 **DEX Analysis** | jadx decompile to Java, baksmali/smali assemble/disassemble, DEX renaming |
| 📝 **Manifest Editing** | Package name, permissions, components, meta-data, uses-feature CRUD |
| 🔧 **Resource Management** | ARSC string resource read/write, resource file add/remove, icon replacement |
| 🚀 **Code Injection** | Lua/Java/Python/C++ multi-language runtime injection, Lua→DEX compilation pipeline |
| 🔌 **MCP Service** | Ktor CIO HTTP transport, JSON-RPC 2.0, preset whitelists (AGENT/FULL/SAFE) |

---

## System Architecture

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

### Data Flow

1. **User / AI Agent** → HTTP POST `127.0.0.1:8800/mcp` (JSON-RPC)
2. **McpKtorTransport** → parse request → **McpServer.dispatch()**
3. **McpServer** → **McpToolRegistry** → lookup tool → execute
4. Tools invoke underlying **Ops** classes (file ops can escalate via Shizuku)
5. Results formatted by **McpResultTrimmer** and returned

---

## Module Details

### 1. UI Layer (Compose)

| File | Responsibility |
|------|---------------|
| `MainActivity.kt` | Compose host, holds Logger, Executor, McpBootstrap, handles Intent |
| `AppRoot.kt` | Top-level route dispatch, tab navigation, real-time MCP status/log monitoring |
| `AppNav.kt` | Route definitions: `Home/Mcp/Log/Settings/Merge/Lua2Dex/Manifest` |
| `HomeScreen.kt` | Home page, tool card entry points |
| `MergeScreen.kt` / `MergeRoute.kt` | APK merge wizard (select file → progress → result) |
| `ManifestScreen.kt` / `ManifestRoute.kt` | Manifest XML editor with syntax highlighting |
| `Lua2DexScreen.kt` / `Lua2DexRoute.kt` | Lua→DEX compilation interface |
| `McpScreen.kt` | MCP service management (start/stop/port/preset/capability toggles) |
| `SettingsScreen.kt` | Shizuku status, battery optimization, permission management |

### 2. MCP Service Layer

#### Core Classes

| Class | Responsibility |
|-------|---------------|
| `McpService` | Android Foreground Service, manages MCP lifecycle, holds log buffer |
| `McpServer` | JSON-RPC 2.0 server: `initialize`, `tools/list`, `tools/call`, `health` |
| `McpBootstrap` | Entry point, Shizuku init → storage permission → start McpService |
| `McpKtorTransport` | Ktor CIO HTTP transport, binds `127.0.0.1`, GET=health, POST=mcp |
| `McpToolRegistry` | Tool registration center, dynamic filtering by capability preset |
| `McpCapabilityStore` | Capability preset management, three modes:

| Preset | Description |
|--------|-------------|
| `AGENT` | Whitelist mode, only exposes specified tools |
| `FULL` | Exposes all tools |
| `SAFE` | Excludes dangerous operations (e.g., shell execution) |

#### Tool Groups

| Tool Class | Count | Responsibility |
|------------|-------|---------------|
| `McpApkTools` | ~18 | APK merge, sign, DEX, Smali, Manifest operations |
| `McpPathTools` | ~6 | pwd/cd/set_root/exists/stat/ls path navigation |
| `McpReadTools` | ~8 | read/head/tail/find/grep/tree/read_base64 |
| `McpWriteTools` | ~9 | write/append/mkdir/touch/copy/rename/edit/code_replace |
| `McpShellTools` | ~3 | Local shell, Shizuku shell |
| `McpRuntimeTools` | ~5 | Lua/Python/Java/C++/Lua2Dex dispatch |
| `McpExtraTools` | ~8 | batch_ops, file_text, shell_unified, image/archive, etc. |
| `McpSystemCompat` | ~5 | Battery optimization, Shizuku status, APK install |

### 3. Core Ops Layer

#### 3.1 APK Merging

| Class | Responsibility |
|-------|---------------|
| `ApksMerger` | **Core merger**: unpack → extract base + splits → process Manifest → sign. Supports apks/xapk/apkm/zip |
| `MergeOps` | Directory-based merge operations (no UI dependency), for MCP invocation |
| `MergeResult` | Merge result DTO: outputApk, signed, logs, elapsedMs |

**Merge Workflow**:
```
Input APKS → SplitOps extract → SplitSelector choose matching splits
           → ApksMerger merge → ManifestOps process Manifest
           → SignOps sign → Output APK
```

#### 3.2 Signing & Verification

| Class | Responsibility |
|-------|---------------|
| `SignOps` | Signing entry, supports debug/PKCS12/BKS keystores |
| `SignHelper` | Low-level apksig implementation, uses embedded `debug23.keystore` |
| `VerifyOps` | V1/V2/V3/V31/V4 signature verification |
| `SignatureBlockOps` | APK signature block dump/load/clear |

#### 3.3 Split Handling

| Class | Responsibility |
|-------|---------------|
| `SplitOps` | Split container extraction (apks/xapk/apkm/zip) |
| `SplitFilterOps` | Filter splits by device characteristics or token |
| `SplitSelector` | Smart split selection: matches ABI (arm64-v8a/armeabi-v7a/x86_64), Density (xxhdpi/mdpi), Language (zh/en) |
| `ModuleSanitizeOps` | Split Manifest cleanup + extractNativeLibs policy adjustment |

#### 3.4 DEX Operations

| Class | Responsibility |
|-------|---------------|
| `DexBrowserOps` | baksmali → smali disassembly, jadx → Java decompile |
| `DexOps` | DEX file base operations |
| `DexPatcher` | DEX patch injection |
| `DexRenameOps` | DEX type/package renaming (paired with PackageRenameOps) |
| `SmaliCompileOps` | **Smali compile & apply**: replace/upsert modes, reflective compatibility with multiple ARSCLib APIs, 60KB+ core code |

#### 3.5 Manifest Operations

| Class | Responsibility |
|-------|---------------|
| `ManifestOps` | Low-level manifest operations: package name, version, Application, MainActivity, SDK versions |
| `ManifestXmlOps` | XML-level manifest read/write |
| `ManifestWorkflow` | High-level workflow composing multiple manifest operations |
| `PermissionOps` | Batch permission management (addAll/removeAll/replaceAll) |
| `MetaDataOps` | `<meta-data>` element read/write |
| `ComponentOps` | Activity/Service/Receiver/Provider component query & export settings |
| `UsesFeatureOps` | `<uses-feature>` element management |

#### 3.6 Package Renaming

| Class | Responsibility |
|-------|---------------|
| `PackageRenameOps` | **Full package rename**: DEX type refs + Manifest package + absolute path refs complete rewrite |
| `EntryOps` | Entry Activity adjustment |

#### 3.7 Resource Operations

| Class | Responsibility |
|-------|---------------|
| `ResFileOps` | Resource file management (list/get/remove/refreshTable) |
| `StringResOps` | resources.arsc string resource read/write |
| `IconOps` | App icon replacement |

#### 3.8 Other

| Class | Responsibility |
|-------|---------------|
| `SoInjector` | `.so` file injection (Uri/File → `lib/arm64-v8a/`) |
| `ApkInspect` | APK structure inspection (zip entries, signature status, split type) |
| `FileHashOps` | File hash computation |
| `InstallOps` / `InstallHelper` | APK installation (via Shizuku) |
| `FrameworkHelper` | Android framework resource reference helper |
| `InputApkResolver` | Unified input path resolution |
| `AndroidAttrNames` | Android attribute name constants |

### 4. Runtime Layer

#### 4.1 Multi-Language Runtimes

| Class | Language | Implementation |
|-------|----------|---------------|
| `LuaRuntime` | Lua 5.2 | LuaJ 3.0.1 in-process interpreter, supports sandboxing |
| `JavaRuntime` | Java | Janino compile `.java` → R8/D8 → `.dex` → InMemoryDexClassLoader |
| `PythonRuntime` | Python 3.11 | Chaquopy CPython, native `libpython3.11.so` |
| `CppRuntime` | C++ | NDK clang++ compile → PIE ELF → `Runtime.exec()` |

**CppRuntime Workflow**:
```
Source .cpp → Download/extract NDK (android-ndk-r29-aarch64.7z)
            → clang++ -std=c++17 -fPIE -pie → PIE ELF
            → Runtime.exec(elf_path) → capture stdout/stderr
```

#### 4.2 Lua → DEX Compilation Pipeline

| Class | Responsibility |
|-------|---------------|
| `LuaToDexRuntime` | Compilation pipeline orchestrator: LuaJC → .class → D8 → DexClassLoader |
| `AggLuajCompat` | ASM class rewriting: LuaInteger→LuaLong, GG-modified luaj compatibility |
| `GgLuajTarget` | Target enum: `STOCK` (standard LuaJ), `MODDED_GG` (GG-modified) |
| `LuaClassObfuscator` | ASM class name obfuscation: entry classes preserved, helpers → `_a`/`_b` short names |
| `LuaStringObfuscator` | Compile-time string literal obfuscation (XOR key + decode function preamble) |
| `LuaSourceExtras` | Source-level obfuscation: A1 number splitting, A3 dead code injection, A5 long string splitting |

### 5. File Access Layer

| Class | Privilege Level | Responsibility |
|-------|-----------------|---------------|
| `McpPathTools` | Normal | pwd, cd, set_root, exists, stat, ls |
| `McpReadTools` | Normal | read, head, tail, find, grep, tree, read_base64 |
| `McpWriteTools` | Normal | write, append, mkdir, touch, copy, rename, edit |
| `ShizukuFileAccess` | Shizuku | Read private directory files via Shizuku |
| `ShizukuPrivilegedFileOps` | Shizuku | Full file ops (stat/list/read/write/mkdir/delete/copy/rename/find/grep/tree), bypassing Android sandbox |
| `FileAccessHelper` | - | Permission checking, exception normalization, path safety validation |

---

## Dependencies

### Gradle Plugins

| Plugin | Version | Purpose |
|--------|---------|---------|
| Android Application | 8.7.3 | Android build |
| Kotlin Android | 2.0.21 | Kotlin compilation |
| Kotlin Compose | 2.0.21 | Compose compiler |
| Chaquopy Python | 16.0.0 | Python runtime embedding |

### Core Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| **Compose BOM** | 2024.10.01 | Jetpack Compose UI |
| **Material3** | - | Material Design 3 |
| **Shizuku API** | 13.1.5 | System-level privilege escalation |
| **Shizuku Provider** | 13.1.5 | Shizuku ContentProvider |
| **Ktor CIO Server** | 2.3.13 | MCP HTTP transport layer |
| **ARSCLib** | 1.3.9 (local JAR) | resources.arsc parsing/editing |
| **LuaJ** | 3.0.1 | Lua 5.2 runtime |
| **BCEL** | 6.5.0 | Java bytecode manipulation |
| **ASM** | 9.7 | Bytecode editing (obfuscation/compat) |
| **Janino** | 3.1.12 | Java source compilation |
| **R8** | 8.7.18 | DEX conversion/optimization |
| **jadx-core** | 1.5.1 | DEX → Java decompilation |
| **smali** | 3.0.8 | smali/baksmali assemble/disassemble |
| **Guava** | 33.0.0-android | General utilities |
| **Gson** | 2.10.1 | JSON serialization |

### Embedded Third-Party Libraries (source-level)

| Package | Source | Purpose |
|---------|--------|---------|
| `com.android.apksig` | AOSP apksig | APK V1-V4 signing/verification |
| `com.aefyr.pseudoapksigner` | pseudoapksigner | Lightweight signing implementation |
| `com.reandroid.apkeditor` | APKEditor | Manifest helper |

---

## Build & Run

### Requirements

- **Android Studio**: Hedgehog (2023.1.1) or later
- **JDK**: 17+
- **Gradle**: 8.7+
- **Target Device**: Android 7.0+ (API 24), ARM64

### Build Steps

```bash
# 1. Clone repository
git clone https://github.com/liaoyh9422-creator/Localmcp.git
cd Localmcp

# 2. Build Debug APK
./gradlew assembleDebug

# 3. APK locations
# app/build/outputs/apk/debug/app-debug.apk
# dist/app-debug.apk          (auto-copied)
# dist/ApksToApk_v1.0.0.apk  (versioned)
```

### Runtime Requirements

1. **Install Shizuku** and start its service
2. Install ApksToApk APK
3. First launch: authorize Shizuku → grant storage permission
4. MCP service auto-starts on `127.0.0.1:8800`

### MCP Endpoint

```
GET  http://127.0.0.1:8800/mcp    → Health check
POST http://127.0.0.1:8800/mcp    → JSON-RPC 2.0
```

### Debug Keystore

- Keystore: `app/debug.keystore.p12` (PKCS12)
- Password / Alias password: `android`
- Alias: `androiddebugkey`

---

## MCP API Reference

### Initialize

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

### List Tools

```json
{"jsonrpc":"2.0","method":"tools/list","params":{},"id":2}
```

### Call Tool

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "<tool_name>",
    "arguments": {}
  },
  "id": 3
}
```

### Key Tool Categories

| Category | Representative Tools | Description |
|----------|---------------------|-------------|
| Path | `pwd`, `cd`, `set_root`, `ls`, `stat` | Working directory navigation, set_root switches sandbox root |
| Read | `read`, `head`, `tail`, `find`, `grep`, `tree` | File content reading and searching |
| Write | `write`, `append`, `mkdir`, `copy`, `rename`, `edit` | File creation and modification |
| Shell | `shell` (local/shizuku) | Command execution, action=shizuku for privilege escalation |
| APK | `merge`, `sign`, `verify`, `split_list` | APK merging, signing, verification, split preview |
| DEX | `dex_browse`, `smali_compile`, `dex_rename` | DEX browsing, Smali compilation, renaming |
| Manifest | `manifest_get`, `manifest_edit`, `permission_*`, `component_*` | Manifest CRUD operations |
| Runtime | `lua_run`, `python_run`, `java_run`, `cpp_run`, `lua2dex` | Multi-language code execution |

---

## Project Structure

```
ApksToApk/
├── app/
│   ├── build.gradle.kts          # App-level build config (deps, signing, Chaquopy)
│   ├── libs/
│   │   ├── ARSCLib-1.3.9.jar     # ARSC resource parsing library
│   │   └── ARSCLib-1.3.9.zip     # ARSCLib source
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/                   # Resources (icons, themes, strings)
│       └── java/com/
│           ├── apkstoapk/app/
│           │   ├── core/          # ★ Core Ops Layer (22+ classes)
│           │   │   ├── ApksMerger.java         # APK merging
│           │   │   ├── SignOps.java            # Signing
│           │   │   ├── VerifyOps.java          # Signature verification
│           │   │   ├── SplitOps.java           # Split extraction
│           │   │   ├── SmaliCompileOps.java    # Smali compilation
│           │   │   ├── DexBrowserOps.java      # DEX browsing
│           │   │   ├── ManifestOps.java        # Manifest editing
│           │   │   ├── PermissionOps.java      # Permission management
│           │   │   ├── PackageRenameOps.java   # Package renaming
│           │   │   ├── SoInjector.java         # SO injection
│           │   │   └── ...                     # More Ops classes
│           │   ├── mcp/           # ★ MCP Service Layer (18 classes)
│           │   │   ├── McpServer.java          # JSON-RPC server
│           │   │   ├── McpService.java         # Foreground Service
│           │   │   ├── McpBootstrap.java       # Bootstrap launcher
│           │   │   ├── McpKtorTransport.kt     # HTTP transport
│           │   │   ├── McpToolRegistry.java    # Tool registry
│           │   │   ├── McpCapabilityStore.java # Capability presets
│           │   │   ├── McpApkTools.java        # APK tools
│           │   │   ├── McpPathTools.java       # Path tools
│           │   │   ├── McpReadTools.java       # Read tools
│           │   │   ├── McpWriteTools.java      # Write tools
│           │   │   ├── McpShellTools.java      # Shell tools
│           │   │   ├── McpRuntimeTools.java    # Runtime tools
│           │   │   ├── McpExtraTools.java      # Extra tools
│           │   │   ├── McpSystemCompat.java    # System compat
│           │   │   ├── McpResultTrimmer.java   # Result formatting
│           │   │   ├── ShizukuFileAccess.java  # Shizuku file access
│           │   │   └── ShizukuPrivilegedFileOps.java
│           │   ├── runtime/       # ★ Runtime Layer (10 classes)
│           │   │   ├── LuaRuntime.java         # Lua 5.2
│           │   │   ├── JavaRuntime.java        # Java compile & exec
│           │   │   ├── PythonRuntime.java      # CPython 3.11
│           │   │   ├── CppRuntime.java         # C++ compile & exec
│           │   │   ├── LuaToDexRuntime.java    # Lua→DEX compiler
│           │   │   ├── AggLuajCompat.java      # GG-modified compat
│           │   │   ├── GgLuajTarget.java       # Target enum
│           │   │   ├── LuaClassObfuscator.java # Class name obfuscation
│           │   │   ├── LuaStringObfuscator.java# String obfuscation
│           │   │   └── LuaSourceExtras.java    # Source obfuscation
│           │   ├── ui/            # UI Layer (Compose)
│           │   │   ├── MainActivity.kt
│           │   │   ├── editor/                  # Editor (syntax highlight, validation)
│           │   │   └── compose/
│           │   │       ├── AppRoot.kt
│           │   │       ├── AppNav.kt
│           │   │       ├── components/CommonUi.kt
│           │   │       └── screens/             # Individual screens
│           │   └── util/
│           │       ├── IoUtils.java
│           │       └── SimpleApkLogger.java
│           ├── android/apksig/    # AOSP apksig (signing library)
│           ├── aefyr/pseudoapksigner/  # Lightweight signing
│           └── reandroid/apkeditor/    # Manifest helper
├── build.gradle.kts              # Root build config
├── settings.gradle.kts           # Project settings (repos, Chaquopy resolution)
├── gradle/
├── gradle.properties
├── gradlew / gradlew.bat
└── local.properties.example
```

---

## Technical Highlights

1. **Deep Shizuku Integration**: Full filesystem bypass via Shizuku UserService + newProcess reflection, breaking through Android 11+ storage sandbox restrictions
2. **MCP Protocol**: JSON-RPC 2.0 standard, Ktor CIO async HTTP transport, security-graded preset whitelists (AGENT/FULL/SAFE)
3. **Multi-Language Runtime Chain**: Lua→DEX compilation pipeline with source obfuscation (number splitting, dead code injection, string XOR) + bytecode obfuscation (class names, strings), adapted for GG-modified LuaJ
4. **Device-Aware Split Selection**: Three-dimensional matching across ABI/Density/Language, automatically selecting the optimal split combination for the current device
5. **Embedded Signing Engine**: Full AOSP apksig library (V1-V4), complete APK signing and verification without external tools
