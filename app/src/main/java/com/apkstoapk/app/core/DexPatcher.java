package com.apkstoapk.app.core;

import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.DexFileInputSource;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.dex.data.CodeItem;
import com.reandroid.dex.data.InstructionList;
import com.reandroid.dex.data.MethodDef;
import com.reandroid.dex.ins.ConstString;
import com.reandroid.dex.ins.Ins;
import com.reandroid.dex.ins.Ins35c;
import com.reandroid.dex.ins.Opcode;
import com.reandroid.dex.ins.SizeXIns;
import com.reandroid.dex.key.Key;
import com.reandroid.dex.key.MethodKey;
import com.reandroid.dex.key.ProtoKey;
import com.reandroid.dex.key.TypeKey;
import com.reandroid.dex.model.DexClass;
import com.reandroid.dex.model.DexFile;
import com.reandroid.dex.model.DexMethod;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Patch DEX inside merged ApkModule.
 *
 * Strategies:
 * 1) Clear DetectionPopup methods (show overloads + finishApp) to return-void
 * 2) In ApplicationMain, delete any invoke line whose target method name is "p" (->p)
 * 3) In UnityPlayerActivity.onCreate, insert System.loadLibrary("Widget") at method start
 */
public final class DexPatcher {
    public static final String DEFAULT_CLASS = "Lcom/siem/ms7/DetectionPopup;";
    public static final String CLASS_APPLICATION_MAIN = "Landroid/support/v4/soft/ApplicationMain;";
    public static final String CLASS_UNITY_PLAYER_ACTIVITY = "Lcom/unity3d/player/UnityPlayerActivity;";
    public static final String METHOD_ON_CREATE = "onCreate";
    public static final String PROTO_ON_CREATE = "(Landroid/os/Bundle;)V";
    public static final String LOAD_LIBRARY = "Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V";
    public static final String LOAD_LIB_NAME = "Widget";

    public static final String PROTO_SHOW_0 = "()V";
    public static final String PROTO_SHOW_6 =
            "(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/CharSequence;)V";
    public static final String PROTO_SHOW_4 =
            "(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/CharSequence;)V";
    public static final String PROTO_FINISH_APP = "(Landroid/content/Context;)V";

    public static class Target {
        public final String classDescriptor;
        public final String methodName;
        public final String proto;

        public Target(String classDescriptor, String methodName, String proto) {
            this.classDescriptor = classDescriptor;
            this.methodName = methodName;
            this.proto = proto;
        }

        public static Target show0() {
            return new Target(DEFAULT_CLASS, "show", PROTO_SHOW_0);
        }

        public static Target show6() {
            return new Target(DEFAULT_CLASS, "show", PROTO_SHOW_6);
        }

        public static Target show4() {
            return new Target(DEFAULT_CLASS, "show", PROTO_SHOW_4);
        }

        public static Target finishApp() {
            return new Target(DEFAULT_CLASS, "finishApp", PROTO_FINISH_APP);
        }

        /** @deprecated use {@link DexPatcher#defaultClearTargets()} */
        @Deprecated
        public static Target detectionPopupShow() {
            return show0();
        }

        @Override
        public String toString() {
            return classDescriptor + "->" + methodName + proto;
        }
    }

    public static class Result {
        public final int methodsCleared;
        public final int invokePRemoved;
        public final int loadLibraryInjected;
        public final List<String> details;

        public Result(int methodsCleared, int invokePRemoved, int loadLibraryInjected, List<String> details) {
            this.methodsCleared = methodsCleared;
            this.invokePRemoved = invokePRemoved;
            this.loadLibraryInjected = loadLibraryInjected;
            this.details = details;
        }

        public int totalChanges() {
            return methodsCleared + invokePRemoved + loadLibraryInjected;
        }
    }

    public static List<Target> defaultClearTargets() {
        List<Target> list = new ArrayList<>(4);
        list.add(Target.show0());
        list.add(Target.show6());
        list.add(Target.show4());
        list.add(Target.finishApp());
        return list;
    }

    /** Backward compatible alias. */
    public static List<Target> defaultTargets() {
        return defaultClearTargets();
    }

    private DexPatcher() {}

    /**
     * Apply default strategies:
     * - clear DetectionPopup methods
     * - remove ApplicationMain ->p invokes
     * - inject loadLibrary("Widget") into UnityPlayerActivity.onCreate
     */
    public static Result applyDefault(ApkModule module, SimpleApkLogger logger) throws Exception {
        return apply(module, logger, defaultClearTargets(), true, true);
    }

    public static Result apply(
            ApkModule module,
            SimpleApkLogger logger,
            List<Target> clearTargets,
            boolean removeInvokeP,
            boolean injectLoadLibrary
    ) throws Exception {
        if (module == null) {
            throw new IllegalArgumentException("module is null");
        }
        if (logger != null) {
            logger.stage("编辑 DEX", "Edit DEX");
            if (clearTargets != null) {
                for (Target t : clearTargets) {
                    logger.item("清空目标", "Clear target", t.toString());
                }
            }
            if (removeInvokeP) {
                logger.item("删除调用", "Remove invoke",
                        CLASS_APPLICATION_MAIN + " any ->p(...)");
            }
            if (injectLoadLibrary) {
                logger.item("插入代码", "Inject code",
                        CLASS_UNITY_PLAYER_ACTIVITY + "->onCreate + loadLibrary(\"" + LOAD_LIB_NAME + "\")");
            }
        }

        List<DexFileInputSource> dexSources = module.listDexFiles();
        if (dexSources == null || dexSources.isEmpty()) {
            if (logger != null) {
                logger.warn("合并模块中没有 classes*.dex", "No classes*.dex in merged module");
            }
            return new Result(0, 0, 0, new ArrayList<>());
        }

        // Prefer processing classes5.dex first for Unity inject, but scan all.
        dexSources = sortDexSources(dexSources);

        int cleared = 0;
        int removedP = 0;
        int injected = 0;
        List<String> details = new ArrayList<>();

        for (DexFileInputSource dexSource : dexSources) {
            String dexName = dexSource.getAlias();
            if (dexName == null || dexName.isEmpty()) {
                dexName = dexSource.getName();
            }
            if (logger != null) {
                logger.bi("扫描 DEX", "Scanning DEX", dexName);
            }

            byte[] original;
            try (InputStream in = dexSource.openStream()) {
                original = readAll(in);
            }
            DexFile dexFile = DexFile.read(original);
            dexFile.setSimpleName(dexName);

            boolean changed = false;

            if (clearTargets != null) {
                for (Target target : clearTargets) {
                    int n = patchClearTarget(dexFile, target, logger, details, dexName);
                    if (n > 0) {
                        cleared += n;
                        changed = true;
                    }
                }
            }

            if (removeInvokeP) {
                int n = removeInvokePInApplicationMain(dexFile, logger, details, dexName);
                if (n > 0) {
                    removedP += n;
                    changed = true;
                }
            }

            if (injectLoadLibrary) {
                int n = injectLoadLibraryInUnityOnCreate(dexFile, logger, details, dexName);
                if (n > 0) {
                    injected += n;
                    changed = true;
                }
            }

            if (changed) {
                dexFile.refreshFull();
                byte[] outBytes = dexFile.getBytes();
                module.add(new ByteInputSource(outBytes, dexName));
                if (logger != null) {
                    logger.ok("已写回 DEX", "DEX written back",
                            dexName + " (" + outBytes.length + " bytes)");
                }
            } else if (logger != null) {
                logger.item("此 DEX 无改动", "No changes in this DEX", dexName);
            }

            try {
                dexFile.close();
            } catch (Exception ignored) {
            }
        }

        if (logger != null) {
            logger.ok("DEX 补丁完成", "DEX patch finished",
                    "clear=" + cleared + ", removeP=" + removedP + ", inject=" + injected);
            if (cleared + removedP + injected == 0) {
                logger.warn("未应用任何 DEX 改动", "No DEX changes applied");
            }
        }
        return new Result(cleared, removedP, injected, details);
    }

    /** Backward compatible entry used previously. */
    public static Result clearMethods(ApkModule module, SimpleApkLogger logger, List<Target> targets)
            throws Exception {
        return apply(module, logger, targets, true, true);
    }

    // ---------------- strategy 1: clear methods ----------------

    private static int patchClearTarget(
            DexFile dexFile,
            Target target,
            SimpleApkLogger logger,
            List<String> details,
            String dexName
    ) {
        TypeKey typeKey = TypeKey.create(normalizeClass(target.classDescriptor));
        if (typeKey == null) {
            if (logger != null) {
                logger.warn("无效类描述符", "Invalid class descriptor", target.classDescriptor);
            }
            return 0;
        }
        DexClass dexClass = dexFile.getDexClass(typeKey);
        if (dexClass == null) {
            return 0;
        }

        MethodKey methodKey = buildMethodKey(typeKey, target);
        DexMethod method = dexClass.getDeclaredMethod(methodKey, true);
        if (method == null) {
            method = findMethodExact(dexClass, target.methodName, normalizeProto(target.proto));
        }
        if (method == null) {
            if (logger != null) {
                logger.item("未找到方法", "Method not found", target + " in " + dexName);
            }
            return 0;
        }

        String keyText = safeKey(method);
        String beforeIns = summarizeInstructions(method, 8);
        int expectedRegs = expectedRegisterCount(method);
        clearMethodBody(method);
        String afterIns = summarizeInstructions(method, 8);

        String msg = dexName + " :: CLEAR " + keyText;
        details.add(msg);
        if (logger != null) {
            logger.ok("已清空方法", "Method cleared", msg);
            logger.item("修改前指令", "Instructions before", beforeIns);
            logger.item("修改后指令", "Instructions after", afterIns);
            logger.item("期望 smali", "Expected smali",
                    ".method ... " + target.methodName + target.proto
                            + " / .registers " + expectedRegs + " / return-void");
        }
        return 1;
    }

    public static void clearMethodBody(DexMethod method) {
        if (method == null) return;
        MethodDef def = method.getDefinition();
        def.clearCode();

        CodeItem codeItem = def.getOrCreateCodeItem();
        codeItem.removeDebugInfo();
        codeItem.removeTryBlock();

        int paramRegs;
        try {
            paramRegs = method.getKey().getParameterRegistersCount();
            if (!method.isStatic()) {
                paramRegs += 1;
            }
        } catch (Exception ignored) {
            paramRegs = method.isStatic() ? 0 : 1;
        }
        if (!method.isStatic() && paramRegs < 1) {
            paramRegs = 1;
        }
        codeItem.setParameterRegistersCount(paramRegs);
        codeItem.setRegistersCount(paramRegs);

        InstructionList list = def.getOrCreateInstructionList();
        int guard = 0;
        while (!list.isEmpty() && guard++ < 10000) {
            Ins first = list.get(0);
            if (first == null || !list.remove(first, true)) {
                break;
            }
        }
        list.createNext(Opcode.RETURN_VOID);
        method.refresh();
    }

    // ---------------- strategy 2: remove ->p invokes ----------------

    /**
     * In Landroid/support/v4/soft/ApplicationMain;, remove any invoke-* whose
     * target method name is exactly "p" (keyword: ->p). Full invoke line is not fixed.
     */
    private static int removeInvokePInApplicationMain(
            DexFile dexFile,
            SimpleApkLogger logger,
            List<String> details,
            String dexName
    ) {
        TypeKey typeKey = TypeKey.create(CLASS_APPLICATION_MAIN);
        DexClass dexClass = dexFile.getDexClass(typeKey);
        if (dexClass == null) {
            return 0;
        }

        int removed = 0;
        for (Iterator<DexMethod> it = dexClass.getDeclaredMethods(); it.hasNext(); ) {
            DexMethod method = it.next();
            if (method == null || method.getDefinition() == null) continue;
            MethodDef def = method.getDefinition();
            InstructionList list = def.getInstructionList();
            if (list == null || list.isEmpty()) continue;

            // Collect first to avoid concurrent modification.
            List<Ins> toRemove = new ArrayList<>();
            for (Ins ins : list) {
                if (isInvokeToMethodNamedP(ins)) {
                    toRemove.add(ins);
                }
            }
            for (Ins ins : toRemove) {
                String desc = describeIns(ins);
                if (list.remove(ins, true)) {
                    removed++;
                    String msg = dexName + " :: REMOVE " + safeKey(method) + " :: " + desc;
                    details.add(msg);
                    if (logger != null) {
                        logger.ok("已删除 ->p 调用", "Removed ->p invoke", msg);
                    }
                }
            }
            if (!toRemove.isEmpty()) {
                method.refresh();
            }
        }
        if (removed == 0 && logger != null) {
            logger.item("ApplicationMain 中未找到 ->p 调用",
                    "No ->p invokes found in ApplicationMain", dexName);
        }
        return removed;
    }

    private static boolean isInvokeToMethodNamedP(Ins ins) {
        if (ins == null || ins.getOpcode() == null) return false;
        String op = ins.getOpcode().getName();
        if (op == null || !op.startsWith("invoke-")) return false;
        if (!(ins instanceof SizeXIns)) return false;
        Key key = ((SizeXIns) ins).getKey();
        if (!(key instanceof MethodKey)) return false;
        MethodKey mk = (MethodKey) key;
        // keyword is method name "p"  <=>  ...->p(...)
        return "p".equals(mk.getName());
    }

    private static String describeIns(Ins ins) {
        try {
            if (ins instanceof SizeXIns) {
                Key key = ((SizeXIns) ins).getKey();
                return ins.getOpcode().getName() + " " + key;
            }
            return String.valueOf(ins.getOpcode());
        } catch (Exception e) {
            return String.valueOf(ins);
        }
    }

    // ---------------- strategy 3: inject loadLibrary ----------------

    /**
     * Insert at beginning of UnityPlayerActivity.onCreate:
     *   const-string v0, "Widget"
     *   invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
     */
    private static int injectLoadLibraryInUnityOnCreate(
            DexFile dexFile,
            SimpleApkLogger logger,
            List<String> details,
            String dexName
    ) {
        TypeKey typeKey = TypeKey.create(CLASS_UNITY_PLAYER_ACTIVITY);
        DexClass dexClass = dexFile.getDexClass(typeKey);
        if (dexClass == null) {
            return 0;
        }

        MethodKey onCreateKey = MethodKey.parse(
                CLASS_UNITY_PLAYER_ACTIVITY + "->" + METHOD_ON_CREATE + PROTO_ON_CREATE);
        DexMethod method = dexClass.getDeclaredMethod(onCreateKey, true);
        if (method == null) {
            method = findMethodExact(dexClass, METHOD_ON_CREATE, PROTO_ON_CREATE);
        }
        if (method == null) {
            if (logger != null) {
                logger.item("未找到 Unity onCreate", "Unity onCreate not found", dexName);
            }
            return 0;
        }

        // Skip if already injected
        if (alreadyHasLoadLibraryWidget(method)) {
            if (logger != null) {
                logger.bi("已存在 loadLibrary(\"Widget\")，跳过插入",
                        "loadLibrary(\"Widget\") already present, skip", dexName);
            }
            return 0;
        }

        MethodDef def = method.getDefinition();
        CodeItem codeItem = def.getOrCreateCodeItem();
        // Ensure at least 4 registers as original sample (.registers 4): p0,p1 + locals
        int regs = codeItem.getRegistersCount();
        int params = codeItem.getParameterRegistersCount();
        int locals = Math.max(0, regs - params);
        if (locals < 1) {
            // need v0 local for const-string
            codeItem.setRegistersCount(params + 1);
        }

        InstructionList list = def.getOrCreateInstructionList();
        // Insert at method head (index 0,1)
        ConstString constString = list.createStringAt(0, 0, LOAD_LIB_NAME); // v0 = "Widget"
        // Ensure it's using v0
        try {
            constString.setRegister(0);
            constString.setString(LOAD_LIB_NAME);
        } catch (Exception ignored) {
        }

        Ins35c invoke = list.createAt(1, Opcode.INVOKE_STATIC);
        MethodKey loadKey = MethodKey.parse(LOAD_LIBRARY);
        if (loadKey == null) {
            loadKey = MethodKey.create(
                    TypeKey.create("Ljava/lang/System;"),
                    "loadLibrary",
                    ProtoKey.create(TypeKey.TYPE_V, TypeKey.create("Ljava/lang/String;"))
            );
        }
        invoke.setKey(loadKey);
        invoke.setRegistersCount(1);
        invoke.setRegister(0, 0); // {v0}

        method.refresh();

        String msg = dexName + " :: INJECT " + safeKey(method)
                + " head loadLibrary(\"" + LOAD_LIB_NAME + "\")";
        details.add(msg);
        if (logger != null) {
            logger.ok("已插入 loadLibrary", "Injected loadLibrary", msg);
            logger.item("插入内容", "Injected code",
                    "const-string v0, \"" + LOAD_LIB_NAME + "\"; "
                            + "invoke-static {v0}, " + LOAD_LIBRARY);
        }
        return 1;
    }

    private static boolean alreadyHasLoadLibraryWidget(DexMethod method) {
        try {
            MethodDef def = method.getDefinition();
            if (def == null || def.getInstructionList() == null) return false;
            for (Ins ins : def.getInstructionList()) {
                if (!(ins instanceof SizeXIns)) continue;
                if (ins.getOpcode() != Opcode.INVOKE_STATIC
                        && ins.getOpcode() != Opcode.INVOKE_STATIC_RANGE) {
                    continue;
                }
                Key key = ((SizeXIns) ins).getKey();
                if (!(key instanceof MethodKey)) continue;
                MethodKey mk = (MethodKey) key;
                if ("loadLibrary".equals(mk.getName())
                        && mk.getDeclaring() != null
                        && "Ljava/lang/System;".equals(mk.getDeclaring().getTypeName())) {
                    // good enough: loadLibrary already present near head
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ---------------- helpers ----------------

    private static List<DexFileInputSource> sortDexSources(List<DexFileInputSource> sources) {
        List<DexFileInputSource> copy = new ArrayList<>(sources);
        copy.sort((a, b) -> {
            String na = a.getAlias() != null ? a.getAlias() : a.getName();
            String nb = b.getAlias() != null ? b.getAlias() : b.getName();
            // classes5.dex first for Unity strategy preference
            boolean a5 = "classes5.dex".equals(na);
            boolean b5 = "classes5.dex".equals(nb);
            if (a5 != b5) return a5 ? -1 : 1;
            return String.valueOf(na).compareTo(String.valueOf(nb));
        });
        return copy;
    }

    private static MethodKey buildMethodKey(TypeKey typeKey, Target target) {
        String protoText = normalizeProto(target.proto);
        MethodKey parsed = MethodKey.parse(typeKey.getTypeName() + "->" + target.methodName + protoText);
        if (parsed != null) {
            return parsed;
        }
        ProtoKey protoKey = ProtoKey.parse(protoText, 0);
        if (protoKey == null) {
            protoKey = ProtoKey.emptyParameters(TypeKey.TYPE_V);
        }
        return MethodKey.create(typeKey, target.methodName, protoKey);
    }

    private static DexMethod findMethodExact(DexClass dexClass, String name, String proto) {
        if (dexClass == null || name == null || proto == null) return null;
        for (Iterator<DexMethod> it = dexClass.getDeclaredMethods(); it.hasNext(); ) {
            DexMethod m = it.next();
            if (m == null || !name.equals(m.getName())) continue;
            MethodKey key = m.getKey();
            if (key == null || key.getProto() == null) continue;
            String p = key.getProto().toString();
            if (proto.equals(p) || proto.equals(normalizeProto(p))) {
                return m;
            }
            String full = String.valueOf(key);
            if (full.endsWith(name + proto) || full.contains("->" + name + proto)) {
                return m;
            }
        }
        return null;
    }

    private static int expectedRegisterCount(DexMethod method) {
        try {
            int paramRegs = method.getKey().getParameterRegistersCount();
            if (!method.isStatic()) {
                paramRegs += 1;
            }
            return Math.max(paramRegs, method.isStatic() ? 0 : 1);
        } catch (Exception e) {
            return method.isStatic() ? 0 : 1;
        }
    }

    private static String summarizeInstructions(DexMethod method, int max) {
        try {
            MethodDef def = method.getDefinition();
            if (def.getCodeItem() == null) {
                return "<no-code>";
            }
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (Iterator<Ins> it = def.getInstructions(); it.hasNext() && i < max; i++) {
                Ins ins = it.next();
                if (i > 0) sb.append("; ");
                sb.append(ins.getOpcode() != null ? ins.getOpcode().getName() : "?");
            }
            try {
                if (def.getInstructionsCount() > max) {
                    sb.append("; ...");
                }
            } catch (Exception ignored) {
            }
            try {
                CodeItem code = def.getCodeItem();
                if (code != null) {
                    sb.append(" | regs=").append(code.getRegistersCount())
                            .append(", params=").append(code.getParameterRegistersCount());
                }
            } catch (Exception ignored) {
            }
            return sb.toString();
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    private static String safeKey(DexMethod method) {
        try {
            return String.valueOf(method.getKey());
        } catch (Exception e) {
            return method.getName();
        }
    }

    private static String normalizeClass(String desc) {
        if (desc == null) return null;
        String s = desc.trim();
        if (s.contains(".") && !s.startsWith("L")) {
            s = "L" + s.replace('.', '/') + ";";
        }
        if (!s.startsWith("L")) {
            s = "L" + s;
        }
        if (!s.endsWith(";")) {
            s = s + ";";
        }
        return s;
    }

    private static String normalizeProto(String proto) {
        if (proto == null || proto.isEmpty()) return "()V";
        String p = proto.trim();
        if (!p.startsWith("(")) {
            p = "()" + p;
        }
        return p;
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
