package com.apkstoapk.app.runtime;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B3: encrypt string LDC constants inside class bytecode.
 * Each non-empty LDC string becomes invoke of a private static decoder
 * with an XOR-scrambled payload (still a string, but not readable plaintext).
 */
public final class ClassStringEncryptor {
    private static final SecureRandom RND = new SecureRandom();

    private ClassStringEncryptor() {}

    public static final class Result {
        public final Map<String, byte[]> classes;
        public final int encrypted;
        public final String log;

        Result(Map<String, byte[]> classes, int encrypted, String log) {
            this.classes = classes;
            this.encrypted = encrypted;
            this.log = log;
        }
    }

    public static Result encrypt(Map<String, byte[]> input) {
        StringBuilder log = new StringBuilder();
        if (input == null || input.isEmpty()) {
            return new Result(input, 0, "B3: empty\n");
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, byte[]> e : input.entrySet()) {
            String binary = e.getKey();
            byte[] src = e.getValue();
            try {
                One r = encryptOne(src, binary);
                out.put(binary, r.bytes);
                total += r.count;
                if (r.count > 0) {
                    log.append("B3 ").append(binary).append(": ").append(r.count).append(" ldc\n");
                }
            } catch (Throwable t) {
                log.append("B3-fail ").append(binary).append(": ")
                        .append(t.getClass().getSimpleName()).append(' ')
                        .append(t.getMessage()).append('\n');
                out.put(binary, src);
            }
        }
        log.append("B3 encrypted ldc total: ").append(total).append('\n');
        return new Result(out, total, log.toString());
    }

    private static final class One {
        final byte[] bytes;
        final int count;

        One(byte[] bytes, int count) {
            this.bytes = bytes;
            this.count = count;
        }
    }

    private static One encryptOne(byte[] classBytes, String binaryName) {
        final int[] count = new int[1];
        final int key = 1 + RND.nextInt(250);
        final String owner = binaryName.replace('.', '/');
        final String decName = "__u" + (10 + RND.nextInt(89));

        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        final boolean[] needDecoder = new boolean[1];

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                // don't touch the decoder itself if re-processing
                if (name != null && name.startsWith("__u")) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            String s = (String) value;
                            if (!s.isEmpty() && s.length() <= 8000) {
                                needDecoder[0] = true;
                                count[0]++;
                                String enc = xorEncode(s, key);
                                super.visitLdcInsn(enc);
                                super.visitIntInsn(Opcodes.SIPUSH, key);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        owner,
                                        decName,
                                        "(Ljava/lang/String;I)Ljava/lang/String;",
                                        false
                                );
                                return;
                            }
                        }
                        super.visitLdcInsn(value);
                    }
                };
            }
        }, 0);

        if (needDecoder[0]) {
            // inject decoder method
            MethodVisitor mv = cw.visitMethod(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    decName,
                    "(Ljava/lang/String;I)Ljava/lang/String;",
                    null,
                    null
            );
            mv.visitCode();
            // char[] c = s.toCharArray();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
            mv.visitVarInsn(Opcodes.ASTORE, 2);
            // int i = 0;
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
            // loop
            org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
            org.objectweb.asm.Label end = new org.objectweb.asm.Label();
            mv.visitLabel(loop);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitInsn(Opcodes.ARRAYLENGTH);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, end);
            // c[i] = (char)(c[i] ^ key ^ (i & 15));
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.CALOAD);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitInsn(Opcodes.IXOR);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitIntInsn(Opcodes.BIPUSH, 15);
            mv.visitInsn(Opcodes.IAND);
            mv.visitInsn(Opcodes.IXOR);
            mv.visitInsn(Opcodes.I2C);
            mv.visitInsn(Opcodes.CASTORE);
            // i++
            mv.visitIincInsn(3, 1);
            mv.visitJumpInsn(Opcodes.GOTO, loop);
            mv.visitLabel(end);
            // return new String(c);
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/String");
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        return new One(cw.toByteArray(), count[0]);
    }

    private static String xorEncode(String plain, int key) {
        char[] c = plain.toCharArray();
        for (int i = 0; i < c.length; i++) {
            c[i] = (char) (c[i] ^ key ^ (i & 15));
        }
        return new String(c);
    }
}
