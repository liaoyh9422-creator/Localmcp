package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.DexFileInputSource;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.dex.key.FieldKey;
import com.reandroid.dex.key.MethodKey;
import com.reandroid.dex.key.TypeKey;
import com.reandroid.dex.model.DexClass;
import com.reandroid.dex.model.DexFile;
import com.reandroid.dex.model.DexMethod;
import com.reandroid.dex.refactor.RenameFields;
import com.reandroid.dex.refactor.RenameMethods;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Method / field rename across all classes*.dex via ARSCLib RenameMethods / RenameFields.
 */
public final class DexRenameOps {
    private DexRenameOps() {}

    public static final class Result {
        public final int dexTouched;
        public final int applied;
        public final List<String> details;

        public Result(int dexTouched, int applied, List<String> details) {
            this.dexTouched = dexTouched;
            this.applied = applied;
            this.details = details != null ? details : new ArrayList<String>();
        }
    }

    public static Result renameMethod(
            ApkModule module,
            String className,
            String oldMethod,
            String newMethod,
            String proto,
            SimpleApkLogger logger
    ) throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        String cls = SmaliCompileOps.normalizeDescriptor(className);
        if (oldMethod == null || oldMethod.trim().isEmpty()) {
            throw new IllegalArgumentException("old_method 不能为空");
        }
        if (newMethod == null || newMethod.trim().isEmpty()) {
            throw new IllegalArgumentException("new_method 不能为空");
        }
        String oldName = oldMethod.trim();
        String newName = newMethod.trim();
        String protoNorm = normalizeProto(proto);

        if (logger != null) {
            logger.stage("重命名方法", "Rename method");
            logger.bi("类", "Class", cls);
            logger.bi("方法", "Method", oldName + protoNorm + " → " + newName);
        }

        List<String> details = new ArrayList<>();
        int dexTouched = 0;
        int applied = 0;
        List<DexFileInputSource> sources = module.listDexFiles();
        if (sources == null || sources.isEmpty()) {
            throw new IllegalStateException("无 DEX");
        }

        TypeKey typeKey = TypeKey.create(cls);
        for (DexFileInputSource src : sources) {
            String dexName = src.getAlias();
            if (dexName == null || dexName.isEmpty()) dexName = src.getName();
            byte[] bytes;
            try (InputStream in = src.openStream()) {
                bytes = readAll(in);
            }
            DexFile dexFile = DexFile.read(bytes);
            try {
                dexFile.setSimpleName(dexName);
                DexClass dexClass = typeKey != null ? dexFile.getDexClass(typeKey) : null;
                if (dexClass == null) continue;

                MethodKey oldKey = buildMethodKey(typeKey, oldName, protoNorm);
                DexMethod found = null;
                if (oldKey != null) {
                    try {
                        found = dexClass.getDeclaredMethod(oldKey, true);
                    } catch (Exception ignored) {
                    }
                }
                if (found == null) {
                    found = findMethod(dexClass, oldName, protoNorm);
                }
                if (found == null) {
                    details.add(dexName + ": method not found " + oldName + protoNorm);
                    continue;
                }

                MethodKey fromKey = found.getKey();
                MethodKey toKey = MethodKey.create(
                        fromKey.getDeclaring(),
                        newName,
                        fromKey.getProto()
                );

                int n = applyRenameMethods(dexFile, fromKey, toKey, details);
                if (n <= 0) {
                    // fallback: setName on definition if API allows
                    n = fallbackRenameMethod(found, newName, details);
                }
                if (n > 0) {
                    applied += n;
                    dexTouched++;
                    try {
                        dexFile.refreshFull();
                    } catch (Exception e) {
                        try { dexFile.refresh(); } catch (Exception ignored) {}
                    }
                    byte[] out = dexFile.getBytes();
                    module.add(new ByteInputSource(out, dexName));
                    details.add(dexName + ": renamed " + oldName + " → " + newName
                            + " (" + out.length + " bytes)");
                    if (logger != null) {
                        logger.ok("方法已改名", "Method renamed",
                                dexName + " " + oldName + " → " + newName);
                    }
                }
            } finally {
                try { dexFile.close(); } catch (Exception ignored) {}
            }
        }

        if (applied == 0) {
            throw new IllegalStateException(
                    "未改名任何方法: " + cls + "->" + oldName + protoNorm);
        }
        if (logger != null) {
            logger.ok("方法改名完成", "Method rename done",
                    "dex=" + dexTouched + ", applied=" + applied);
        }
        return new Result(dexTouched, applied, details);
    }

    public static Result renameField(
            ApkModule module,
            String className,
            String oldField,
            String newField,
            String fieldType,
            SimpleApkLogger logger
    ) throws Exception {
        if (module == null) throw new IllegalArgumentException("module is null");
        String cls = SmaliCompileOps.normalizeDescriptor(className);
        if (oldField == null || oldField.trim().isEmpty()) {
            throw new IllegalArgumentException("old_field 不能为空");
        }
        if (newField == null || newField.trim().isEmpty()) {
            throw new IllegalArgumentException("new_field 不能为空");
        }
        String oldName = oldField.trim();
        String newName = newField.trim();
        String typeNorm = fieldType == null || fieldType.trim().isEmpty()
                ? null : normalizeFieldType(fieldType.trim());

        if (logger != null) {
            logger.stage("重命名字段", "Rename field");
            logger.bi("类", "Class", cls);
            logger.bi("字段", "Field", oldName + " → " + newName
                    + (typeNorm != null ? " type=" + typeNorm : ""));
        }

        List<String> details = new ArrayList<>();
        int dexTouched = 0;
        int applied = 0;
        List<DexFileInputSource> sources = module.listDexFiles();
        if (sources == null || sources.isEmpty()) {
            throw new IllegalStateException("无 DEX");
        }
        TypeKey typeKey = TypeKey.create(cls);

        for (DexFileInputSource src : sources) {
            String dexName = src.getAlias();
            if (dexName == null || dexName.isEmpty()) dexName = src.getName();
            byte[] bytes;
            try (InputStream in = src.openStream()) {
                bytes = readAll(in);
            }
            DexFile dexFile = DexFile.read(bytes);
            try {
                dexFile.setSimpleName(dexName);
                DexClass dexClass = typeKey != null ? dexFile.getDexClass(typeKey) : null;
                if (dexClass == null) continue;

                FieldKey fromKey = findFieldKey(dexClass, oldName, typeNorm);
                if (fromKey == null) {
                    details.add(dexName + ": field not found " + oldName);
                    continue;
                }
                FieldKey toKey = FieldKey.create(
                        fromKey.getDeclaring(),
                        newName,
                        fromKey.getType()
                );
                int n = applyRenameFields(dexFile, fromKey, toKey, details);
                if (n <= 0) {
                    n = fallbackRenameField(dexClass, oldName, newName, typeNorm, details);
                }
                if (n > 0) {
                    applied += n;
                    dexTouched++;
                    try {
                        dexFile.refreshFull();
                    } catch (Exception e) {
                        try { dexFile.refresh(); } catch (Exception ignored) {}
                    }
                    byte[] out = dexFile.getBytes();
                    module.add(new ByteInputSource(out, dexName));
                    details.add(dexName + ": field " + oldName + " → " + newName);
                    if (logger != null) {
                        logger.ok("字段已改名", "Field renamed",
                                dexName + " " + oldName + " → " + newName);
                    }
                }
            } finally {
                try { dexFile.close(); } catch (Exception ignored) {}
            }
        }
        if (applied == 0) {
            throw new IllegalStateException("未改名任何字段: " + cls + "->" + oldName);
        }
        if (logger != null) {
            logger.ok("字段改名完成", "Field rename done",
                    "dex=" + dexTouched + ", applied=" + applied);
        }
        return new Result(dexTouched, applied, details);
    }

    // -------- rename apply via ARSCLib --------

    private static int applyRenameMethods(
            DexFile dexFile,
            MethodKey from,
            MethodKey to,
            List<String> details
    ) {
        try {
            RenameMethods rm = new RenameMethods();
            // try add(from,to) / add(MethodKey,String) / put
            boolean added = false;
            for (Method m : RenameMethods.class.getMethods()) {
                if (!"add".equals(m.getName()) && !"put".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 2
                            && p[0].isAssignableFrom(MethodKey.class)
                            && p[1].isAssignableFrom(MethodKey.class)) {
                        m.invoke(rm, from, to);
                        added = true;
                        break;
                    }
                    if (p.length == 2
                            && p[0].isAssignableFrom(MethodKey.class)
                            && p[1] == String.class) {
                        m.invoke(rm, from, to.getName());
                        added = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!added) {
                details.add("RenameMethods.add API not matched");
                return 0;
            }
            // apply(DexFile) or apply(repository)
            for (Method m : RenameMethods.class.getMethods()) {
                if (!"apply".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1) {
                    try {
                        if (p[0].isInstance(dexFile)) {
                            m.invoke(rm, dexFile);
                            details.add("apply=RenameMethods.apply(DexFile)");
                            return 1;
                        }
                        // repository from dexFile
                        Method getRepo = DexFile.class.getMethod("getRootRepository");
                        Object repo = getRepo.invoke(dexFile);
                        if (p[0].isInstance(repo)) {
                            m.invoke(rm, repo);
                            details.add("apply=RenameMethods.apply(repository)");
                            return 1;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            details.add("RenameMethods.apply not invoked");
            return 0;
        } catch (Exception e) {
            details.add("RenameMethods failed: " + e.getMessage());
            return 0;
        }
    }

    private static int applyRenameFields(
            DexFile dexFile,
            FieldKey from,
            FieldKey to,
            List<String> details
    ) {
        try {
            RenameFields rf = new RenameFields();
            boolean added = false;
            for (Method m : RenameFields.class.getMethods()) {
                if (!"add".equals(m.getName()) && !"put".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 2
                            && p[0].isAssignableFrom(FieldKey.class)
                            && p[1].isAssignableFrom(FieldKey.class)) {
                        m.invoke(rf, from, to);
                        added = true;
                        break;
                    }
                    if (p.length == 2
                            && p[0].isAssignableFrom(FieldKey.class)
                            && p[1] == String.class) {
                        m.invoke(rf, from, to.getName());
                        added = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!added) {
                details.add("RenameFields.add API not matched");
                return 0;
            }
            for (Method m : RenameFields.class.getMethods()) {
                if (!"apply".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1) continue;
                try {
                    if (p[0].isInstance(dexFile)) {
                        m.invoke(rf, dexFile);
                        details.add("apply=RenameFields.apply(DexFile)");
                        return 1;
                    }
                    Method getRepo = DexFile.class.getMethod("getRootRepository");
                    Object repo = getRepo.invoke(dexFile);
                    if (p[0].isInstance(repo)) {
                        m.invoke(rf, repo);
                        details.add("apply=RenameFields.apply(repository)");
                        return 1;
                    }
                } catch (Exception ignored) {
                }
            }
            return 0;
        } catch (Exception e) {
            details.add("RenameFields failed: " + e.getMessage());
            return 0;
        }
    }

    private static int fallbackRenameMethod(DexMethod method, String newName, List<String> details) {
        try {
            Method setName = method.getClass().getMethod("setName", String.class);
            setName.invoke(method, newName);
            details.add("fallback=DexMethod.setName");
            return 1;
        } catch (Exception e) {
            details.add("fallback method rename failed: " + e.getMessage());
            return 0;
        }
    }

    private static int fallbackRenameField(
            DexClass dexClass,
            String oldName,
            String newName,
            String typeNorm,
            List<String> details
    ) {
        try {
            Object field = null;
            for (Method m : DexClass.class.getMethods()) {
                if (!"getDeclaredField".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 1 && p[0] == String.class) {
                        field = m.invoke(dexClass, oldName);
                        if (field != null) break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (field == null) return 0;
            Method setName = field.getClass().getMethod("setName", String.class);
            setName.invoke(field, newName);
            details.add("fallback=DexField.setName");
            return 1;
        } catch (Exception e) {
            details.add("fallback field rename failed: " + e.getMessage());
            return 0;
        }
    }

    // -------- helpers --------

    private static MethodKey buildMethodKey(TypeKey typeKey, String name, String proto) {
        try {
            String full = typeKey.getTypeName() + "->" + name + proto;
            MethodKey parsed = MethodKey.parse(full);
            if (parsed != null) return parsed;
        } catch (Exception ignored) {
        }
        try {
            return MethodKey.create(typeKey, name,
                    com.reandroid.dex.key.ProtoKey.parse(proto, 0));
        } catch (Exception e) {
            return null;
        }
    }

    private static DexMethod findMethod(DexClass dexClass, String name, String proto) {
        try {
            for (Iterator<DexMethod> it = dexClass.getDeclaredMethods(); it.hasNext(); ) {
                DexMethod m = it.next();
                if (m == null || !name.equals(m.getName())) continue;
                if (proto == null || proto.isEmpty() || "()V".equals(proto) && m.getKey() == null) {
                    return m;
                }
                MethodKey key = m.getKey();
                if (key == null || key.getProto() == null) continue;
                String p = key.getProto().toString();
                if (proto.equals(p) || proto.equals(normalizeProto(p))) return m;
                String full = String.valueOf(key);
                if (full.contains("->" + name + proto)) return m;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static FieldKey findFieldKey(DexClass dexClass, String name, String typeNorm) {
        try {
            Method getFields = null;
            for (Method m : DexClass.class.getMethods()) {
                if ("getDeclaredFields".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    getFields = m;
                    break;
                }
            }
            if (getFields != null) {
                Object itObj = getFields.invoke(dexClass);
                if (itObj instanceof Iterator) {
                    Iterator<?> it = (Iterator<?>) itObj;
                    while (it.hasNext()) {
                        Object f = it.next();
                        Method getKey = f.getClass().getMethod("getKey");
                        Object key = getKey.invoke(f);
                        if (!(key instanceof FieldKey)) continue;
                        FieldKey fk = (FieldKey) key;
                        if (!name.equals(fk.getName())) continue;
                        if (typeNorm == null) return fk;
                        if (fk.getType() != null
                                && typeNorm.equals(String.valueOf(fk.getType()))) {
                            return fk;
                        }
                        if (fk.getType() != null
                                && typeNorm.equals(fk.getType().getTypeName())) {
                            return fk;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            TypeKey declaring = dexClass.getKey();
            if (typeNorm != null) {
                return FieldKey.create(declaring, name, TypeKey.create(typeNorm));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String normalizeProto(String proto) {
        if (proto == null || proto.trim().isEmpty()) return "()V";
        String p = proto.trim();
        if (!p.startsWith("(")) p = "()" + p;
        return p;
    }

    private static String normalizeFieldType(String t) {
        String s = t.trim();
        if (s.startsWith("L") || s.length() == 1 || s.startsWith("[")) return s;
        if (s.contains(".")) return "L" + s.replace('.', '/') + ";";
        if (s.contains("/")) {
            if (!s.startsWith("L")) s = "L" + s;
            if (!s.endsWith(";")) s = s + ";";
            return s;
        }
        return s;
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
}
