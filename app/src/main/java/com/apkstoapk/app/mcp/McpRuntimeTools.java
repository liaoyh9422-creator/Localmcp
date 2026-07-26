package com.apkstoapk.app.mcp;

import android.content.Context;

import com.apkstoapk.app.runtime.CppRuntime;
import com.apkstoapk.app.runtime.JavaRuntime;
import com.apkstoapk.app.runtime.LuaRuntime;
import com.apkstoapk.app.runtime.LuaToDexRuntime;
import com.apkstoapk.app.runtime.PythonRuntime;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for embedded language runtimes
 * (Lua / CPython3 / Java / C++).
 * Full interpreters/compilers — not shell wrappers.
 */
final class McpRuntimeTools {
    private final Context context;
    private final McpPathTools pathTools;

    McpRuntimeTools(Context context, McpPathTools pathTools) {
        this.context = context.getApplicationContext();
        this.pathTools = pathTools;
    }

    JsonObject toolRuntime(JsonObject args) throws Exception {
        String action = "";
        if (args != null) {
            if (args.has("action") && !args.get("action").isJsonNull()) {
                action = args.get("action").getAsString();
            } else if (args.has("lang") && !args.get("lang").isJsonNull()) {
                action = args.get("lang").getAsString();
            } else if (args.has("language") && !args.get("language").isJsonNull()) {
                action = args.get("language").getAsString();
            }
        }
        if (action == null) action = "";
        action = action.trim().toLowerCase(java.util.Locale.US);
        if (action.length() == 0) {
            action = "info";
        }
        if ("info".equals(action) || "status".equals(action)) {
            return runtimeInfo(args == null ? new JsonObject() : args);
        }
        if ("python".equals(action) || "py".equals(action)) {
            return evalPython(args == null ? new JsonObject() : args);
        }
        if ("lua".equals(action)) {
            return evalLua(args == null ? new JsonObject() : args);
        }
        if ("java".equals(action)) {
            return evalJava(args == null ? new JsonObject() : args);
        }
        if ("cpp".equals(action) || "c++".equals(action) || "cxx".equals(action)) {
            return evalCpp(args == null ? new JsonObject() : args);
        }
        if ("lua_dex".equals(action) || "luadex".equals(action)) {
            return compileLuaDex(args == null ? new JsonObject() : args);
        }
        if ("install_cpp".equals(action) || "cpp_toolchain".equals(action)) {
            return installCppToolchain(args == null ? new JsonObject() : args);
        }
        throw new Exception("未知 runtime action: " + action
                + "（可用 info/python/lua/java/cpp/lua_dex/install_cpp）");
    }

    JsonObject runtimeInfo(JsonObject args) {
        JsonObject result = new JsonObject();
        result.addProperty("lua", "LuaJ 3.0.1 (Lua 5.2 VM, in-process)");
        result.addProperty("python", "Chaquopy CPython 3.11 (native libpython)");
        result.addProperty("java", "Janino 3.1.12 + R8/D8 + InMemoryDexClassLoader");
        result.addProperty("cpp", "Real Clang++ NDK (compile PIE ELF) + linker64 exec");
        result.addProperty("lua2dex", "LuaJ LuaJC (.class) + R8/D8 (.dex)");
        try {
            result.addProperty("python_version", PythonRuntime.version(context));
            result.addProperty("python_ready", true);
        } catch (Throwable t) {
            result.addProperty("python_ready", false);
            result.addProperty("python_error", t.getMessage());
        }
        result.addProperty("lua_ready", true);
        result.addProperty("java_ready", true);

        CppRuntime.ToolchainInfo cpp = CppRuntime.info(context);
        result.addProperty("cpp_ready", cpp.ready);
        result.addProperty("cpp_host_abi", cpp.hostAbi);
        if (cpp.clangxx != null) result.addProperty("cpp_clangxx", cpp.clangxx);
        if (cpp.root != null) result.addProperty("cpp_toolchain_root", cpp.root);
        if (cpp.message != null) result.addProperty("cpp_message", cpp.message);
        return result;
    }

    JsonObject evalLua(JsonObject args) throws Exception {
        String code = optCode(args);
        String path = optString(args, "file_path");
        String cwd = optCwd(args);
        Map<String, String> env = optEnv(args);
        LuaRuntime.Result r;
        if (path != null && !path.isEmpty()) {
            File f = pathTools.resolve(path);
            r = LuaRuntime.evalFile(f, cwd != null ? cwd : f.getParent(), env);
        } else {
            if (code == null) throw new IllegalArgumentException("code 或 path 至少提供一个");
            r = LuaRuntime.eval(code, cwd, env);
        }
        return luaResult(r);
    }

    JsonObject evalPython(JsonObject args) throws Exception {
        String code = optCode(args);
        String path = optString(args, "file_path");
        String cwd = optCwd(args);
        Map<String, String> env = optEnv(args);
        PythonRuntime.Result r;
        if (path != null && !path.isEmpty()) {
            File f = pathTools.resolve(path);
            r = PythonRuntime.evalFile(context, f, cwd != null ? cwd : f.getParent(), env);
        } else {
            if (code == null) throw new IllegalArgumentException("code 或 path 至少提供一个");
            r = PythonRuntime.eval(context, code, cwd, env);
        }
        return pythonResult(r);
    }

    JsonObject evalJava(JsonObject args) throws Exception {
        String code = optCode(args);
        String path = optString(args, "file_path");
        String mainClass = optString(args, "main_class");
        String cwd = optCwd(args);
        String[] argv = optStringArray(args, "args");
        JavaRuntime.Result r;
        if (path != null && !path.isEmpty()) {
            File f = pathTools.resolve(path);
            r = JavaRuntime.evalFile(context, f, mainClass, argv,
                    cwd != null ? cwd : f.getParent());
        } else {
            if (code == null) throw new IllegalArgumentException("code 或 path 至少提供一个");
            r = JavaRuntime.eval(context, code, mainClass, argv, cwd, optEnv(args));
        }
        return javaResult(r);
    }

    JsonObject installCppToolchain(JsonObject args) throws Exception {
        String url = optString(args, "url");
        String path = optString(args, "file_path");
        boolean force = args != null && args.has("force") && !args.get("force").isJsonNull()
                && args.get("force").getAsBoolean();
        File local = null;
        if (path != null && !path.isEmpty()) {
            local = pathTools.resolve(path);
        }
        CppRuntime.ToolchainInfo info = CppRuntime.install(context, url, local, force);
        JsonObject o = new JsonObject();
        o.addProperty("ok", info.ready);
        o.addProperty("ready", info.ready);
        o.addProperty("host_abi", info.hostAbi);
        if (info.root != null) o.addProperty("toolchain_root", info.root);
        if (info.clangxx != null) o.addProperty("clangxx", info.clangxx);
        if (info.message != null) o.addProperty("message", info.message);
        return o;
    }

    /**
     * Compile .lua → dex (LuaJC + D8). MCP 仅暴露编译，不暴露加载。
     * args: path|code|source, output?, obfuscate?=false
     */
    JsonObject compileLuaDex(JsonObject args) throws Exception {
        String path = optString(args, "file_path");
        String code = optCode(args);
        boolean obfuscate = args != null && args.has("obfuscate")
                && !args.get("obfuscate").isJsonNull()
                && args.get("obfuscate").getAsBoolean();
        String outPath = optString(args, "output");

        File outDex = null;
        if (outPath != null && !outPath.isEmpty()) {
            outDex = pathTools.resolve(outPath);
        }

        LuaToDexRuntime.CompileResult cr;
        if (path != null && !path.isEmpty()) {
            File f = pathTools.resolve(path);
            cr = LuaToDexRuntime.compileLuaToDex(context, f, obfuscate, outDex);
        } else if (code != null) {
            cr = LuaToDexRuntime.compileLuaSourceToDex(
                    context, code, "chunk.lua", obfuscate, outDex);
        } else {
            throw new IllegalArgumentException("path 或 code 至少提供一个");
        }

        JsonObject o = new JsonObject();
        o.addProperty("ok", cr.ok);
        o.addProperty("runtime", "lua2dex");
        if (cr.dexPath != null) o.addProperty("dex_path", cr.dexPath);
        o.addProperty("log", cr.log == null ? "" : cr.log);
        o.addProperty("elapsed_ms", cr.elapsedMs);
        JsonArray arr = new JsonArray();
        if (cr.classNames != null) {
            for (String c : cr.classNames) {
                arr.add(c);
            }
        }
        o.add("class_names", arr);
        return o;
    }

    JsonObject evalCpp(JsonObject args) throws Exception {
        String code = optCode(args);
        String path = optString(args, "file_path");
        String cwd = optCwd(args);
        String std = optString(args, "std");
        List<String> cxxflags = optStringList(args, "cxxflags");
        List<String> libs = optStringList(args, "libs");
        Map<String, String> env = optEnv(args);

        // auto-install toolchain if missing and host is aarch64
        CppRuntime.ToolchainInfo ti = CppRuntime.info(context);
        if (!ti.ready) {
            boolean auto = args == null || !args.has("auto_install")
                    || args.get("auto_install").isJsonNull()
                    || args.get("auto_install").getAsBoolean();
            if (auto && ti.hostAbi != null
                    && (ti.hostAbi.contains("arm64") || ti.hostAbi.contains("aarch64"))) {
                ti = CppRuntime.install(context, optString(args, "url"), false);
            }
        }
        if (!ti.ready) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", false);
            o.addProperty("runtime", "cpp");
            o.addProperty("stderr", ti.message);
            o.addProperty("cpp_ready", false);
            return o;
        }

        CppRuntime.Result r;
        if (path != null && !path.isEmpty()) {
            File f = pathTools.resolve(path);
            r = CppRuntime.evalFile(context, f, cwd != null ? cwd : f.getParent(),
                    cxxflags, libs, std, env);
        } else {
            if (code == null) throw new IllegalArgumentException("code 或 path 至少提供一个");
            r = CppRuntime.eval(context, code, cwd, cxxflags, libs, std, env);
        }
        return cppResult(r);
    }

    private static JsonObject luaResult(LuaRuntime.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok);
        o.addProperty("runtime", "lua");
        o.addProperty("stdout", r.stdout == null ? "" : r.stdout);
        o.addProperty("stderr", r.stderr == null ? "" : r.stderr);
        if (r.returnValue != null) o.addProperty("return_value", r.returnValue);
        o.addProperty("elapsed_ms", r.elapsedMs);
        return o;
    }

    private static JsonObject pythonResult(PythonRuntime.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok);
        o.addProperty("runtime", "python3");
        o.addProperty("stdout", r.stdout == null ? "" : r.stdout);
        o.addProperty("stderr", r.stderr == null ? "" : r.stderr);
        if (r.returnValue != null) o.addProperty("return_value", r.returnValue);
        if (r.version != null) o.addProperty("version", r.version);
        o.addProperty("elapsed_ms", r.elapsedMs);
        return o;
    }

    private static JsonObject javaResult(JavaRuntime.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok);
        o.addProperty("runtime", "java");
        o.addProperty("stdout", r.stdout == null ? "" : r.stdout);
        o.addProperty("stderr", r.stderr == null ? "" : r.stderr);
        if (r.returnValue != null) o.addProperty("return_value", r.returnValue);
        if (r.mainClass != null) o.addProperty("main_class", r.mainClass);
        o.addProperty("elapsed_ms", r.elapsedMs);
        return o;
    }

    private static JsonObject cppResult(CppRuntime.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok);
        o.addProperty("runtime", "cpp");
        o.addProperty("stdout", r.stdout == null ? "" : r.stdout);
        o.addProperty("stderr", r.stderr == null ? "" : r.stderr);
        o.addProperty("exit_code", r.exitCode);
        if (r.compiler != null) o.addProperty("compiler", r.compiler);
        if (r.binary != null) o.addProperty("binary", r.binary);
        o.addProperty("compile_ms", r.compileMs);
        o.addProperty("run_ms", r.runMs);
        o.addProperty("elapsed_ms", r.elapsedMs);
        return o;
    }

    private String optCwd(JsonObject args) throws Exception {
        String cwd = optString(args, "cwd");
        if (cwd == null || cwd.isEmpty()) {
            return pathTools.getWorkDir().getAbsolutePath();
        }
        File f = pathTools.resolve(cwd);
        return f.getAbsolutePath();
    }

    private static String optCode(JsonObject args) {
        if (args == null) return null;
        if (args.has("code") && !args.get("code").isJsonNull()) {
            return args.get("code").getAsString();
        }
        if (args.has("source") && !args.get("source").isJsonNull()) {
            return args.get("source").getAsString();
        }
        return null;
    }

    private static String optString(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return null;
        try {
            return args.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> optEnv(JsonObject args) {
        Map<String, String> map = new HashMap<>();
        if (args == null || !args.has("env") || !args.get("env").isJsonObject()) return map;
        JsonObject env = args.getAsJsonObject("env");
        for (String k : env.keySet()) {
            try {
                map.put(k, env.get(k).isJsonNull() ? null : env.get(k).getAsString());
            } catch (Exception ignored) {
            }
        }
        return map;
    }

    private static String[] optStringArray(JsonObject args, String key) {
        List<String> list = optStringList(args, key);
        return list.toArray(new String[0]);
    }

    private static List<String> optStringList(JsonObject args, String key) {
        List<String> out = new ArrayList<>();
        if (args == null || !args.has(key) || !args.get(key).isJsonArray()) {
            return out;
        }
        com.google.gson.JsonArray arr = args.getAsJsonArray(key);
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i) != null && !arr.get(i).isJsonNull()) {
                out.add(arr.get(i).getAsString());
            }
        }
        return out;
    }
}
