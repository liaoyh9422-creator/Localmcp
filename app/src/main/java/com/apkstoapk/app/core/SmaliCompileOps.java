package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.DexFileInputSource;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.dex.key.TypeKey;
import com.reandroid.dex.model.DexClass;
import com.reandroid.dex.model.DexFile;
import com.reandroid.dex.smali.SmaliReader;
import com.reandroid.dex.smali.model.SmaliClass;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compile / apply arbitrary smali class text into an {@link ApkModule}.
 *
 * <p>Uses ARSCLib reandroid smali ({@link SmaliClass} + {@link DexClass#fromSmali} /
 * {@code replace} / merge fallbacks via reflection for API variance).
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code replace} — class must already exist (default)</li>
 *   <li>{@code upsert} — replace if present, otherwise inject into a dex</li>
 * </ul>
 */
public final class SmaliCompileOps {
    private static final Pattern CLASS_LINE = Pattern.compile(
            "^\\.class\\s+(?:(?:public|private|protected|final|abstract|interface|enum|synthetic|annotation|static)\\s+)*L([^;\\s]+);",
            Pattern.MULTILINE);

    private SmaliCompileOps() {}

    public static final class Result {
        public final String classDescriptor;
        public final String dexName;
        public final boolean created;
        public final boolean replaced;
        public final List<String> details;

        public Result(
                String classDescriptor,
                String dexName,
                boolean created,
                boolean replaced,
                List<String> details
        ) {
            this.classDescriptor = classDescriptor;
            this.dexName = dexName;
            this.created = created;
            this.replaced = replaced;
            this.details = details != null ? details : new ArrayList<String>();
        }
    }

    public static Result applySmaliFile(
            ApkModule module,
            File smaliFile,
            String mode,
            String preferDex,
            SimpleApkLogger logger
    ) throws Exception {
        if (smaliFile == null || !smaliFile.isFile()) {
            throw new IllegalArgumentException("smali 文件不存在: " + smaliFile);
        }
        String text = new String(Files.readAllBytes(smaliFile.toPath()), StandardCharsets.UTF_8);
        if (logger != null) {
            logger.bi("smali 文件", "smali file", smaliFile.getAbsolutePath());
        }
        return applySmali(module, text, mode, preferDex, logger);
    }

    /**
     * @param mode {@code replace}|{@code upsert}（也认 add/create/inject）
     * @param preferDex 可选目标 dex，如 classes.dex
     */
    public static Result applySmali(
            ApkModule module,
            String smaliSource,
            String mode,
            String preferDex,
            SimpleApkLogger logger
    ) throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (smaliSource == null || smaliSource.trim().isEmpty()) {
            throw new IllegalArgumentException("smali 源码不能为空");
        }
        String modeNorm = normalizeMode(mode);
        String source = smaliSource.replace("\r\n", "\n").replace('\r', '\n');
        String descriptor = extractClassDescriptor(source);
        if (descriptor == null) {
            throw new IllegalArgumentException("无法从 smali 解析 .class 行（需要 Lpkg/Name;）");
        }
        if (logger != null) {
            logger.stage("编译 smali", "Compile smali");
            logger.bi("类", "Class", descriptor);
            logger.bi("模式", "Mode", modeNorm);
        }

        List<String> details = new ArrayList<>();
        SmaliClass smaliClass = parseSmaliClass(source, logger, details);

        List<DexFileInputSource> dexSources = module.listDexFiles();
        if (dexSources == null || dexSources.isEmpty()) {
            throw new IllegalStateException("APK 中没有 classes*.dex");
        }

        HostDex existing = findHostDex(dexSources, descriptor, preferDex);
        if (existing != null) {
            applyIntoDex(module, existing, smaliClass, source, descriptor, true, logger, details);
            return new Result(descriptor, existing.dexName, false, true, details);
        }

        if ("replace".equals(modeNorm)) {
            throw new IllegalStateException(
                    "类不存在，replace 模式拒绝创建: " + descriptor
                            + "（改用 mode=upsert）");
        }

        HostDex host = pickInjectHost(dexSources, preferDex);
        applyIntoDex(module, host, smaliClass, source, descriptor, false, logger, details);
        return new Result(descriptor, host.dexName, true, false, details);
    }

    public static String exportSmali(
            ApkModule module,
            String className,
            SimpleApkLogger logger
    ) throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        String descriptor = normalizeDescriptor(className);
        List<DexFileInputSource> dexSources = module.listDexFiles();
        if (dexSources == null || dexSources.isEmpty()) {
            throw new IllegalStateException("APK 中没有 DEX");
        }
        HostDex host = findHostDex(dexSources, descriptor, null);
        if (host == null) {
            throw new IllegalStateException("未找到类: " + descriptor);
        }
        DexFile dexFile = DexFile.read(host.bytes);
        try {
            dexFile.setSimpleName(host.dexName);
            TypeKey typeKey = TypeKey.create(descriptor);
            DexClass dexClass = typeKey != null ? dexFile.getDexClass(typeKey) : null;
            if (dexClass == null) {
                throw new IllegalStateException(
                        "DEX 内无此类: " + descriptor + " @ " + host.dexName);
            }
            String smali = classToSmali(dexClass);
            if (logger != null) {
                logger.ok("已导出 smali", "Exported smali",
                        descriptor + " from " + host.dexName
                                + " (" + smali.length() + " chars)");
            }
            return smali;
        } finally {
            try {
                dexFile.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------- parse / apply ----------------

    private static SmaliClass parseSmaliClass(
            String source,
            SimpleApkLogger logger,
            List<String> details
    ) throws Exception {
        Exception last = null;
        SmaliReader reader = null;
        try {
            reader = newSmaliReader(source);
        } catch (Exception e) {
            last = e;
        }

        // SmaliClass instance parse/read(SmaliReader)
        if (reader != null) {
            for (String methodName : new String[] {"parse", "read"}) {
                try {
                    Method m = SmaliClass.class.getMethod(methodName, SmaliReader.class);
                    SmaliClass sc = new SmaliClass();
                    m.invoke(sc, reader);
                    details.add("parse=SmaliClass." + methodName + "(SmaliReader)");
                    if (logger != null) {
                        logger.ok("smali 已解析", "smali parsed", methodName);
                    }
                    return sc;
                } catch (NoSuchMethodException e) {
                    last = e;
                } catch (Exception e) {
                    last = unwrap(e);
                    // reader may be consumed; rebuild
                    try {
                        reader = newSmaliReader(source);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // Any single-arg parse/read on SmaliClass
        for (Method m : SmaliClass.class.getMethods()) {
            if (!"parse".equals(m.getName()) && !"read".equals(m.getName())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 1) continue;
            try {
                Object arg;
                if (pts[0] == String.class) {
                    arg = source;
                } else if (pts[0] == char[].class) {
                    arg = source.toCharArray();
                } else if (pts[0].getName().contains("SmaliReader")) {
                    arg = newSmaliReader(source);
                } else {
                    continue;
                }
                Object result;
                if (Modifier.isStatic(m.getModifiers())) {
                    result = m.invoke(null, arg);
                } else {
                    SmaliClass sc = new SmaliClass();
                    result = m.invoke(sc, arg);
                    if (result == null) result = sc;
                }
                if (result instanceof SmaliClass) {
                    details.add("parse=" + m.toGenericString());
                    if (logger != null) {
                        logger.ok("smali 已解析", "smali parsed", m.getName());
                    }
                    return (SmaliClass) result;
                }
            } catch (Exception e) {
                last = unwrap(e);
            }
        }

        throw new IllegalStateException(
                "解析 smali 失败（ARSCLib SmaliClass）: "
                        + (last != null ? last.getMessage() : "no parse method"),
                last);
    }

    private static void applyIntoDex(
            ApkModule module,
            HostDex host,
            SmaliClass smaliClass,
            String smaliSource,
            String descriptor,
            boolean replaceExisting,
            SimpleApkLogger logger,
            List<String> details
    ) throws Exception {
        DexFile dexFile = DexFile.read(host.bytes);
        try {
            dexFile.setSimpleName(host.dexName);
            boolean ok = false;
            TypeKey typeKey = TypeKey.create(descriptor);
            DexClass dexClass = typeKey != null ? dexFile.getDexClass(typeKey) : null;

            // A) Prefer DexClass.parse/fromSmali via declared methods (may be package-visible)
            if (dexClass != null && smaliSource != null) {
                ok = invokeClassSmaliWrite(dexClass, smaliClass, smaliSource, details);
            }

            // B) file-level merge / fromSmali
            if (!ok) {
                ok = tryFromSmali(dexFile, smaliClass, descriptor, details);
            }
            if (!ok) {
                ok = tryReplaceClass(dexFile, smaliClass, descriptor, details);
            }
            if (!ok) {
                ok = tryMergeSmali(dexFile, smaliClass, descriptor, details);
            }

            // C) temp single-class smali dir + DexFile.parseSmaliDirectory
            if (!ok && smaliSource != null) {
                ok = tryParseSmaliDirectory(dexFile, smaliSource, descriptor, details);
            }

            // E) Official smali assembler → ClassDef → rewrite whole dex via dexlib2
            if (!ok && smaliSource != null) {
                ok = trySmaliAssemblerReplace(host, module, smaliSource, descriptor, details, logger);
                if (ok) {
                    // already wrote dex into module; skip later write
                    return;
                }
            }

            // D) create class then write
            if (!ok && !replaceExisting && smaliSource != null && typeKey != null) {
                try {
                    DexClass created = tryGetOrCreateClass(dexFile, typeKey, descriptor, details);
                    if (created != null) {
                        ok = invokeClassSmaliWrite(created, smaliClass, smaliSource, details);
                    }
                } catch (Exception e) {
                    details.add("create+write failed: " + safeMsg(e));
                }
            }

            if (!ok) {
                throw new IllegalStateException(
                        "无法将 smali 写入 DEX: " + descriptor + " | details=" + details);
            }
            try {
                dexFile.refreshFull();
            } catch (Exception e) {
                try {
                    dexFile.refresh();
                } catch (Exception ignored) {
                }
            }
            byte[] outBytes = dexFile.getBytes();
            module.add(new ByteInputSource(outBytes, host.dexName));
            details.add((replaceExisting ? "replaced" : "created")
                    + " in " + host.dexName + " (" + outBytes.length + " bytes)");
            if (logger != null) {
                logger.ok(replaceExisting ? "已替换类" : "已注入类",
                        replaceExisting ? "Class replaced" : "Class injected",
                        descriptor + " → " + host.dexName);
                logger.ok("已写回 DEX", "DEX written back",
                        host.dexName + " (" + outBytes.length + " bytes)");
                for (String d : details) {
                    if (d != null && d.startsWith("apply=")) {
                        logger.item("写回路径", "Apply path", d);
                    }
                }
            }
        } finally {
            try {
                dexFile.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Try all known DexClass write APIs with setAccessible.
     * ARSCLib often exposes parse(SmaliReader) / fromSmali(Smali) as package-private.
     */
    private static boolean invokeClassSmaliWrite(
            DexClass dexClass,
            SmaliClass smaliClass,
            String smaliSource,
            List<String> details
    ) {
        // 1) declared parse(SmaliReader)
        if (smaliSource != null) {
            try {
                SmaliReader reader = newSmaliReader(smaliSource);
                Method m = findAccessible(DexClass.class, "parse", reader.getClass());
                if (m == null) {
                    m = findAccessible(DexClass.class, "parse", SmaliReader.class);
                }
                if (m != null) {
                    m.invoke(dexClass, reader);
                    details.add("apply=DexClass.parse(SmaliReader)/" + m);
                    return true;
                }
            } catch (Exception e) {
                details.add("DexClass.parse failed: " + fullMsg(e));
            }
        }

        // 2) fromSmali(Smali) / fromSmali(SmaliClass)
        if (smaliClass != null) {
            for (String name : new String[] {"fromSmali", "replace", "merge", "edit", "add"}) {
                try {
                    Method m = findAccessibleSingleArg(DexClass.class, name, smaliClass.getClass());
                    if (m == null) {
                        // try Smali interface / superclass
                        Class<?> c = smaliClass.getClass();
                        while (c != null && m == null) {
                            m = findAccessibleSingleArg(DexClass.class, name, c);
                            c = c.getSuperclass();
                        }
                    }
                    if (m != null) {
                        m.invoke(dexClass, smaliClass);
                        details.add("apply=DexClass." + name + "/" + m);
                        return true;
                    }
                } catch (Exception e) {
                    details.add("DexClass." + name + " failed: " + fullMsg(e));
                }
            }
        }

        // 3) parseMethod / rebuild: remove all methods then parse from reader line-by-line — skip
        return false;
    }

    private static Method findAccessible(Class<?> owner, String name, Class<?> param) {
        try {
            Method m = owner.getDeclaredMethod(name, param);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            try {
                Method m = owner.getMethod(name, param);
                m.setAccessible(true);
                return m;
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static Method findAccessibleSingleArg(Class<?> owner, String name, Class<?> argType) {
        for (Method m : owner.getDeclaredMethods()) {
            if (!name.equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0].isAssignableFrom(argType)) {
                m.setAccessible(true);
                return m;
            }
        }
        for (Method m : owner.getMethods()) {
            if (!name.equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0].isAssignableFrom(argType)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static DexClass tryGetOrCreateClass(
            DexFile dexFile,
            TypeKey typeKey,
            String descriptor,
            List<String> details
    ) {
        for (Method m : DexFile.class.getDeclaredMethods()) {
            if (!m.getName().startsWith("getOrCreate") || m.getParameterTypes().length != 1) continue;
            try {
                m.setAccessible(true);
                Class<?> p0 = m.getParameterTypes()[0];
                Object r;
                if (p0.isAssignableFrom(TypeKey.class)) {
                    r = m.invoke(dexFile, typeKey);
                } else if (p0 == String.class) {
                    r = m.invoke(dexFile, descriptor);
                } else {
                    continue;
                }
                if (r instanceof DexClass) {
                    details.add("create=" + m.getName());
                    return (DexClass) r;
                }
            } catch (Exception ignored) {
            }
        }
        for (Method m : DexFile.class.getMethods()) {
            if (!m.getName().startsWith("getOrCreate") || m.getParameterTypes().length != 1) continue;
            try {
                Class<?> p0 = m.getParameterTypes()[0];
                Object r;
                if (p0.isAssignableFrom(TypeKey.class)) {
                    r = m.invoke(dexFile, typeKey);
                } else if (p0 == String.class) {
                    r = m.invoke(dexFile, descriptor);
                } else {
                    continue;
                }
                if (r instanceof DexClass) {
                    details.add("create=" + m.getName());
                    return (DexClass) r;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Write one class to a temp smali tree and call DexFile.parseSmaliDirectory.
     */

    private static void tryRemoveClass(DexFile dexFile, String descriptor, List<String> details) {
        try {
            TypeKey typeKey = TypeKey.create(descriptor);
            DexClass dexClass = typeKey != null ? dexFile.getDexClass(typeKey) : null;
            if (dexClass == null) {
                details.add("remove=class not present");
                return;
            }
            // DexClass.removeSelf()
            try {
                Method m = findAccessible(DexClass.class, "removeSelf");
                if (m != null && m.getParameterTypes().length == 0) {
                    m.invoke(dexClass);
                    details.add("remove=DexClass.removeSelf()");
                    return;
                }
            } catch (Exception e) {
                details.add("removeSelf failed: " + fullMsg(e));
            }
            try {
                Method m = findAccessibleSingleArg(DexClass.class, "remove", boolean.class);
                // no
            } catch (Exception ignored) {
            }
            for (Method m : DexClass.class.getDeclaredMethods()) {
                if (!"removeSelf".equals(m.getName()) && !"remove".equals(m.getName())) continue;
                try {
                    m.setAccessible(true);
                    if (m.getParameterTypes().length == 0) {
                        m.invoke(dexClass);
                        details.add("remove=" + m.getName());
                        return;
                    }
                } catch (Exception e) {
                    details.add("remove try " + m.getName() + ": " + fullMsg(e));
                }
            }
            // DexFile level remove
            for (Method m : DexFile.class.getDeclaredMethods()) {
                if (!m.getName().toLowerCase(Locale.US).contains("remove")) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    m.setAccessible(true);
                    if (p.length == 1 && p[0].isAssignableFrom(TypeKey.class)) {
                        m.invoke(dexFile, typeKey);
                        details.add("remove=DexFile." + m.getName() + "(TypeKey)");
                        return;
                    }
                    if (p.length == 1 && p[0] == String.class) {
                        m.invoke(dexFile, descriptor);
                        details.add("remove=DexFile." + m.getName() + "(String)");
                        return;
                    }
                    if (p.length == 1 && p[0].isAssignableFrom(DexClass.class)) {
                        m.invoke(dexFile, dexClass);
                        details.add("remove=DexFile." + m.getName() + "(DexClass)");
                        return;
                    }
                } catch (Exception e) {
                    details.add("DexFile remove: " + fullMsg(e));
                }
            }
            details.add("remove=no API succeeded (class still present?)");
        } catch (Exception e) {
            details.add("tryRemoveClass: " + fullMsg(e));
        }
    }

    private static Method findAccessible(Class<?> owner, String name) {
        try {
            Method m = owner.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Exception e) {
            try {
                Method m = owner.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static boolean tryParseSmaliDirectory(
            DexFile dexFile,
            String smaliSource,
            String descriptor,
            List<String> details
    ) {
        File tmp = null;
        try {
            // MUST remove existing class first, else ARSCLib: "has already been interned"
            tryRemoveClass(dexFile, descriptor, details);
            try {
                dexFile.refreshFull();
            } catch (Exception e) {
                try { dexFile.refresh(); } catch (Exception ignored) {}
            }

            tmp = Files.createTempDirectory("smali_one_").toFile();
            String rel = descriptor.substring(1, descriptor.length() - 1) + ".smali";
            File out = new File(tmp, rel);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            Files.write(out.toPath(), smaliSource.getBytes(StandardCharsets.UTF_8));

            // parseSmaliDirectory(File) or (SmaliReaderSetting, File)
            for (Method m : DexFile.class.getDeclaredMethods()) {
                if (!"parseSmaliDirectory".equals(m.getName())) continue;
                m.setAccessible(true);
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 1 && p[0] == File.class) {
                        m.invoke(dexFile, tmp);
                        details.add("apply=parseSmaliDirectory(File)");
                        return true;
                    }
                    if (p.length == 2 && p[1] == File.class) {
                        Object setting = p[0].getDeclaredConstructor().newInstance();
                        m.invoke(dexFile, setting, tmp);
                        details.add("apply=parseSmaliDirectory(setting,File)");
                        return true;
                    }
                } catch (Exception e) {
                    details.add("parseSmaliDirectory " + m + " failed: " + fullMsg(e));
                }
            }
            for (Method m : DexFile.class.getMethods()) {
                if (!"parseSmaliDirectory".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 1 && p[0] == File.class) {
                        m.invoke(dexFile, tmp);
                        details.add("apply=parseSmaliDirectory(File)/public");
                        return true;
                    }
                    if (p.length == 2 && p[1] == File.class) {
                        Object setting = p[0].getDeclaredConstructor().newInstance();
                        m.invoke(dexFile, setting, tmp);
                        details.add("apply=parseSmaliDirectory(setting,File)/public");
                        return true;
                    }
                } catch (Exception e) {
                    details.add("parseSmaliDirectory public failed: " + fullMsg(e));
                }
            }
        } catch (Exception e) {
            details.add("tryParseSmaliDirectory: " + fullMsg(e));
        } finally {
            if (tmp != null) {
                deleteTree(tmp);
            }
        }
        return false;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String fullMsg(Exception e) {
        Throwable t = e;
        if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
            t = e.getCause();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        int guard = 0;
        while (c != null && guard++ < 4) {
            sb.append(" <- ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            c = c.getCause();
        }
        return sb.toString();
    }

    private static boolean tryFromSmali(
            DexFile dexFile,
            SmaliClass smaliClass,
            String descriptor,
            List<String> details
    ) {
        TypeKey typeKey = TypeKey.create(descriptor);
        DexClass dexClass = typeKey != null ? dexFile.getDexClass(typeKey) : null;
        if (dexClass != null && smaliClass != null) {
            if (invokeClassSmaliWrite(dexClass, smaliClass, null, details)) {
                return true;
            }
        }
        for (String name : new String[] {"fromSmali", "merge", "add", "combineFrom"}) {
            Method m = findAccessibleSingleArg(DexFile.class, name, smaliClass.getClass());
            if (m == null) continue;
            try {
                m.invoke(dexFile, smaliClass);
                details.add("apply=DexFile." + name);
                return true;
            } catch (Exception e) {
                details.add("DexFile." + name + " failed: " + fullMsg(e));
            }
        }
        return false;
    }

    private static boolean tryReplaceClass(
            DexFile dexFile,
            SmaliClass smaliClass,
            String descriptor,
            List<String> details
    ) {
        try {
            TypeKey typeKey = TypeKey.create(descriptor);
            DexClass dexClass = typeKey != null ? dexFile.getDexClass(typeKey) : null;
            if (dexClass == null || smaliClass == null) return false;
            Method m = findAccessibleSingleArg(DexClass.class, "replace", smaliClass.getClass());
            if (m == null) return false;
            m.invoke(dexClass, smaliClass);
            details.add("apply=DexClass.replace");
            return true;
        } catch (Exception e) {
            details.add("replace failed: " + fullMsg(e));
            return false;
        }
    }

    private static boolean tryMergeSmali(
            DexFile dexFile,
            SmaliClass smaliClass,
            String descriptor,
            List<String> details
    ) {
        if (smaliClass == null) return false;
        for (String name : new String[] {"merge", "add"}) {
            Method m = findAccessibleSingleArg(DexFile.class, name, smaliClass.getClass());
            if (m == null) continue;
            try {
                m.invoke(dexFile, smaliClass);
                details.add("apply=DexFile." + name + "(SmaliClass)");
                return true;
            } catch (Exception e) {
                details.add(name + " failed: " + fullMsg(e));
            }
        }
        return false;
    }


    /**
     * Fallback: assemble smali with com.android.tools.smali, replace ClassDef in dexlib2, write bytes.
     * Then put bytes back into ApkModule. Does not use ARSCLib DexFile mutation.
     */
    private static boolean trySmaliAssemblerReplace(
            HostDex host,
            ApkModule module,
            String smaliSource,
            String descriptor,
            List<String> details,
            SimpleApkLogger logger
    ) {
        try {
            Object classDef = assembleSmaliToClassDef(smaliSource, details);
            if (classDef == null) return false;

            // Load original dex via dexlib2
            Class<?> opcodesClz = Class.forName("com.android.tools.smali.dexlib2.Opcodes");
            Object opcodes = opcodesClz.getMethod("getDefault").invoke(null);
            Class<?> dexBackedClz = Class.forName(
                    "com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile");
            Object dexFile;
            try {
                java.lang.reflect.Constructor<?> ctor =
                        dexBackedClz.getConstructor(opcodesClz, byte[].class);
                dexFile = ctor.newInstance(opcodes, host.bytes);
            } catch (NoSuchMethodException e) {
                Method fromIs = dexBackedClz.getMethod(
                        "fromInputStream", opcodesClz, java.io.InputStream.class);
                dexFile = fromIs.invoke(null, opcodes,
                        new java.io.ByteArrayInputStream(host.bytes));
            }

            // Collect classes, replace matching type
            Method getClasses = dexBackedClz.getMethod("getClasses");
            Object classesSet = getClasses.invoke(dexFile);
            List<Object> outClasses = new ArrayList<>();
            boolean replaced = false;
            if (classesSet instanceof Iterable) {
                for (Object cd : (Iterable<?>) classesSet) {
                    String type = String.valueOf(cd.getClass().getMethod("getType").invoke(cd));
                    if (descriptor.equals(type)) {
                        outClasses.add(classDef);
                        replaced = true;
                    } else {
                        outClasses.add(cd);
                    }
                }
            }
            if (!replaced) {
                // upsert: append
                outClasses.add(classDef);
                details.add("assembler=append new class");
            } else {
                details.add("assembler=replaced class");
            }

            // DexPool / MemoryDexFile / DexBuilder write
            byte[] outBytes = writeDexlibClasses(opcodes, outClasses, details);
            if (outBytes == null || outBytes.length == 0) {
                details.add("assembler write returned empty");
                return false;
            }
            module.add(new ByteInputSource(outBytes, host.dexName));
            details.add("assembler wrote " + host.dexName + " (" + outBytes.length + " bytes)");
            if (logger != null) {
                logger.ok("smali 汇编写回", "smali assembled",
                        descriptor + " → " + host.dexName);
            }
            return true;
        } catch (Throwable t) {
            details.add("assembler fallback: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static Object assembleSmaliToClassDef(String smaliSource, List<String> details)
            throws Exception {
        // com.android.tools.smali.smali.Smali.assembleSmaliFile variants
        File tmp = Files.createTempFile("one_", ".smali").toFile();
        try {
            Files.write(tmp.toPath(), smaliSource.getBytes(StandardCharsets.UTF_8));
            Class<?> smaliClz = Class.forName("com.android.tools.smali.smali.Smali");
            Class<?> optionsClz = Class.forName("com.android.tools.smali.smali.SmaliOptions");
            Object options = optionsClz.getDeclaredConstructor().newInstance();
            // try set api level if present
            try {
                Method setApi = optionsClz.getMethod("setApiLevel", int.class);
                setApi.invoke(options, 28);
            } catch (Exception ignored) {
                try {
                    java.lang.reflect.Field f = optionsClz.getField("apiLevel");
                    f.setInt(options, 28);
                } catch (Exception ignored2) {
                }
            }

            // Method signatures vary across forks (public + declared)
            Exception last = null;
            List<Method> methods = new ArrayList<>();
            for (Method m : smaliClz.getMethods()) methods.add(m);
            for (Method m : smaliClz.getDeclaredMethods()) {
                m.setAccessible(true);
                methods.add(m);
            }
            details.add("Smali methods=" + methods.size());
            for (Method m : methods) {
                if (!m.getName().toLowerCase(Locale.US).contains("assemble")
                        && !m.getName().toLowerCase(Locale.US).contains("smali")) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    Object result = null;
                    if (p.length == 2 && p[0] == File.class) {
                        result = m.invoke(null, tmp, options);
                    } else if (p.length == 1 && p[0] == File.class) {
                        result = m.invoke(null, tmp);
                    } else if (p.length == 2 && p[0] == String.class) {
                        result = m.invoke(null, smaliSource, options);
                    } else if (p.length == 3 && Reader.class.isAssignableFrom(p[0])) {
                        result = m.invoke(null, new java.io.StringReader(smaliSource),
                                "inline.smali", options);
                    } else {
                        continue;
                    }
                    if (result != null) {
                        details.add("assemble=" + m.toGenericString());
                        // may return ClassDef or List
                        if (result instanceof Iterable && !(result instanceof CharSequence)) {
                            for (Object o : (Iterable<?>) result) {
                                if (o != null && o.getClass().getName().contains("ClassDef")) {
                                    return o;
                                }
                                // return first
                                return o;
                            }
                        }
                        return result;
                    }
                } catch (Exception e) {
                    last = e;
                    details.add("assemble try " + m.getName() + ": " + e.getMessage());
                }
            }

            // Smali.assembleSmaliFile(File, SmaliOptions) returning ClassDef
            // Alternative: org.jf.smali.Smali
            try {
                Class<?> smali2 = Class.forName("org.jf.smali.Smali");
                for (Method m : smali2.getMethods()) {
                    if (!m.getName().toLowerCase(Locale.US).contains("assemble")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length >= 1 && p[0] == File.class) {
                        Object result = p.length == 1 ? m.invoke(null, tmp)
                                : m.invoke(null, tmp, options);
                        if (result != null) {
                            details.add("assemble=org.jf " + m.getName());
                            return result;
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }

            if (last != null) throw last;
            details.add("no assemble method found on Smali class");
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static byte[] writeDexlibClasses(
            Object opcodes,
            List<Object> classes,
            List<String> details
    ) throws Exception {
        // Prefer DexPool
        try {
            Class<?> dexPoolClz = Class.forName("com.android.tools.smali.dexlib2.writer.pool.DexPool");
            Object pool;
            try {
                java.lang.reflect.Constructor<?> c =
                        dexPoolClz.getConstructor(opcodes.getClass());
                pool = c.newInstance(opcodes);
            } catch (NoSuchMethodException e) {
                pool = dexPoolClz.getDeclaredConstructor().newInstance();
            }
            Method internClass = null;
            for (Method m : dexPoolClz.getMethods()) {
                if ("internClass".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    internClass = m;
                    break;
                }
            }
            if (internClass == null) {
                details.add("DexPool.internClass missing");
            } else {
                for (Object cd : classes) {
                    internClass.invoke(pool, cd);
                }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                Method writeTo = null;
                for (Method m : dexPoolClz.getMethods()) {
                    if ("writeTo".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        writeTo = m;
                        break;
                    }
                }
                if (writeTo != null) {
                    Class<?> param = writeTo.getParameterTypes()[0];
                    if (param.getName().contains("DexDataStore")
                            || param.getName().contains("MemoryDataStore")
                            || param.getName().contains("FileDataStore")) {
                        // MemoryDexDataStore / MemoryDataStore
                        Object store = null;
                        for (String cn : new String[] {
                                "com.android.tools.smali.dexlib2.writer.io.MemoryDataStore",
                                "com.android.tools.smali.dexlib2.writer.io.MemoryDexDataStore",
                                "org.jf.dexlib2.writer.io.MemoryDataStore"
                        }) {
                            try {
                                Class<?> sc = Class.forName(cn);
                                store = sc.getDeclaredConstructor().newInstance();
                                break;
                            } catch (Exception ignored) {
                            }
                        }
                        if (store != null) {
                            writeTo.invoke(pool, store);
                            // getData / getBuffer / read
                            for (String mn : new String[] {"getData", "getBuffer", "getBytes"}) {
                                try {
                                    Method g = store.getClass().getMethod(mn);
                                    Object data = g.invoke(store);
                                    if (data instanceof byte[]) {
                                        details.add("write=DexPool+MemoryDataStore");
                                        return (byte[]) data;
                                    }
                                    if (data instanceof java.nio.ByteBuffer) {
                                        java.nio.ByteBuffer bb = (java.nio.ByteBuffer) data;
                                        byte[] arr = new byte[bb.remaining()];
                                        bb.get(arr);
                                        return arr;
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                            // some stores: readFrom(0, length) 
                            try {
                                Method getData = store.getClass().getMethod("readFrom", int.class, int.class);
                                // skip
                            } catch (Exception ignored) {
                            }
                            try {
                                Method getBuf = store.getClass().getMethod("getBuffer");
                                Object buf = getBuf.invoke(store);
                                Method getData2 = buf.getClass().getMethod("getData");
                                Object data = getData2.invoke(buf);
                                if (data instanceof byte[]) return (byte[]) data;
                            } catch (Exception ignored) {
                            }
                        }
                    } else if (OutputStream.class.isAssignableFrom(param)) {
                        writeTo.invoke(pool, bos);
                        details.add("write=DexPool+OutputStream");
                        return bos.toByteArray();
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            details.add("DexPool not found: " + e.getMessage());
        } catch (Exception e) {
            details.add("DexPool write failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // DexBuilder fallback
        try {
            Class<?> builderClz = Class.forName("com.android.tools.smali.dexlib2.writer.builder.DexBuilder");
            Object builder = builderClz.getConstructor(opcodes.getClass()).newInstance(opcodes);
            Method intern = null;
            for (Method m : builderClz.getMethods()) {
                if (m.getName().startsWith("internClass") && m.getParameterTypes().length == 1) {
                    intern = m;
                    break;
                }
            }
            if (intern != null) {
                for (Object cd : classes) intern.invoke(builder, cd);
            }
            // write via DexPool-like API on builder - often extends DexPool
            Method writeTo = null;
            for (Method m : builderClz.getMethods()) {
                if ("writeTo".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    writeTo = m;
                    break;
                }
            }
            if (writeTo != null) {
                for (String cn : new String[] {
                        "com.android.tools.smali.dexlib2.writer.io.MemoryDataStore",
                        "org.jf.dexlib2.writer.io.MemoryDataStore"
                }) {
                    try {
                        Class<?> sc = Class.forName(cn);
                        Object store = sc.getDeclaredConstructor().newInstance();
                        writeTo.invoke(builder, store);
                        Method getData = sc.getMethod("getData");
                        Object data = getData.invoke(store);
                        if (data instanceof byte[]) {
                            details.add("write=DexBuilder+MemoryDataStore");
                            return (byte[]) data;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            details.add("DexBuilder failed: " + e.getMessage());
        }
        return null;
    }

    private static String classToSmali(DexClass dexClass) throws Exception {
        try {
            Method m = DexClass.class.getMethod("toSmali");
            Object r = m.invoke(dexClass);
            if (r != null) return String.valueOf(r);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            StringWriter sw = new StringWriter();
            Method m = DexClass.class.getMethod("writeSmali", Writer.class);
            m.invoke(dexClass, sw);
            return sw.toString();
        } catch (NoSuchMethodException ignored) {
        }
        try {
            StringBuilder sb = new StringBuilder();
            Method m = DexClass.class.getMethod("writeSmali", Appendable.class);
            m.invoke(dexClass, sb);
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("toSmali/writeSmali 不可用: " + e.getMessage(), e);
        }
    }

    // ---------------- dex host ----------------

    private static final class HostDex {
        final String dexName;
        final byte[] bytes;

        HostDex(String dexName, byte[] bytes) {
            this.dexName = dexName;
            this.bytes = bytes;
        }
    }

    private static HostDex findHostDex(
            List<DexFileInputSource> dexSources,
            String descriptor,
            String preferDex
    ) throws Exception {
        TypeKey typeKey = TypeKey.create(descriptor);
        List<DexFileInputSource> ordered = new ArrayList<>(dexSources);
        if (preferDex != null && !preferDex.trim().isEmpty()) {
            final String want = preferDex.trim();
            ordered.sort((a, b) -> {
                boolean aa = want.equals(dexSourceName(a));
                boolean bb = want.equals(dexSourceName(b));
                if (aa == bb) return 0;
                return aa ? -1 : 1;
            });
        }
        for (DexFileInputSource src : ordered) {
            String name = dexSourceName(src);
            byte[] bytes;
            try (InputStream in = src.openStream()) {
                bytes = readAll(in);
            }
            DexFile dexFile = DexFile.read(bytes);
            try {
                dexFile.setSimpleName(name);
                DexClass c = typeKey != null ? dexFile.getDexClass(typeKey) : null;
                if (c != null) {
                    return new HostDex(name, bytes);
                }
            } finally {
                try {
                    dexFile.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static HostDex pickInjectHost(List<DexFileInputSource> dexSources, String preferDex)
            throws Exception {
        if (preferDex != null && !preferDex.trim().isEmpty()) {
            String want = preferDex.trim();
            for (DexFileInputSource src : dexSources) {
                if (want.equals(dexSourceName(src))) {
                    try (InputStream in = src.openStream()) {
                        return new HostDex(want, readAll(in));
                    }
                }
            }
        }
        DexFileInputSource last = dexSources.get(dexSources.size() - 1);
        String name = dexSourceName(last);
        try (InputStream in = last.openStream()) {
            return new HostDex(name, readAll(in));
        }
    }

    private static String dexSourceName(DexFileInputSource src) {
        String n = src.getAlias();
        if (n == null || n.isEmpty()) n = src.getName();
        return n != null ? n : "classes.dex";
    }

    // ---------------- helpers ----------------

    public static String extractClassDescriptor(String smali) {
        if (smali == null) return null;
        Matcher m = CLASS_LINE.matcher(smali);
        if (m.find()) {
            return "L" + m.group(1) + ";";
        }
        for (String line : smali.split("\n")) {
            String t = line.trim();
            if (!t.startsWith(".class")) continue;
            int l = t.indexOf('L');
            int sc = t.indexOf(';', l);
            if (l >= 0 && sc > l) {
                return t.substring(l, sc + 1);
            }
        }
        return null;
    }

    public static String normalizeDescriptor(String className) {
        if (className == null) throw new IllegalArgumentException("class 不能为空");
        String s = className.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("class 不能为空");
        if (s.startsWith("L") && s.endsWith(";")) return s;
        if (s.contains("/")) {
            if (!s.startsWith("L")) s = "L" + s;
            if (!s.endsWith(";")) s = s + ";";
            return s;
        }
        return "L" + s.replace('.', '/') + ";";
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) return "replace";
        String m = mode.trim().toLowerCase(Locale.US);
        if ("upsert".equals(m) || "add".equals(m) || "create".equals(m) || "inject".equals(m)) {
            return "upsert";
        }
        return "replace";
    }

    private static SmaliReader newSmaliReader(String source) throws Exception {
        try {
            Method of = SmaliReader.class.getMethod("of", String.class);
            Object r = of.invoke(null, source);
            if (r instanceof SmaliReader) return (SmaliReader) r;
        } catch (Exception ignored) {
        }
        try {
            Constructor<SmaliReader> c = SmaliReader.class.getConstructor(String.class);
            return c.newInstance(source);
        } catch (Exception ignored) {
        }
        Constructor<SmaliReader> c = SmaliReader.class.getConstructor(char[].class);
        return c.newInstance((Object) source.toCharArray());
    }

    private static Exception unwrap(Exception e) {
        Throwable t = e;
        if (e instanceof java.lang.reflect.InvocationTargetException
                && e.getCause() != null) {
            t = e.getCause();
        }
        if (t instanceof Exception) return (Exception) t;
        return new Exception(t);
    }

    private static String safeMsg(Exception e) {
        Exception u = unwrap(e);
        String m = u.getMessage();
        return m != null ? m : u.getClass().getSimpleName();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * Export all classes of a single DEX entry to a smali directory tree via ARSCLib writeSmali.
     * @return number of classes written (best-effort)
     */
    public static int exportSmaliDirectory(
            ApkModule module,
            String dexEntry,
            File outDir,
            SimpleApkLogger logger
    ) throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        if (outDir == null) throw new IllegalArgumentException("outDir is null");
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + outDir);
        }
        List<DexFileInputSource> sources = module.listDexFiles();
        if (sources == null || sources.isEmpty()) throw new IllegalStateException("无 DEX");
        DexFileInputSource chosen = null;
        if (dexEntry != null && !dexEntry.trim().isEmpty()) {
            String want = dexEntry.trim();
            for (DexFileInputSource s : sources) {
                String n = s.getAlias();
                if (n == null || n.isEmpty()) n = s.getName();
                if (want.equals(n) || want.equals(new File(n).getName())) {
                    chosen = s;
                    break;
                }
            }
            if (chosen == null) throw new IllegalArgumentException("找不到 dex: " + want);
        } else {
            chosen = sources.get(0);
        }
        String dexName = chosen.getAlias();
        if (dexName == null || dexName.isEmpty()) dexName = chosen.getName();
        byte[] bytes;
        try (InputStream in = chosen.openStream()) {
            bytes = readAll(in);
        }
        DexFile dexFile = DexFile.read(bytes);
        try {
            dexFile.setSimpleName(dexName);
            // writeSmali variants
            try {
                Method m = DexFile.class.getMethod("writeSmali", File.class);
                m.invoke(dexFile, outDir);
                int c = countSmaliFiles(outDir);
                if (c > 0) {
                    if (logger != null) {
                        logger.ok("smali 目录已导出", "smali dir exported",
                                dexName + " → " + outDir.getAbsolutePath());
                    }
                    return c;
                }
            } catch (Exception ignored) {
            }
            try {
                // writeSmali(SmaliWriterSetting, File) or (SmaliWriter, File)
                for (Method m : DexFile.class.getMethods()) {
                    if (!"writeSmali".equals(m.getName()) || m.getParameterTypes().length != 2) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p[1] != File.class) continue;
                    Object first = p[0].getDeclaredConstructor().newInstance();
                    m.invoke(dexFile, first, outDir);
                    int c = countSmaliFiles(outDir);
                    if (c > 0) {
                        if (logger != null) {
                            logger.ok("smali 目录已导出", "smali dir exported",
                                    m.toGenericString());
                        }
                        return c;
                    }
                }
            } catch (Exception ignored) {
            }
            // fallback: iterate classes and write each
            int count = 0;
            Exception last = null;
            String[] names = {
                    "getDexClasses", "getClonedDexClasses", "classes",
                    "iterator", "getDexClassesCloned"
            };
            for (String name : names) {
                try {
                    Method cl = null;
                    for (Method m : DexFile.class.getMethods()) {
                        if (name.equals(m.getName()) && m.getParameterTypes().length == 0) {
                            cl = m;
                            break;
                        }
                    }
                    if (cl == null) continue;
                    Object iterable = cl.invoke(dexFile);
                    if (iterable instanceof Iterable) {
                        for (Object o : (Iterable<?>) iterable) {
                            if (o instanceof DexClass) {
                                writeOneClassSmali((DexClass) o, outDir);
                                count++;
                            }
                        }
                        if (count > 0) break;
                    } else if (iterable instanceof java.util.Iterator) {
                        java.util.Iterator<?> it = (java.util.Iterator<?>) iterable;
                        while (it.hasNext()) {
                            Object o = it.next();
                            if (o instanceof DexClass) {
                                writeOneClassSmali((DexClass) o, outDir);
                                count++;
                            }
                        }
                        if (count > 0) break;
                    }
                } catch (Exception e1) {
                    last = e1;
                }
            }
            // Also try DexFile.writeSmali(File, ...) overloads via reflection already done.
            // Last resort: use baksmali path is outside this class.
            if (count == 0 && last != null && logger != null) {
                logger.warn("整包 smali 导出 API 受限", "full smali export limited",
                        last.getMessage());
            }
            if (logger != null) {
                logger.ok("smali 目录导出", "smali dir export",
                        count + " classes → " + outDir.getAbsolutePath());
            }
            return count;
        } finally {
            try { dexFile.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Parse a directory of .smali files and merge/replace into the module (upsert each class).
     */
    public static int importSmaliDirectory(
            ApkModule module,
            File smaliDir,
            String mode,
            String preferDex,
            SimpleApkLogger logger
    ) throws Exception {
        if (smaliDir == null || !smaliDir.isDirectory()) {
            throw new IllegalArgumentException("smali 目录不存在: " + smaliDir);
        }
        List<File> files = new ArrayList<>();
        collectSmaliFiles(smaliDir, files);
        if (files.isEmpty()) {
            throw new IllegalStateException("目录中无 .smali: " + smaliDir);
        }
        if (logger != null) {
            logger.stage("导入 smali 目录", "Import smali directory");
            logger.bi("文件数", "Files", String.valueOf(files.size()));
        }
        int ok = 0;
        List<String> errors = new ArrayList<>();
        for (File f : files) {
            try {
                applySmaliFile(module, f, mode != null ? mode : "upsert", preferDex, logger);
                ok++;
            } catch (Exception e) {
                errors.add(f.getName() + ": " + e.getMessage());
                if (logger != null) {
                    logger.warn("导入失败", "import failed", f.getName() + " " + e.getMessage());
                }
            }
        }
        if (logger != null) {
            logger.ok("smali 目录导入完成", "smali dir import done",
                    "ok=" + ok + "/" + files.size()
                            + (errors.isEmpty() ? "" : " errors=" + errors.size()));
        }
        if (ok == 0) {
            throw new IllegalStateException("全部导入失败: " + errors);
        }
        return ok;
    }

    private static void collectSmaliFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) collectSmaliFiles(f, out);
            else if (f.getName().toLowerCase(Locale.US).endsWith(".smali")) out.add(f);
        }
    }

    private static int countSmaliFiles(File dir) {
        List<File> list = new ArrayList<>();
        collectSmaliFiles(dir, list);
        return list.size();
    }

    private static void writeOneClassSmali(DexClass dexClass, File outDir) throws Exception {
        String smali = classToSmali(dexClass);
        String desc = null;
        try {
            Object key = dexClass.getKey();
            if (key != null) desc = String.valueOf(key);
        } catch (Exception ignored) {
        }
        if (desc == null || !desc.startsWith("L")) {
            desc = extractClassDescriptor(smali);
        }
        if (desc == null) return;
        String rel = desc.substring(1, desc.length() - 1) + ".smali";
        File out = new File(outDir, rel);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Files.write(out.toPath(), smali.getBytes(StandardCharsets.UTF_8));
    }

}
