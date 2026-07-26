package com.apkstoapk.app.runtime;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Rewrite LuaJC (stock Maven LuaJ) class bytes for a target host runtime.
 *
 * <p>{@link GgLuajTarget#STOCK}: AGG-style light fix — no {@code LuaInteger},
 * ints become {@code LuaLong} via {@code valueOf(J)}.
 *
 * <p>{@link GgLuajTarget#MODDED_GG}: map to obfuscated GG luaj (Nqfes 466.3 and
 * similar): package {@code luaj/**}, {@code Varargs→ap}, short method names.
 * Mapping is verified against Nqfes_466.3 base.apk.
 */
public final class AggLuajCompat {
    private static final String LUA_INTEGER = "org/luaj/vm2/LuaInteger";
    private static final String LUA_LONG = "org/luaj/vm2/LuaLong";
    private static final String LUA_VALUE = "org/luaj/vm2/LuaValue";
    private static final String LUA_STRING = "org/luaj/vm2/LuaString";
    private static final String VARARGS = "org/luaj/vm2/Varargs";
    private static final String GG_LUA_VALUE = "luaj/LuaValue";
    private static final String GG_LUA_STRING = "luaj/LuaString";
    private static final String GG_LUA_LONG = "luaj/LuaLong";
    private static final String GG_AP = "luaj/ap";
    private static final String GG_LUA_BOOLEAN = "luaj/LuaBoolean";
    private static final String GG_LUA_NIL = "luaj/LuaNil";

    /**
     * Stock LuaJ static fields on LuaValue → Nqfes short names
     * (from LuaValue.&lt;clinit&gt; putstatic order on Nqfes 466.3).
     */
    private static String mapGgFieldName(String ownerRaw, String fname) {
        if (fname == null) return null;
        String o = ownerRaw == null ? "" : ownerRaw.replace('.', '/');
        boolean onLuaValue = LUA_VALUE.equals(o) || GG_LUA_VALUE.equals(o)
                || "org/luaj/vm2/LuaValue".equals(o) || "luaj/LuaValue".equals(o);
        if (onLuaValue) {
            switch (fname) {
                case "NIL": return "u";
                case "TRUE": return "v";
                case "FALSE": return "w";
                case "NONE": return "x";
                case "ZERO": return "y";
                case "ONE": return "z";
                case "MINUSONE": return "A";
                case "NOVALS": return "B";
                case "ENV": return "C"; // best-effort; may be metatable string pool on Nqfes
                default: return fname;
            }
        }
        // LuaBoolean.TRUE/FALSE sometimes referenced
        boolean onBool = "org/luaj/vm2/LuaBoolean".equals(o) || GG_LUA_BOOLEAN.equals(o)
                || "luaj/LuaBoolean".equals(o);
        if (onBool && ("TRUE".equals(fname) || "FALSE".equals(fname) || "_TRUE".equals(fname) || "_FALSE".equals(fname))) {
            // prefer LuaValue.v / w
            return fname; // handled by owner remap + keep; LuaJC usually uses LuaValue.TRUE
        }
        // LuaNil.NIL / _NIL
        boolean onNil = "org/luaj/vm2/LuaNil".equals(o) || GG_LUA_NIL.equals(o) || "luaj/LuaNil".equals(o);
        if (onNil && ("NIL".equals(fname) || "_NIL".equals(fname))) {
            return "a"; // LuaNil.a
        }
        // Varargs.NONE in stock → often same as LuaValue.NONE usage
        if (("org/luaj/vm2/Varargs".equals(o) || GG_AP.equals(o) || "luaj/ap".equals(o))
                && "NONE".equals(fname)) {
            // no NONE on ap; rewrite field access owner to LuaValue.x at call site
            return "x";
        }
        return fname;
    }

    private AggLuajCompat() {}

    /** Default: STOCK (backward compatible). */
    public static byte[] rewriteClass(byte[] classBytes) {
        return rewriteClass(classBytes, GgLuajTarget.STOCK);
    }

    public static byte[] rewriteClass(byte[] classBytes, GgLuajTarget target) {
        if (classBytes == null || classBytes.length < 8) return classBytes;
        if (target == null) target = GgLuajTarget.STOCK;
        if (target == GgLuajTarget.MODDED_GG) {
            return rewriteModdedGg(classBytes);
        }
        return rewriteStock(classBytes);
    }

    // ------------------------------------------------------------------ STOCK
    private static byte[] rewriteStock(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name,
                        rewriteStockDesc(descriptor), rewriteStockSig(signature), exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        super.visitTypeInsn(opcode, mapStockType(type));
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        super.visitFieldInsn(opcode, mapStockType(owner), name, rewriteStockDesc(descriptor));
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        String o = mapStockType(owner);
                        String d = rewriteStockDesc(descriptor);
                        if (LUA_VALUE.equals(owner.replace('.', '/')) || LUA_VALUE.equals(o)) {
                            if ("valueOf".equals(name) && "(I)Lorg/luaj/vm2/LuaInteger;".equals(descriptor)) {
                                super.visitInsn(Opcodes.I2L);
                                super.visitMethodInsn(opcode, LUA_VALUE, "valueOf",
                                        "(J)Lorg/luaj/vm2/LuaLong;", isInterface);
                                return;
                            }
                            if ("valueOf".equals(name) && d.contains("LuaInteger")) {
                                d = d.replace("Lorg/luaj/vm2/LuaInteger;", "Lorg/luaj/vm2/LuaLong;");
                            }
                        }
                        if (LUA_INTEGER.equals(owner.replace('.', '/')) && "valueOf".equals(name)) {
                            if (descriptor.startsWith("(I)")) {
                                super.visitInsn(Opcodes.I2L);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, LUA_VALUE, "valueOf",
                                        "(J)Lorg/luaj/vm2/LuaLong;", false);
                                return;
                            }
                        }
                        super.visitMethodInsn(opcode, o, name, d, isInterface);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        Object[] args = bootstrapMethodArguments;
                        if (args != null) {
                            args = args.clone();
                            for (int i = 0; i < args.length; i++) {
                                if (args[i] instanceof Type) {
                                    args[i] = Type.getType(rewriteStockDesc(((Type) args[i]).getDescriptor()));
                                } else if (args[i] instanceof Handle) {
                                    Handle h = (Handle) args[i];
                                    args[i] = new Handle(h.getTag(), mapStockType(h.getOwner()), h.getName(),
                                            rewriteStockDesc(h.getDesc()), h.isInterface());
                                }
                            }
                        }
                        Handle bsm = bootstrapMethodHandle;
                        if (bsm != null) {
                            bsm = new Handle(bsm.getTag(), mapStockType(bsm.getOwner()), bsm.getName(),
                                    rewriteStockDesc(bsm.getDesc()), bsm.isInterface());
                        }
                        super.visitInvokeDynamicInsn(name, rewriteStockDesc(descriptor), bsm, args);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type) {
                            super.visitLdcInsn(Type.getType(rewriteStockDesc(((Type) value).getDescriptor())));
                        } else {
                            super.visitLdcInsn(value);
                        }
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                        super.visitMultiANewArrayInsn(rewriteStockDesc(descriptor), numDimensions);
                    }

                    @Override
                    public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                                   org.objectweb.asm.Label end,
                                                   org.objectweb.asm.Label handler,
                                                   String type) {
                        super.visitTryCatchBlock(start, end, handler, type == null ? null : mapStockType(type));
                    }

                    @Override
                    public void visitLocalVariable(String name, String descriptor, String signature,
                                                   org.objectweb.asm.Label start,
                                                   org.objectweb.asm.Label end, int index) {
                        super.visitLocalVariable(name, rewriteStockDesc(descriptor), rewriteStockSig(signature),
                                start, end, index);
                    }
                };
            }

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                String[] ifs = interfaces;
                if (ifs != null) {
                    ifs = ifs.clone();
                    for (int i = 0; i < ifs.length; i++) ifs[i] = mapStockType(ifs[i]);
                }
                super.visit(version, access, mapStockType(name), rewriteStockSig(signature),
                        mapStockType(superName), ifs);
            }

            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                             String signature, Object value) {
                return super.visitField(access, name, rewriteStockDesc(descriptor), rewriteStockSig(signature), value);
            }

            @Override
            public void visitOuterClass(String owner, String name, String descriptor) {
                super.visitOuterClass(mapStockType(owner), name, rewriteStockDesc(descriptor));
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                super.visitInnerClass(mapStockType(name),
                        outerName == null ? null : mapStockType(outerName), innerName, access);
            }
        }, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    private static String mapStockType(String internal) {
        if (internal == null) return null;
        String n = internal.replace('.', '/');
        if (LUA_INTEGER.equals(n)) return LUA_LONG;
        if (n.contains(LUA_INTEGER)) {
            return n.replace("LuaInteger", "LuaLong");
        }
        return n;
    }

    private static String rewriteStockDesc(String desc) {
        if (desc == null) return null;
        return desc.replace("Lorg/luaj/vm2/LuaInteger;", "Lorg/luaj/vm2/LuaLong;");
    }

    private static String rewriteStockSig(String sig) {
        return rewriteStockDesc(sig);
    }

    // --------------------------------------------------------------- MODDED GG
    private static byte[] rewriteModdedGg(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                String[] ifs = interfaces;
                if (ifs != null) {
                    ifs = ifs.clone();
                    for (int i = 0; i < ifs.length; i++) ifs[i] = mapGgType(ifs[i]);
                }
                // entry class names stay; only type refs in super/interfaces remapped
                super.visit(version, access, name, rewriteGgSig(signature),
                        mapGgType(superName), ifs);
            }

            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                             String signature, Object value) {
                return super.visitField(access, name, rewriteGgDesc(descriptor), rewriteGgSig(signature), value);
            }

            @Override
            public void visitOuterClass(String owner, String name, String descriptor) {
                super.visitOuterClass(mapGgType(owner), name, rewriteGgDesc(descriptor));
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                // do not rename our own generated classes; only remap luaj types if present
                String n = name != null && name.startsWith("org/luaj/vm2") ? mapGgType(name) : name;
                String o = outerName != null && outerName.startsWith("org/luaj/vm2")
                        ? mapGgType(outerName) : outerName;
                super.visitInnerClass(n, o, innerName, access);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                String newName = mapGgMethodNameOnOurClass(name, descriptor);
                String newDesc = rewriteGgDesc(descriptor);
                // Generated entry: initupvalue1(env)V -> K(env)V (must be void)
                if ("K".equals(newName) && newDesc != null && newDesc.startsWith("(Lluaj/LuaValue;)")) {
                    newDesc = "(Lluaj/LuaValue;)V";
                }
                // onInvoke(Varargs)Varargs -> a_(ap)ap
                if ("a_".equals(newName) && newDesc != null) {
                    if (newDesc.contains("Lluaj/ap;") || newDesc.contains("Varargs")) {
                        newDesc = rewriteGgDesc(descriptor);
                    }
                }
                MethodVisitor mv = super.visitMethod(access, newName,
                        newDesc, rewriteGgSig(signature), exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        super.visitTypeInsn(opcode, mapGgType(type));
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                        String oRaw = owner == null ? "" : owner.replace('.', '/');
                        String mappedName = mapGgFieldName(oRaw, fname);
                        String o = mapGgType(owner);
                        String d = rewriteGgDesc(fdesc);
                        // Varargs.NONE / stock NONE on wrong owner → LuaValue.x
                        if ("NONE".equals(fname) && ("org/luaj/vm2/Varargs".equals(oRaw)
                                || "luaj/ap".equals(o) || GG_AP.equals(o)
                                || "org/luaj/vm2/LuaValue".equals(oRaw)
                                || GG_LUA_VALUE.equals(o) || LUA_VALUE.equals(oRaw))) {
                            o = GG_LUA_VALUE;
                            mappedName = "x";
                            d = "Lluaj/LuaValue;";
                        }
                        // NIL on LuaValue
                        if ("NIL".equals(fname) && (LUA_VALUE.equals(oRaw) || GG_LUA_VALUE.equals(o)
                                || "org/luaj/vm2/LuaValue".equals(oRaw))) {
                            o = GG_LUA_VALUE;
                            mappedName = "u";
                            d = "Lluaj/LuaValue;";
                        }
                        if ("TRUE".equals(fname) && (LUA_VALUE.equals(oRaw) || GG_LUA_VALUE.equals(o)
                                || "org/luaj/vm2/LuaValue".equals(oRaw))) {
                            o = GG_LUA_VALUE;
                            mappedName = "v";
                            d = "Lluaj/LuaBoolean;";
                        }
                        if ("FALSE".equals(fname) && (LUA_VALUE.equals(oRaw) || GG_LUA_VALUE.equals(o)
                                || "org/luaj/vm2/LuaValue".equals(oRaw))) {
                            o = GG_LUA_VALUE;
                            mappedName = "w";
                            d = "Lluaj/LuaBoolean;";
                        }
                        if ("ZERO".equals(fname) && (LUA_VALUE.equals(oRaw) || GG_LUA_VALUE.equals(o)
                                || "org/luaj/vm2/LuaValue".equals(oRaw))) {
                            o = GG_LUA_VALUE; mappedName = "y"; d = "Lluaj/LuaNumber;";
                        }
                        if ("ONE".equals(fname) && (LUA_VALUE.equals(oRaw) || GG_LUA_VALUE.equals(o)
                                || "org/luaj/vm2/LuaValue".equals(oRaw))) {
                            o = GG_LUA_VALUE; mappedName = "z"; d = "Lluaj/LuaNumber;";
                        }
                        if ("MINUSONE".equals(fname) && (LUA_VALUE.equals(oRaw) || GG_LUA_VALUE.equals(o)
                                || "org/luaj/vm2/LuaValue".equals(oRaw))) {
                            o = GG_LUA_VALUE; mappedName = "A"; d = "Lluaj/LuaNumber;";
                        }
                        if ("NOVALS".equals(fname) && (LUA_VALUE.equals(oRaw) || GG_LUA_VALUE.equals(o)
                                || "org/luaj/vm2/LuaValue".equals(oRaw))) {
                            o = GG_LUA_VALUE; mappedName = "B"; d = "[Lluaj/LuaValue;";
                        }
                        super.visitFieldInsn(opcode, o, mappedName, d);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                                String mdesc, boolean isInterface) {
                        String oRaw = owner == null ? "" : owner.replace('.', '/');
                        // Integer → Long factory for stock LuaJC patterns first
                        if (LUA_INTEGER.equals(oRaw) && "valueOf".equals(mname) && mdesc.startsWith("(I)")) {
                            super.visitInsn(Opcodes.I2L);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_VALUE, "d",
                                    "(J)Lluaj/LuaLong;", false);
                            return;
                        }
                        if (LUA_VALUE.equals(oRaw) && "valueOf".equals(mname)) {
                            // Nqfes: valueOf(I)->LuaNumber/LuaValue exists;
                            // NO valueOf(I)LuaLong. Prefer i2l + d(J) for int→long path
                            // (LuaJC/AGG often emit int constants via valueOf(I)).
                            if (mdesc != null && mdesc.startsWith("(I)")) {
                                // int → long factory: LuaValue.d(J)LuaLong
                                super.visitInsn(Opcodes.I2L);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_VALUE, "d",
                                        "(J)Lluaj/LuaLong;", false);
                                return;
                            }
                            if (mdesc != null && mdesc.startsWith("(J)")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_VALUE, "d",
                                        "(J)Lluaj/LuaLong;", false);
                                return;
                            }
                            if (mdesc != null && mdesc.startsWith("(D)")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_VALUE, "c",
                                        "(D)Lluaj/LuaNumber;", false);
                                return;
                            }
                            if (mdesc != null && mdesc.startsWith("(Z)")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_VALUE, "b",
                                        "(Z)Lluaj/LuaBoolean;", false);
                                return;
                            }
                            if (mdesc != null && mdesc.startsWith("(Ljava/lang/String;)")) {
                                // sometimes valueOf on LuaValue for strings → LuaString.c
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_STRING, "c",
                                        "(Ljava/lang/String;)Lluaj/LuaString;", false);
                                return;
                            }
                        }
                        if (LUA_STRING.equals(oRaw) && "valueOf".equals(mname)) {
                            if (mdesc.startsWith("(Ljava/lang/String;)")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_STRING, "c",
                                        "(Ljava/lang/String;)Lluaj/LuaString;", false);
                                return;
                            }
                            if (mdesc.startsWith("([B)")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_STRING, "a",
                                        rewriteGgDesc(mdesc), false);
                                return;
                            }
                        }
                        if (LUA_LONG.equals(oRaw) && "valueOf".equals(mname) && mdesc.startsWith("(J)")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_LONG, "b",
                                    "(J)Lluaj/LuaLong;", false);
                            return;
                        }

                        String o = mapGgType(owner);
                        String d = rewriteGgDesc(mdesc);
                        String mn = mapGgCalleeMethod(oRaw, o, mname, mdesc, d);

                        // --- Signature fixes for Nqfes (method name alone is not enough) ---

                        // valueOf(I)LuaLong does not exist → i2l + d(J)LuaLong
                        if (("valueOf".equals(mname) || "valueOf".equals(mn))
                                && d != null && d.startsWith("(I)")
                                && (o.startsWith("luaj/") || oRaw.startsWith("org/luaj/vm2"))) {
                            super.visitInsn(Opcodes.I2L);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_VALUE, "d",
                                    "(J)Lluaj/LuaLong;", false);
                            return;
                        }

                        // String factory: must be LuaString.c(String)LuaString  (NOT LuaValue return)
                        if (("valueOf".equals(mname) || "c".equals(mn))
                                && d != null && d.startsWith("(Ljava/lang/String;)")
                                && (LUA_STRING.equals(oRaw.replace('.', '/'))
                                || LUA_VALUE.equals(oRaw.replace('.', '/'))
                                || GG_LUA_STRING.equals(o)
                                || GG_LUA_VALUE.equals(o))) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_STRING, "c",
                                    "(Ljava/lang/String;)Lluaj/LuaString;", false);
                            return;
                        }

                        // long factory
                        if (("valueOf".equals(mname) || "d".equals(mn))
                                && d != null && d.startsWith("(J)")
                                && (o.startsWith("luaj/") || oRaw.startsWith("org/luaj/vm2"))) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GG_LUA_VALUE, "d",
                                    "(J)Lluaj/LuaLong;", false);
                            return;
                        }

                        // initupvalue1 / K must be void: K(LuaValue)V  — never K(LuaValue)LuaValue
                        if (("initupvalue1".equals(mname) || "K".equals(mn))
                                && d != null && d.startsWith("(Lluaj/LuaValue;)")) {
                            // definition side is V; call sites from host use V.
                            // If stock call used wrong return, force V.
                            if (!d.equals("(Lluaj/LuaValue;)V")) {
                                d = "(Lluaj/LuaValue;)V";
                            }
                            mn = "K";
                        }

                        // Never emit virtual K(LuaValue)LuaValue (does not exist on Nqfes)
                        if ("K".equals(mn) && "(Lluaj/LuaValue;)Lluaj/LuaValue;".equals(d)) {
                            d = "(Lluaj/LuaValue;)V";
                        }

                        super.visitMethodInsn(opcode, o, mn, d, isInterface);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        Object[] args = bootstrapMethodArguments;
                        if (args != null) {
                            args = args.clone();
                            for (int i = 0; i < args.length; i++) {
                                if (args[i] instanceof Type) {
                                    args[i] = Type.getType(rewriteGgDesc(((Type) args[i]).getDescriptor()));
                                } else if (args[i] instanceof Handle) {
                                    Handle h = (Handle) args[i];
                                    String ho = mapGgType(h.getOwner());
                                    String hd = rewriteGgDesc(h.getDesc());
                                    String hn = mapGgCalleeMethod(h.getOwner(), ho, h.getName(), h.getDesc(), hd);
                                    args[i] = new Handle(h.getTag(), ho, hn, hd, h.isInterface());
                                }
                            }
                        }
                        Handle bsm = bootstrapMethodHandle;
                        if (bsm != null) {
                            String bo = mapGgType(bsm.getOwner());
                            String bd = rewriteGgDesc(bsm.getDesc());
                            bsm = new Handle(bsm.getTag(), bo, bsm.getName(), bd, bsm.isInterface());
                        }
                        super.visitInvokeDynamicInsn(name, rewriteGgDesc(descriptor), bsm, args);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type) {
                            super.visitLdcInsn(Type.getType(rewriteGgDesc(((Type) value).getDescriptor())));
                        } else {
                            super.visitLdcInsn(value);
                        }
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                        super.visitMultiANewArrayInsn(rewriteGgDesc(descriptor), numDimensions);
                    }

                    @Override
                    public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                                   org.objectweb.asm.Label end,
                                                   org.objectweb.asm.Label handler,
                                                   String type) {
                        super.visitTryCatchBlock(start, end, handler, type == null ? null : mapGgType(type));
                    }

                    @Override
                    public void visitLocalVariable(String name, String descriptor, String signature,
                                                   org.objectweb.asm.Label start,
                                                   org.objectweb.asm.Label end, int index) {
                        super.visitLocalVariable(name, rewriteGgDesc(descriptor), rewriteGgSig(signature),
                                start, end, index);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /** Rename methods defined on the generated plugin class (LuaJC entry). */
    private static String mapGgMethodNameOnOurClass(String name, String descriptor) {
        // Only rename methods DEFINED on the generated plugin class.
        // initupvalue1(env) -> K(env)V   (LoadDex calls f.K(env); return void)
        // onInvoke(varargs) -> a_(ap)
        if ("initupvalue1".equals(name)) {
            return "K";
        }
        if ("onInvoke".equals(name)) {
            return "a_";
        }
        return name;
    }

    /**
     * Map callee method names for host luaj types (Nqfes 466.3).
     * Only renames when owner is stock luaj or already mapped gg luaj.
     */
    private static String mapGgCalleeMethod(String ownerRaw, String ownerMapped,
                                            String name, String descRaw, String descMapped) {
        if (name == null) return name;
        String o = ownerRaw == null ? "" : ownerRaw.replace('.', '/');
        boolean host = o.startsWith("org/luaj/vm2") || o.startsWith("luaj/");
        if (!host) return name;

        // Factories: prefer handled in visitMethodInsn; keep name fixes here as backup.
        // NEVER leave valueOf(String) as c with LuaValue return — caller fixes desc.
        if ("valueOf".equals(name)) {
            if (descRaw != null && descRaw.startsWith("(Ljava/lang/String;)")) return "c";
            if (descRaw != null && descRaw.startsWith("(J)")) return "d";
            if (descRaw != null && descRaw.startsWith("(D)")) return "c";
            if (descRaw != null && descRaw.startsWith("(Z)")) return "b";
            return name;
        }

        // Do NOT map initupvalue1 here for arbitrary callees — only our class defines K(env)V.
        // If stock code calls initupvalue1 on a function, map to K (void).
        if ("initupvalue1".equals(name)) return "K";
        if ("onInvoke".equals(name)) return "a_";

        // Varargs / ap
        if ("subargs".equals(name)) return "e_";
        if ("arg1".equals(name)) return "g";
        if ("arg".equals(name) && descRaw != null && descRaw.startsWith("(I)")) return "c";

        if ("tojstring".equals(name)) return "y";
        if ("toboolean".equals(name) && descMapped != null && descMapped.equals("()Z")) return "h";
        if ("testfor_b".equals(name) && descMapped != null
                && descMapped.equals("(Lluaj/LuaValue;Lluaj/LuaValue;)Z")) {
            return "g";
        }

        if ("invoke".equals(name)) {
            if (descMapped != null && descMapped.equals("()Lluaj/ap;")) return "Y";
            if (descMapped != null && descMapped.equals("(Lluaj/ap;)Lluaj/ap;")) return "a_";
            if (descMapped != null && descMapped.startsWith("([Lluaj/LuaValue;)")) return "a";
        }
        if ("call".equals(name)) {
            // LibFunction / VarArgFunction use a(...) overloads for call
            if ("()Lluaj/LuaValue;".equals(descMapped)) return "l";
            if ("(Lluaj/LuaValue;)Lluaj/LuaValue;".equals(descMapped)) return "a";
            if ("(Lluaj/LuaValue;Lluaj/LuaValue;)Lluaj/LuaValue;".equals(descMapped)) return "a";
            if ("(Lluaj/LuaValue;Lluaj/LuaValue;Lluaj/LuaValue;)Lluaj/LuaValue;".equals(descMapped)) return "a";
        }
        if ("get".equals(name)) {
            if ("(Lluaj/LuaValue;)Lluaj/LuaValue;".equals(descMapped)) return "_getField";
            if (descMapped != null && descMapped.startsWith("(I)")) return "c";
            if (descMapped != null && descMapped.startsWith("(Ljava/lang/String;)")) return "a";
        }
        if ("set".equals(name)) {
            if ("(Lluaj/LuaValue;Lluaj/LuaValue;)V".equals(descMapped)) return "b";
            if (descMapped != null && descMapped.startsWith("(ILluaj/LuaValue;)")) return "a";
            if (descMapped != null && descMapped.startsWith("(Ljava/lang/String;Lluaj/LuaValue;)")) return "a";
        }
        if ("sub".equals(name) && "(Lluaj/LuaValue;)Lluaj/LuaValue;".equals(descMapped)) {
            return "o";
        }
        if ("add".equals(name)) {
            // add(D)/add(I) still readable on Nqfes
            if ("(Lluaj/LuaValue;)Lluaj/LuaValue;".equals(descMapped)) return "a";
            return name;
        }
        if ("lt_b".equals(name) || "gt_b".equals(name) || "gteq_b".equals(name) || "lteq_b".equals(name)) {
            return name; // still present
        }
        if ("eq_b".equals(name) || "raweq".equals(name)) {
            if ("(Lluaj/LuaValue;)Z".equals(descMapped)) return "c";
            return name;
        }
        // len: Nqfes has L()I; LuaJC often uses len()LuaValue — use L()I would break type.
        // Prefer methods that still exist: do NOT map to K (K(LuaValue)V is initupvalue).
        if ("len".equals(name)) {
            if ("()I".equals(descMapped)) return "L";
            // ()LuaValue: use checkinteger-like path is wrong; keep "len" if absent will fail clearly.
            // Common GG short name for len() as value: try "r" ()LuaValue (heuristic).
            if ("()Lluaj/LuaValue;".equals(descMapped)) return "r";
            return name;
        }
        if ("tostring".equals(name)) return "t";
        if ("checkstring".equals(name)) return "a";

        return name;
    }

    private static String mapGgType(String internal) {
        if (internal == null) return null;
        String n = internal.replace('.', '/');
        if (n.startsWith("org/luaj/vm2/")) {
            String rest = n.substring("org/luaj/vm2/".length());
            if ("Varargs".equals(rest)) return GG_AP;
            if ("LuaInteger".equals(rest)) return GG_LUA_LONG;
            // jse platform etc. still map under luaj/ if present
            if (rest.startsWith("lib/jse/")) {
                return "luaj/lib/" + rest.substring("lib/jse/".length());
            }
            return "luaj/" + rest;
        }
        if (LUA_INTEGER.equals(n)) return GG_LUA_LONG;
        return n;
    }

    private static String rewriteGgDesc(String desc) {
        if (desc == null) return null;
        String d = desc;
        d = d.replace("Lorg/luaj/vm2/Varargs;", "Lluaj/ap;");
        d = d.replace("Lorg/luaj/vm2/LuaInteger;", "Lluaj/LuaLong;");
        d = d.replace("Lorg/luaj/vm2/", "Lluaj/");
        // fix lib/jse path if any slipped as Lorg/luaj/vm2/lib/jse → Lluaj/lib/jse
        d = d.replace("Lluaj/lib/jse/", "Lluaj/lib/");
        return d;
    }

    private static String rewriteGgSig(String sig) {
        return rewriteGgDesc(sig);
    }
}
