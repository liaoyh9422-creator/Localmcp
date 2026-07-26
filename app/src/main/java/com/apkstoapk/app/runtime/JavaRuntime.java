package com.apkstoapk.app.runtime;

import android.content.Context;
import android.os.Build;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import org.codehaus.commons.compiler.util.resource.MapResourceCreator;
import org.codehaus.commons.compiler.util.resource.MapResourceFinder;
import org.codehaus.commons.compiler.util.resource.Resource;
import org.codehaus.commons.compiler.util.resource.StringResource;
import org.codehaus.janino.ClassLoaderIClassLoader;
import org.codehaus.janino.Compiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Full Java source runtime on device (not BeanShell, not shell javac):
 * Janino (source→class bytes) → R8/D8 (class→dex) → InMemoryDexClassLoader → main/run.
 */
public final class JavaRuntime {
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private JavaRuntime() {}

    public static final class Result {
        public final boolean ok;
        public final String stdout;
        public final String stderr;
        public final String returnValue;
        public final String mainClass;
        public final long elapsedMs;

        public Result(boolean ok, String stdout, String stderr, String returnValue,
                      String mainClass, long elapsedMs) {
            this.ok = ok;
            this.stdout = stdout;
            this.stderr = stderr;
            this.returnValue = returnValue;
            this.mainClass = mainClass;
            this.elapsedMs = elapsedMs;
        }
    }

    public static Result eval(
            Context context,
            String source,
            String mainClass,
            String[] args,
            String cwd,
            Map<String, String> env
    ) {
        long start = System.currentTimeMillis();
        if (source == null || source.trim().isEmpty()) {
            return new Result(false, "", "source is empty", null, mainClass, 0);
        }

        File work = new File(context.getCacheDir(), "java_rt_" + SEQ.getAndIncrement());
        File classDir = new File(work, "classes");
        File dexDir = new File(work, "dex");
        PrintStream prevOut = System.out;
        PrintStream prevErr = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        try {
            if (!classDir.mkdirs() || !dexDir.mkdirs()) {
                throw new IllegalStateException("Cannot create work dirs: " + work);
            }

            String className = mainClass;
            if (className == null || className.trim().isEmpty()) {
                className = detectPublicClass(source);
            }
            if (className == null || className.trim().isEmpty()) {
                className = "Main";
                source = wrapSnippet(source, className);
            }
            className = className.trim();

            String fileName = toJavaFileName(className);
            MapResourceCreator classFileCreator = new MapResourceCreator(new HashMap<String, byte[]>());
            Compiler compiler = new Compiler();
            compiler.setIClassLoader(new ClassLoaderIClassLoader(context.getClassLoader()));
            compiler.setClassFileCreator(classFileCreator);
            compiler.setClassFileFinder(new MapResourceFinder(new HashMap<String, byte[]>()));
            compiler.setDebugLines(true);
            compiler.setDebugVars(true);
            compiler.setDebugSource(true);
            try {
                compiler.compile(new Resource[]{new StringResource(fileName, source)});
            } catch (Throwable compileErr) {
                return new Result(false, "",
                        "Janino compile failed:\n" + compileErr.getClass().getSimpleName()
                                + ": " + compileErr.getMessage(),
                        null, className, System.currentTimeMillis() - start);
            }

            @SuppressWarnings("unchecked")
            Map<String, byte[]> classMap =
                    (Map<String, byte[]>) classFileCreator.getMap();
            if (classMap == null || classMap.isEmpty()) {
                return new Result(false, "", "Janino produced no class files", null, className,
                        System.currentTimeMillis() - start);
            }

            File classesJar = new File(work, "classes.jar");
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(classesJar))) {
                for (Map.Entry<String, byte[]> e : classMap.entrySet()) {
                    String path = e.getKey();
                    if (path == null || e.getValue() == null) continue;
                    // MapResourceCreator keys look like "pkg/Foo.class"
                    String entry = path.replace('\\', '/');
                    if (entry.startsWith("/")) entry = entry.substring(1);
                    writeFile(new File(classDir, entry), e.getValue());
                    jos.putNextEntry(new JarEntry(entry));
                    jos.write(e.getValue());
                    jos.closeEntry();
                }
            }

            D8Command.Builder d8 = D8Command.builder()
                    .setMode(CompilationMode.DEBUG)
                    .setMinApiLevel(24)
                    .setOutput(dexDir.toPath(), OutputMode.DexIndexed);
            d8.addProgramFiles(classesJar.toPath());
            D8.run(d8.build());

            byte[] dexBytes = findDexBytes(dexDir);
            if (dexBytes == null || dexBytes.length == 0) {
                return new Result(false, "", "D8 produced no dex", null, className,
                        System.currentTimeMillis() - start);
            }

            ClassLoader parent = context.getClassLoader();
            ClassLoader loader;
            if (Build.VERSION.SDK_INT >= 26) {
                loader = new dalvik.system.InMemoryDexClassLoader(
                        java.nio.ByteBuffer.wrap(dexBytes), parent);
            } else {
                File dexFile = new File(dexDir, "run.dex");
                writeFile(dexFile, dexBytes);
                loader = new dalvik.system.DexClassLoader(
                        dexFile.getAbsolutePath(),
                        dexDir.getAbsolutePath(),
                        null,
                        parent);
            }

            Class<?> clazz = Class.forName(className, true, loader);
            Method main = findMethod(clazz, "main", String[].class);
            Method run = main == null ? findMethod(clazz, "run") : null;
            if (main == null && run == null) {
                return new Result(false, "",
                        "No public static main(String[]) or run() in " + className,
                        null, className, System.currentTimeMillis() - start);
            }

            PrintStream outPs = new PrintStream(outBuf, true, "UTF-8");
            PrintStream errPs = new PrintStream(errBuf, true, "UTF-8");
            System.setOut(outPs);
            System.setErr(errPs);

            String prevUserDir = System.getProperty("user.dir");
            if (cwd != null && !cwd.isEmpty()) {
                System.setProperty("user.dir", cwd);
            }
            if (env != null) {
                for (Map.Entry<String, String> e : env.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        System.setProperty("mcp.env." + e.getKey(), e.getValue());
                    }
                }
            }

            Object ret;
            if (main != null) {
                String[] a = args != null ? args : new String[0];
                ret = main.invoke(null, (Object) a);
            } else {
                Object instance = clazz.getDeclaredConstructor().newInstance();
                ret = run.invoke(instance);
            }

            outPs.flush();
            errPs.flush();
            if (prevUserDir != null) System.setProperty("user.dir", prevUserDir);

            return new Result(true, outBuf.toString("UTF-8"), errBuf.toString("UTF-8"),
                    ret == null ? null : String.valueOf(ret), className,
                    System.currentTimeMillis() - start);
        } catch (Throwable t) {
            String err = errBuf.toString() + (errBuf.size() > 0 ? "\n" : "")
                    + t.getClass().getSimpleName() + ": " + t.getMessage();
            Throwable c = t.getCause();
            if (c != null && c.getMessage() != null) {
                err += "\ncaused by: " + c.getClass().getSimpleName() + ": " + c.getMessage();
            }
            return new Result(false, outBuf.toString(), err, null, mainClass,
                    System.currentTimeMillis() - start);
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
            deleteRecursively(work);
        }
    }

    public static Result evalFile(Context context, File file, String mainClass, String[] args,
                                  String cwd) throws Exception {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("java file missing: " + file);
        }
        String source = new String(readFile(file), StandardCharsets.UTF_8);
        if (mainClass == null || mainClass.isEmpty()) {
            mainClass = detectPublicClass(source);
            if (mainClass == null) {
                String n = file.getName();
                if (n.endsWith(".java")) n = n.substring(0, n.length() - 5);
                mainClass = n;
            }
        }
        String workCwd = cwd != null ? cwd : file.getParent();
        return eval(context, source, mainClass, args, workCwd, null);
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String toJavaFileName(String className) {
        String simple = className;
        int dot = className.lastIndexOf('.');
        if (dot >= 0) simple = className.substring(dot + 1);
        return simple + ".java";
    }

    private static byte[] findDexBytes(File dexDir) throws Exception {
        File classesDex = new File(dexDir, "classes.dex");
        if (classesDex.isFile()) return readFile(classesDex);
        File[] kids = dexDir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.getName().endsWith(".dex")) return readFile(k);
            if (k.getName().endsWith(".jar") || k.getName().endsWith(".zip")) {
                byte[] d = extractFirstDex(k);
                if (d != null) return d;
            }
        }
        return null;
    }

    private static byte[] extractFirstDex(File jarOrDex) throws Exception {
        if (jarOrDex.getName().endsWith(".dex")) return readFile(jarOrDex);
        try (ZipFile zf = new ZipFile(jarOrDex)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().endsWith(".dex")) {
                    try (java.io.InputStream in = zf.getInputStream(e);
                         ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
                        return bos.toByteArray();
                    }
                }
            }
        }
        return null;
    }

    private static String wrapSnippet(String body, String className) {
        String b = body.trim();
        if (b.contains("class ") || b.contains("interface ")) {
            return body;
        }
        return "public class " + className + " {\n"
                + "  public static void main(String[] args) throws Exception {\n"
                + body + "\n"
                + "  }\n"
                + "}\n";
    }

    private static String detectPublicClass(String source) {
        String pkg = null;
        java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;")
                .matcher(source);
        if (pm.find()) pkg = pm.group(1);

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("public\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)")
                .matcher(source);
        if (!m.find()) {
            m = java.util.regex.Pattern
                    .compile("(?m)^\\s*class\\s+([A-Za-z_][A-Za-z0-9_]*)")
                    .matcher(source);
            if (!m.find()) return null;
        }
        String simple = m.group(1);
        return pkg == null ? simple : pkg + "." + simple;
    }

    private static byte[] readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
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
