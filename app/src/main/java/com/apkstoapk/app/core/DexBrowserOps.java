package com.apkstoapk.app.core;

import com.apkstoapk.app.util.IoUtils;
import com.apkstoapk.app.util.SimpleApkLogger;

// smali 3.x Android tools fork (same API surface as org.jf.* / org.smali)
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

/**
 * List DEX in APK, list classes, decompile to smali / Java.
 * smali: baksmali 2.5 + dexlib2. Java: jadx-core (best-effort).
 */
public final class DexBrowserOps {
    private DexBrowserOps() {}

    public static final class DexEntry {
        public final String name;
        public final long size;

        public DexEntry(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    public static final class ClassItem {
        public final String typeName;   // Lcom/foo/Bar;
        public final String javaName;   // com.foo.Bar
        public final String simpleName;

        public ClassItem(String typeName) {
            this.typeName = typeName;
            this.javaName = typeToJava(typeName);
            int dot = javaName.lastIndexOf('.');
            this.simpleName = dot >= 0 ? javaName.substring(dot + 1) : javaName;
        }
    }

    public static List<DexEntry> listDexEntries(File apkOrDex) throws Exception {
        if (apkOrDex == null || !apkOrDex.isFile()) {
            throw new IllegalArgumentException("file missing: " + apkOrDex);
        }
        String lower = apkOrDex.getName().toLowerCase(Locale.US);
        if (lower.endsWith(".dex")) {
            return Collections.singletonList(new DexEntry(apkOrDex.getName(), apkOrDex.length()));
        }
        List<DexEntry> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(apkOrDex)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n == null) continue;
                String simple = n;
                int slash = n.lastIndexOf('/');
                if (slash >= 0) simple = n.substring(slash + 1);
                String sl = simple.toLowerCase(Locale.US);
                if (sl.matches("classes\\d*\\.dex") || (sl.endsWith(".dex") && !n.contains("/"))) {
                    out.add(new DexEntry(n, e.getSize()));
                } else if (sl.endsWith(".dex") && n.toLowerCase(Locale.US).contains("classes")) {
                    out.add(new DexEntry(n, e.getSize()));
                }
            }
        }
        Collections.sort(out, new Comparator<DexEntry>() {
            @Override
            public int compare(DexEntry a, DexEntry b) {
                return dexOrder(a.name).compareTo(dexOrder(b.name));
            }
        });
        if (out.isEmpty()) {
            throw new IllegalStateException("未找到 DEX 条目: " + apkOrDex.getName());
        }
        return out;
    }

    private static String dexOrder(String name) {
        String s = new File(name).getName().toLowerCase(Locale.US);
        if (s.equals("classes.dex")) return "0000";
        if (s.startsWith("classes") && s.endsWith(".dex")) {
            String num = s.substring(7, s.length() - 4);
            try {
                return String.format(Locale.US, "%04d", Integer.parseInt(num));
            } catch (Exception e) {
                return "9" + s;
            }
        }
        return "8" + s;
    }

    public static File extractDexEntry(File apkOrDex, String entryName, File outDex) throws Exception {
        if (apkOrDex == null || !apkOrDex.isFile()) {
            throw new IllegalArgumentException("apk/dex missing");
        }
        String lower = apkOrDex.getName().toLowerCase(Locale.US);
        if (lower.endsWith(".dex")) {
            if (outDex == null) return apkOrDex;
            IoUtils.copy(apkOrDex, outDex);
            return outDex;
        }
        if (entryName == null || entryName.isEmpty()) {
            entryName = listDexEntries(apkOrDex).get(0).name;
        }
        if (outDex == null) throw new IllegalArgumentException("outDex is null");
        File parent = outDex.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (ZipFile zf = new ZipFile(apkOrDex)) {
            ZipEntry e = zf.getEntry(entryName);
            if (e == null) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (entryName.equals(ze.getName())
                            || entryName.equals(new File(ze.getName()).getName())) {
                        e = ze;
                        break;
                    }
                }
            }
            if (e == null) throw new IllegalStateException("DEX 条目不存在: " + entryName);
            try (InputStream in = zf.getInputStream(e)) {
                IoUtils.copy(in, outDex);
            }
        }
        return outDex;
    }

    public static List<ClassItem> listClasses(File dexFile, String filter, int limit) throws Exception {
        if (dexFile == null || !dexFile.isFile()) {
            throw new IllegalArgumentException("dex missing: " + dexFile);
        }
        if (limit <= 0) limit = 8000;
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.US);
        DexBackedDexFile dex = loadDex(dexFile);
        List<ClassItem> out = new ArrayList<>();
        for (ClassDef def : dex.getClasses()) {
            String type = def.getType();
            if (type == null) continue;
            String java = typeToJava(type).toLowerCase(Locale.US);
            if (!f.isEmpty() && !java.contains(f) && !type.toLowerCase(Locale.US).contains(f)) {
                continue;
            }
            out.add(new ClassItem(type));
            if (out.size() >= limit) break;
        }
        Collections.sort(out, new Comparator<ClassItem>() {
            @Override
            public int compare(ClassItem a, ClassItem b) {
                return a.javaName.compareToIgnoreCase(b.javaName);
            }
        });
        return out;
    }

    public static String toSmali(File dexFile, String typeName, SimpleApkLogger logger) throws Exception {
        String type = normalizeType(typeName);
        DexBackedDexFile dex = loadDex(dexFile);
        ClassDef found = null;
        for (ClassDef def : dex.getClasses()) {
            if (type.equals(def.getType())) {
                found = def;
                break;
            }
        }
        if (found == null) throw new IllegalStateException("类不存在: " + type);

        BaksmaliOptions options = new BaksmaliOptions();
        StringWriter sw = new StringWriter(16 * 1024);
        Object writer = openBaksmaliWriter(sw);
        try {
            ClassDefinition classDefinition = new ClassDefinition(options, found);
            // writeTo(IndentingWriter) or writeTo(BaksmaliWriter)
            Method writeTo = null;
            for (Method m : ClassDefinition.class.getMethods()) {
                if ("writeTo".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    writeTo = m;
                    break;
                }
            }
            if (writeTo == null) {
                throw new IllegalStateException("baksmali ClassDefinition.writeTo not found");
            }
            writeTo.invoke(classDefinition, writer);
        } finally {
            closeQuietly(writer);
        }
        if (logger != null) logger.ok("smali 完成", "smali done", type);
        return sw.toString();
    }

    private static Object openBaksmaliWriter(StringWriter sw) throws Exception {
        // Prefer BaksmaliWriter for smali 3.x ClassDefinition.writeTo(BaksmaliWriter)
        String[] classes = {
                "com.android.tools.smali.baksmali.formatter.BaksmaliWriter",
                "org.jf.baksmali.formatter.BaksmaliWriter",
                "com.android.tools.smali.util.IndentingWriter",
                "org.jf.util.IndentingWriter"
        };
        Throwable last = null;
        for (String cn : classes) {
            try {
                Class<?> c = Class.forName(cn);
                Constructor<?> ctor = c.getConstructor(Writer.class);
                return ctor.newInstance(sw);
            } catch (Throwable t) {
                last = t;
            }
        }
        // Some forks: BaksmaliWriter(Writer, boolean)
        for (String cn : new String[] {
                "com.android.tools.smali.baksmali.formatter.BaksmaliWriter",
                "org.jf.baksmali.formatter.BaksmaliWriter"
        }) {
            try {
                Class<?> c = Class.forName(cn);
                Constructor<?> ctor = c.getConstructor(Writer.class, boolean.class);
                return ctor.newInstance(sw, true);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("No baksmali writer class", last);
    }

    private static void closeQuietly(Object writer) {
        if (writer == null) return;
        try {
            Method m = writer.getClass().getMethod("close");
            m.invoke(writer);
        } catch (Exception ignored) {
        }
    }

    public static String toJava(File dexFile, String typeName, SimpleApkLogger logger) throws Exception {
        String wantJava = typeToJava(normalizeType(typeName));
        File tmpOut = new File(dexFile.getParentFile(), "jadx_tmp_" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        tmpOut.mkdirs();
        JadxArgs args = new JadxArgs();
        args.setInputFiles(Collections.singletonList(dexFile));
        args.setOutDir(tmpOut);
        args.setShowInconsistentCode(true);
        args.setReplaceConsts(true);
        args.setDeobfuscationOn(false);
        args.setThreadsCount(1);
        args.setSkipResources(true);

        JadxDecompiler decompiler = null;
        try {
            decompiler = new JadxDecompiler(args);
            decompiler.load();
            for (JavaClass cls : decompiler.getClasses()) {
                String full = cls.getFullName();
                if (full == null) continue;
                if (wantJava.equals(full)
                        || full.replace('$', '.').equals(wantJava.replace('$', '.'))
                        || full.endsWith("." + wantJava)
                        || wantJava.endsWith("." + cls.getName())) {
                    String code = cls.getCode();
                    if (code == null || code.trim().isEmpty()) {
                        return "// jadx 未生成代码，回退 smali\n\n"
                                + toSmali(dexFile, typeName, null);
                    }
                    if (logger != null) {
                        logger.ok("Java 反编译完成", "Java decompile done", full);
                    }
                    return code;
                }
            }
            throw new IllegalStateException("jadx 未找到类: " + wantJava + "（可改看 smali）");
        } catch (Throwable t) {
            if (t instanceof IllegalStateException
                    && t.getMessage() != null
                    && t.getMessage().startsWith("jadx 未找到")) {
                throw (IllegalStateException) t;
            }
            if (logger != null) {
                logger.warn("jadx 失败，回退 smali", "jadx failed",
                        t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            String smali = toSmali(dexFile, typeName, null);
            return "// jadx 失败: " + t.getClass().getSimpleName() + ": "
                    + t.getMessage() + "\n// 以下为 smali 回退\n\n" + smali;
        } finally {
            if (decompiler != null) {
                try {
                    decompiler.close();
                } catch (Exception ignored) {
                }
            }
            IoUtils.deleteRecursively(tmpOut);
        }
    }

    private static DexBackedDexFile loadDex(File dexFile) throws Exception {
        byte[] data;
        try (InputStream in = new FileInputStream(dexFile);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            IoUtils.copy(in, bos);
            data = bos.toByteArray();
        }
        try {
            // dexlib2 2.5.2
            return new DexBackedDexFile(Opcodes.getDefault(), data);
        } catch (Throwable t) {
            return DexBackedDexFile.fromInputStream(Opcodes.getDefault(),
                    new ByteArrayInputStream(data));
        }
    }

    public static String typeToJava(String type) {
        if (type == null) return "";
        String t = type.trim();
        if (t.startsWith("L") && t.endsWith(";")) {
            t = t.substring(1, t.length() - 1).replace('/', '.');
        }
        return t;
    }

    public static String normalizeType(String name) {
        String t = name == null ? "" : name.trim();
        if (t.startsWith("L") && t.endsWith(";")) return t;
        if (t.contains("/")) {
            if (!t.startsWith("L")) t = "L" + t;
            if (!t.endsWith(";")) t = t + ";";
            return t;
        }
        t = t.replace('.', '/');
        if (!t.startsWith("L")) t = "L" + t;
        if (!t.endsWith(";")) t = t + ";";
        return t;
    }

    public static String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        return String.format(Locale.US, "%.2f MB", size / (1024.0 * 1024.0));
    }
}
