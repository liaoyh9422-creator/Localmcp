package com.apkstoapk.app.runtime;

import android.content.Context;
import android.os.Build;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.luaj.vm2.luajc.LuaJC;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import dalvik.system.DexClassLoader;
import dalvik.system.InMemoryDexClassLoader;

/**
 * AGG-style pipeline (same architecture, our own implementation):
 * <pre>
 *   .lua  --LuaJC--&gt;  .class bytes  --D8--&gt;  .dex plugin  --DexClassLoader--&gt; run
 * </pre>
 * Not a reimplementation of AGG private code; uses public LuaJ LuaJC + R8/D8.
 */
public final class LuaToDexRuntime {
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private LuaToDexRuntime() {}

    public static final class CompileResult {
        public final boolean ok;
        public final String dexPath;
        public final List<String> classNames;
        public final String log;
        public final long elapsedMs;

        public CompileResult(boolean ok, String dexPath, List<String> classNames,
                             String log, long elapsedMs) {
            this.ok = ok;
            this.dexPath = dexPath;
            this.classNames = classNames;
            this.log = log;
            this.elapsedMs = elapsedMs;
        }
    }

    public static final class LoadResult {
        public final boolean ok;
        public final String message;
        public final String returnValue;
        public final long elapsedMs;

        public LoadResult(boolean ok, String message, String returnValue, long elapsedMs) {
            this.ok = ok;
            this.message = message;
            this.returnValue = returnValue;
            this.elapsedMs = elapsedMs;
        }
    }

    /**
     * Compile Lua source file to a dex plugin.
     *
     * @param obfuscate if true: string literal protect + random chunk + ASM class rename
     * @param outDex    output .dex path; null → cache dir auto name
     */
    public static CompileResult compileLuaToDex(
            Context context,
            File luaFile,
            boolean obfuscate,
            File outDex
    ) {
        return compileLuaToDex(context, luaFile, obfuscate, outDex, GgLuajTarget.STOCK);
    }

    /**
     * @param target STOCK = org.luaj.vm2; MODDED_GG = luaj short names (Nqfes etc.)
     */
    public static CompileResult compileLuaToDex(
            Context context,
            File luaFile,
            boolean obfuscate,
            File outDex,
            GgLuajTarget target
    ) {
        long t0 = System.currentTimeMillis();
        StringBuilder log = new StringBuilder();
        List<String> classNames = new ArrayList<>();
        File work = null;
        try {
            if (luaFile == null || !luaFile.isFile()) {
                return new CompileResult(false, null, classNames,
                        "lua file missing: " + luaFile, 0);
            }
            Context app = context.getApplicationContext();
            work = new File(app.getCacheDir(), "lua2dex_" + SEQ.getAndIncrement());
            File classDir = new File(work, "classes");
            File dexDir = new File(work, "dex");
            if (!classDir.mkdirs() || !dexDir.mkdirs()) {
                throw new IllegalStateException("Cannot create work dir " + work);
            }

            byte[] luaBytes = readFile(luaFile);
            // Host (GG 等) 通常 loadClass(dex文件名不含.dex)，入口类名必须对齐
            String entryClass = resolveEntryClassName(luaFile, outDex, obfuscate);
            log.append("entryClass=").append(entryClass).append('\n');
            if (target == null) target = GgLuajTarget.STOCK;
            log.append("target=").append(target.name()).append('\n');

            // 混淆时：A1/A3/A5 → 字符串保护；chunk 用入口类名
            if (obfuscate) {
                String src = new String(luaBytes, StandardCharsets.UTF_8);
                LuaSourceExtras.Result ex = LuaSourceExtras.apply(src);
                log.append(ex.log == null ? "" : ex.log);
                src = ex.source;
                LuaStringObfuscator.Result so = LuaStringObfuscator.obfuscate(src);
                log.append(so.log == null ? "" : so.log);
                luaBytes = so.source.getBytes(StandardCharsets.UTF_8);
                try {
                    writeFile(new File(work, "obf_input.lua"), luaBytes);
                } catch (Throwable ignored) {
                }
            }
            // 主 chunk 名 = 入口类名.lua（与 AGG 的 speed.lua → Lspeed 一致）
            String chunkName = entryClass + ".lua";
            String fileName = obfuscate ? chunkName : luaFile.getAbsolutePath();
            log.append("obfuscate=").append(obfuscate).append('\n');
            log.append("chunk=").append(chunkName).append('\n');

            if (obfuscate) {
                try {
                    Method m = LuaJC.class.getMethod("initObfuscator", Globals.class);
                    Globals g = JsePlatform.standardGlobals();
                    m.invoke(LuaJC.instance, g);
                    log.append("initObfuscator: ok\n");
                } catch (NoSuchMethodException e) {
                    log.append("initObfuscator: absent (use string+class obf)\n");
                } catch (Throwable t) {
                    log.append("initObfuscator failed: ").append(t.getMessage())
                            .append(" (fallback string+class)\n");
                }
            }

            Hashtable<?, ?> table = invokeCompileAll(luaBytes, chunkName, fileName, log);
            if (table == null || table.isEmpty()) {
                return new CompileResult(false, null, classNames,
                        log + "LuaJC.compileAll produced no classes",
                        System.currentTimeMillis() - t0);
            }

            // key=binary class name, value=class bytes (after AGG LuaLong rewrite)
            Map<String, byte[]> classMap = new LinkedHashMap<>();
            Enumeration<?> keys = table.keys();
            while (keys.hasMoreElements()) {
                Object k = keys.nextElement();
                Object v = table.get(k);
                if (!(v instanceof byte[])) continue;
                byte[] clsBytes = (byte[]) v;
                byte[] fixed = AggLuajCompat.rewriteClass(clsBytes, target);
                if (fixed != null && fixed.length > 0) {
                    if (fixed.length != clsBytes.length) {
                        log.append("agg-compat rewrite: ")
                                .append(clsBytes.length).append("→").append(fixed.length)
                                .append('\n');
                    }
                    clsBytes = fixed;
                }
                String key = String.valueOf(k).replace('.', '/').replace('\\', '/');
                if (!key.endsWith(".class")) {
                    if (!key.contains("/")) {
                        key = key.replace('.', '/');
                    }
                    if (!key.endsWith(".class")) key = key + ".class";
                }
                while (key.startsWith("/")) key = key.substring(1);
                String binary = key.substring(0, key.length() - 6).replace('/', '.');
                classMap.put(binary, clsBytes);
                log.append("class: ").append(binary).append(" (")
                        .append(clsBytes.length).append(" bytes)\n");
            }

            if (classMap.isEmpty()) {
                return new CompileResult(false, null, classNames,
                        log + "no .class entries in compileAll result",
                        System.currentTimeMillis() - t0);
            }

            if (obfuscate) {
                // 入口类固定为 entryClass；其余 helper 才改成短名
                LuaClassObfuscator.Result obf =
                        LuaClassObfuscator.obfuscate(classMap, entryClass);
                log.append(obf.log == null ? "" : obf.log);
                classMap = obf.classes;
                entryClass = obf.entryClass != null ? obf.entryClass : entryClass;
                // B3: class 内 LDC 字符串再加密
                ClassStringEncryptor.Result b3 = ClassStringEncryptor.encrypt(classMap);
                log.append(b3.log == null ? "" : b3.log);
                classMap = b3.classes;
            }

            classNames.clear();
            // 入口类放第一位，便于 primary_class / 加载方
            if (classMap.containsKey(entryClass)) {
                classNames.add(entryClass);
            }
            for (String n : classMap.keySet()) {
                if (!n.equals(entryClass)) classNames.add(n);
            }

            File classesJar = new File(work, "classes.jar");
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(classesJar))) {
                for (Map.Entry<String, byte[]> e : classMap.entrySet()) {
                    String binary = e.getKey();
                    byte[] clsBytes = e.getValue();
                    String entry = binary.replace('.', '/') + ".class";
                    writeFile(new File(classDir, entry), clsBytes);
                    jos.putNextEntry(new JarEntry(entry));
                    jos.write(clsBytes);
                    jos.closeEntry();
                }
            }

            // D8: jar → dex（混淆时用 RELEASE，略减调试信息）
            D8Command.Builder d8 = D8Command.builder()
                    .setMode(obfuscate ? CompilationMode.RELEASE : CompilationMode.DEBUG)
                    .setMinApiLevel(24)
                    .setOutput(dexDir.toPath(), OutputMode.DexIndexed);
            d8.addProgramFiles(classesJar.toPath());
            D8.run(d8.build());

            byte[] dexBytes = findDexBytes(dexDir);
            if (dexBytes == null) {
                return new CompileResult(false, null, classNames,
                        log + "D8 produced no dex",
                        System.currentTimeMillis() - t0);
            }

            File dest = outDex;
            if (dest == null) {
                File outDir = new File(app.getFilesDir(), "lua_plugins");
                if (!outDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outDir.mkdirs();
                }
                // 默认输出文件名与入口类一致，方便 host loadClass(文件名)
                dest = new File(outDir, entryClass + ".dex");
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            // 若用户指定了 output，但 basename 与 entry 不一致，打警告
            String destBase = stripExt(dest.getName());
            if (!entryClass.equals(destBase)) {
                log.append("warn: dex base '").append(destBase)
                        .append("' != entryClass '").append(entryClass)
                        .append("' — host loadClass 请用 ").append(entryClass)
                        .append('\n');
            }
            // 上次成功编译会 setReadOnly，再次覆盖前必须可写，否则 EACCES
            prepareWritableFile(dest, log);
            writeFile(dest, dexBytes);
            log.append("dex: ").append(dest.getAbsolutePath())
                    .append(" (").append(dexBytes.length).append(" bytes)\n");
            log.append("classes: ").append(classNames.size()).append('\n');
            log.append("primary: ").append(entryClass).append('\n');
            log.append("loadClass: ").append(entryClass).append('\n');
            log.append("ok\n");
            return new CompileResult(true, dest.getAbsolutePath(), classNames,
                    log.toString(), System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            log.append("编译错误: ").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage()).append('\n');
            Throwable c = t.getCause();
            if (c != null) {
                log.append("caused by: ").append(c.getClass().getSimpleName())
                        .append(": ").append(c.getMessage()).append('\n');
            }
            return new CompileResult(false, null, classNames, log.toString(),
                    System.currentTimeMillis() - t0);
        } finally {
            if (work != null) deleteRecursively(work);
        }
    }

    public static CompileResult compileLuaSourceToDex(
            Context context,
            String luaSource,
            String chunkName,
            boolean obfuscate,
            File outDex
    ) {
        return compileLuaSourceToDex(context, luaSource, chunkName, obfuscate, outDex, GgLuajTarget.STOCK);
    }

    public static CompileResult compileLuaSourceToDex(
            Context context,
            String luaSource,
            String chunkName,
            boolean obfuscate,
            File outDex,
            GgLuajTarget target
    ) {
        File tmp = null;
        try {
            tmp = File.createTempFile("lua2dex_", ".lua", context.getCacheDir());
            writeFile(tmp, (luaSource == null ? "" : luaSource).getBytes(StandardCharsets.UTF_8));
            return compileLuaToDex(context, tmp, obfuscate, outDex, target);
        } catch (Throwable t) {
            return new CompileResult(false, null, new ArrayList<String>(),
                    "compileLuaSourceToDex: " + t.getMessage(), 0);
        } finally {
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /**
     * Load a dex plugin and instantiate {@code className}.
     * If the instance is Runnable / has main/run / is LuaValue, try to execute.
     */
    public static LoadResult loadDexPlugin(
            Context context,
            File dexFile,
            String className,
            boolean run
    ) {
        long t0 = System.currentTimeMillis();
        try {
            if (dexFile == null || !dexFile.isFile()) {
                return new LoadResult(false, "dex missing: " + dexFile, null, 0);
            }
            if (className == null || className.trim().isEmpty()) {
                return new LoadResult(false, "className is blank", null, 0);
            }
            Context app = context.getApplicationContext();
            File opt = new File(app.getCacheDir(), "dex_opt_" + SEQ.getAndIncrement());
            if (!opt.exists()) {
                //noinspection ResultOfMethodCallIgnored
                opt.mkdirs();
            }
            //noinspection ResultOfMethodCallIgnored
            dexFile.setReadOnly();

            ClassLoader parent = app.getClassLoader();
            ClassLoader loader;
            byte[] dexBytes = readFile(dexFile);
            if (Build.VERSION.SDK_INT >= 26 && dexBytes.length > 0) {
                loader = new InMemoryDexClassLoader(
                        java.nio.ByteBuffer.wrap(dexBytes), parent);
            } else {
                loader = new DexClassLoader(
                        dexFile.getAbsolutePath(),
                        opt.getAbsolutePath(),
                        null,
                        parent);
            }

            Class<?> clazz = loader.loadClass(className.trim());
            Object instance = clazz.getDeclaredConstructor().newInstance();
            String msg = "loaded " + className + " via " + loader.getClass().getSimpleName();
            String ret = null;
            if (run) {
                ret = tryRun(instance);
                msg += "; ran=" + (ret != null);
            }
            return new LoadResult(true, msg, ret, System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            String m = t.getClass().getSimpleName() + ": " + t.getMessage();
            Throwable c = t.getCause();
            if (c != null) m += " | cause " + c.getClass().getSimpleName() + ": " + c.getMessage();
            return new LoadResult(false, m, null, System.currentTimeMillis() - t0);
        }
    }

    private static String tryRun(Object instance) throws Exception {
        if (instance instanceof Runnable) {
            ((Runnable) instance).run();
            return "Runnable.run";
        }
        if (instance instanceof LuaValue) {
            LuaValue lv = (LuaValue) instance;
            // LuaJC generated prototypes often implement call with Globals upvalue
            Globals g = JsePlatform.standardGlobals();
            try {
                Method init = instance.getClass().getMethod("initupvalue1", LuaValue.class);
                init.invoke(instance, g);
            } catch (NoSuchMethodException ignored) {
            }
            Varargs r = lv.invoke();
            return r != null ? r.tojstring() : "nil";
        }
        try {
            Method main = instance.getClass().getMethod("main", String[].class);
            Object r = main.invoke(null, (Object) new String[0]);
            return r == null ? "main:void" : String.valueOf(r);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method run = instance.getClass().getMethod("run");
            Object r = run.invoke(instance);
            return r == null ? "run:void" : String.valueOf(r);
        } catch (NoSuchMethodException ignored) {
        }
        // coerce to lua and call if function-like
        try {
            LuaValue lv = CoerceJavaToLua.coerce(instance);
            if (lv.isfunction()) {
                return lv.call().tojstring();
            }
        } catch (Throwable ignored) {
        }
        return "instantiated:" + instance.getClass().getName();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Hashtable invokeCompileAll(
            byte[] luaBytes,
            String chunkName,
            String fileName,
            StringBuilder log
    ) throws Exception {
        LuaJC jc = LuaJC.instance;
        InputStream in = new ByteArrayInputStream(luaBytes);

        // Try known overloads in order (LuaJ 3.0.x variants / AGG-like)
        Object[][] attempts = new Object[][]{
                // (InputStream, chunkname, filename, boolean genjava)
                {new Class[]{InputStream.class, String.class, String.class, boolean.class},
                        new Object[]{in, chunkName, fileName, Boolean.TRUE}},
                // (InputStream, chunkname, filename, Globals, boolean)
                null, // filled below
                // (InputStream, chunkname, filename, boolean, boolean)
                {new Class[]{InputStream.class, String.class, String.class, boolean.class, boolean.class},
                        new Object[]{new ByteArrayInputStream(luaBytes), chunkName, fileName, Boolean.TRUE, Boolean.TRUE}},
                // (Reader, ...)
                {new Class[]{java.io.Reader.class, String.class, String.class, boolean.class},
                        new Object[]{new java.io.InputStreamReader(new ByteArrayInputStream(luaBytes), "UTF-8"),
                                chunkName, fileName, Boolean.TRUE}},
        };
        Globals g = JsePlatform.standardGlobals();
        attempts[1] = new Object[]{
                new Class[]{InputStream.class, String.class, String.class, Globals.class, boolean.class},
                new Object[]{new ByteArrayInputStream(luaBytes), chunkName, fileName, g, Boolean.TRUE}
        };

        Throwable last = null;
        for (Object[] att : attempts) {
            if (att == null) continue;
            Class<?>[] pts = (Class<?>[]) att[0];
            Object[] args = (Object[]) att[1];
            try {
                Method m = findMethod(jc.getClass(), "compileAll", pts);
                if (m == null) continue;
                m.setAccessible(true);
                Object r = m.invoke(jc, args);
                log.append("compileAll via ").append(sig(pts)).append('\n');
                if (r instanceof Hashtable) {
                    return (Hashtable) r;
                }
                if (r instanceof Map) {
                    return new Hashtable((Map) r);
                }
                log.append("unexpected return: ")
                        .append(r == null ? "null" : r.getClass().getName()).append('\n');
            } catch (Throwable t) {
                last = t;
                log.append("try ").append(sig(pts)).append(" failed: ")
                        .append(rootMsg(t)).append('\n');
            }
        }

        // Fallback: no overload worked
        if (last != null) {
            if (last instanceof Exception) {
                throw (Exception) last;
            }
            throw new Exception(rootMsg(last), last);
        }
        throw new NoSuchMethodException("No usable LuaJC.compileAll overload found");
    }

    private static Method findMethod(Class<?> c, String name, Class<?>[] pts) {
        try {
            return c.getMethod(name, pts);
        } catch (NoSuchMethodException e) {
            for (Method m : c.getMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != pts.length) continue;
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (!p[i].isAssignableFrom(pts[i]) && !pts[i].isAssignableFrom(p[i])) {
                        // allow InputStream/Reader exact only
                        if (p[i] != pts[i]) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (ok) return m;
            }
            return null;
        }
    }

    private static String sig(Class<?>[] pts) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(pts[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private static byte[] findDexBytes(File dexDir) throws Exception {
        File f = new File(dexDir, "classes.dex");
        if (f.isFile()) return readFile(f);
        File[] kids = dexDir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.getName().endsWith(".dex")) return readFile(k);
        }
        return null;
    }

    /**
     * Host 约定：loadClass(dexBaseName)。
     * 优先用输出 dex 文件名；否则用源名（混淆时加 _obf）。
     */
    private static String resolveEntryClassName(File luaFile, File outDex, boolean obfuscate) {
        if (outDex != null) {
            return LuaClassObfuscator.sanitizeEntryName(outDex.getName());
        }
        String base = stripExt(luaFile != null ? luaFile.getName() : "main");
        if (obfuscate && !base.endsWith("_obf")) {
            base = base + "_obf";
        }
        return LuaClassObfuscator.sanitizeEntryName(base);
    }

    private static String stripExt(String name) {
        if (name == null) return "main";
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static byte[] readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * Ensure parent exists and target is writable (clear read-only / delete stale).
     */
    private static void prepareWritableFile(File dest, StringBuilder log) {
        if (dest == null) return;
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            boolean mk = parent.mkdirs();
            if (!mk && !parent.isDirectory() && log != null) {
                log.append("warn: mkdirs failed: ").append(parent.getAbsolutePath()).append('\n');
            }
        }
        if (dest.exists()) {
            // clear read-only from previous compile
            //noinspection ResultOfMethodCallIgnored
            dest.setWritable(true, false);
            //noinspection ResultOfMethodCallIgnored
            boolean del = dest.delete();
            if (!del && dest.exists() && log != null) {
                log.append("warn: cannot delete existing: ")
                        .append(dest.getAbsolutePath()).append('\n');
            }
        }
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) {
            //noinspection ResultOfMethodCallIgnored
            p.mkdirs();
        }
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        }
        try (OutputStream out = new FileOutputStream(f, false)) {
            out.write(data);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
