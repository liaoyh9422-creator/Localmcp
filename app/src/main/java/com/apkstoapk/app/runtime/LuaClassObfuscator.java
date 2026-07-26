package com.apkstoapk.app.runtime;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rename LuaJC-generated helper classes to short opaque names.
 * <p>
 * Host loaders (e.g. GG Script) typically loadClass(dexBaseName), so the
 * <b>entry</b> class must stay equal to the output dex basename (e.g.
 * {@code speed_obf.dex} → class {@code speed_obf}). Only non-entry classes
 * are mangled.
 */
public final class LuaClassObfuscator {
    private static final SecureRandom RND = new SecureRandom();
    private static final char[] ALPHA = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] ALNUM =
            "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private LuaClassObfuscator() {}

    public static final class Result {
        public final Map<String, byte[]> classes; // binary name -> class bytes
        public final List<String> classNames;
        public final Map<String, String> mapping; // old binary -> new binary
        public final String entryClass;
        public final String log;

        Result(Map<String, byte[]> classes, List<String> classNames,
               Map<String, String> mapping, String entryClass, String log) {
            this.classes = classes;
            this.classNames = classNames;
            this.mapping = mapping;
            this.entryClass = entryClass;
            this.log = log;
        }
    }

    /**
     * @param input          binaryClassName → class bytes
     * @param entryClassName required loadable main class (no package), e.g. speed_obf
     */
    public static Result obfuscate(Map<String, byte[]> input, String entryClassName) {
        StringBuilder log = new StringBuilder();
        if (input == null || input.isEmpty()) {
            return new Result(Collections.<String, byte[]>emptyMap(),
                    Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap(),
                    entryClassName,
                    "class-obf: empty input\n");
        }

        String entry = sanitizeEntryName(entryClassName);
        List<String> oldNames = new ArrayList<>(input.keySet());
        Collections.sort(oldNames);

        // Pick primary: exact match → ends with entry → shortest outer-like name
        String primaryOld = pickPrimary(oldNames, entry);
        log.append("entry=").append(entry).append('\n');
        log.append("primary-old=").append(primaryOld).append('\n');

        Set<String> used = new LinkedHashSet<>();
        used.add(entry);
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put(primaryOld, entry);

        for (String oldBinary : oldNames) {
            if (oldBinary.equals(primaryOld)) continue;
            String neu = nextHelperName(used);
            mapping.put(oldBinary, neu);
            used.add(neu);
            log.append("rename: ").append(oldBinary).append(" → ").append(neu).append('\n');
        }
        log.append("rename: ").append(primaryOld).append(" → ").append(entry)
                .append(" (entry kept for host loadClass)\n");

        Map<String, String> internalMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            internalMap.put(e.getKey().replace('.', '/'), e.getValue().replace('.', '/'));
        }
        SimpleRemapper remapper = new SimpleRemapper(internalMap);

        Map<String, byte[]> out = new LinkedHashMap<>();
        // entry class first in list for primary_class reporting
        List<String> newNames = new ArrayList<>();
        // process entry first
        List<String> order = new ArrayList<>();
        order.add(primaryOld);
        for (String o : oldNames) {
            if (!o.equals(primaryOld)) order.add(o);
        }

        for (String oldBinary : order) {
            byte[] src = input.get(oldBinary);
            if (src == null || src.length < 8) continue;
            String neu = mapping.get(oldBinary);
            try {
                ClassReader cr = new ClassReader(src);
                ClassWriter cw = new ClassWriter(cr, 0);
                ClassRemapper visitor = new ClassRemapper(cw, remapper) {
                    @Override
                    public void visitSource(String source, String debug) {
                        // drop SourceFile
                    }
                };
                cr.accept(visitor, 0);
                out.put(neu, cw.toByteArray());
                newNames.add(neu);
            } catch (Throwable t) {
                log.append("rename-fail ").append(oldBinary).append(": ")
                        .append(t.getClass().getSimpleName()).append(' ')
                        .append(t.getMessage()).append('\n');
                out.put(oldBinary, src);
                newNames.add(oldBinary);
            }
        }
        // ensure entry is index 0
        if (!newNames.isEmpty() && !entry.equals(newNames.get(0)) && newNames.contains(entry)) {
            newNames.remove(entry);
            newNames.add(0, entry);
        }
        log.append("obfuscated classes: ").append(newNames.size()).append('\n');
        return new Result(out, newNames, mapping, entry, log.toString());
    }

    /** @deprecated use {@link #obfuscate(Map, String)} */
    @Deprecated
    public static Result obfuscate(Map<String, byte[]> input) {
        String entry = "main";
        if (input != null && !input.isEmpty()) {
            List<String> names = new ArrayList<>(input.keySet());
            Collections.sort(names);
            entry = simpleName(names.get(0));
        }
        return obfuscate(input, entry);
    }

    public static String randomChunkName() {
        // still useful for non-entry isolation; entry is remapped afterward
        return "c" + randomToken(6) + ".lua";
    }

    public static String sanitizeEntryName(String name) {
        if (name == null || name.trim().isEmpty()) return "main";
        String n = name.trim();
        // drop path
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < n.length()) n = n.substring(slash + 1);
        // drop extension
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        // Java simple identifier (host loadClass)
        n = n.replaceAll("[^A-Za-z0-9_$]", "_");
        if (n.isEmpty()) n = "main";
        if (Character.isDigit(n.charAt(0))) n = "C" + n;
        return n;
    }

    private static String pickPrimary(List<String> oldNames, String entry) {
        for (String n : oldNames) {
            if (n.equals(entry) || simpleName(n).equals(entry)) return n;
        }
        // LuaJC often names main chunk after chunk file stem
        for (String n : oldNames) {
            String s = simpleName(n);
            if (s.equalsIgnoreCase(entry)) return n;
        }
        // prefer default-package / shortest
        String best = oldNames.get(0);
        for (String n : oldNames) {
            if (!n.contains(".") && (best.contains(".") || n.length() < best.length())) {
                best = n;
            }
        }
        return best;
    }

    private static String simpleName(String binary) {
        int i = binary.lastIndexOf('.');
        return i >= 0 ? binary.substring(i + 1) : binary;
    }

    /** Helper classes: default package short names _a, _b, ... (not used as entry). */
    private static String nextHelperName(Set<String> used) {
        int n = 0;
        while (true) {
            String simple;
            if (n < 26) {
                simple = "_" + ALPHA[n];
            } else {
                simple = "_" + ALPHA[n % 26] + Integer.toString(n / 26, 36);
            }
            if (!used.contains(simple)) return simple;
            n++;
            if (n > 10000) {
                return "_" + randomToken(5);
            }
        }
    }

    private static String randomToken(int len) {
        char[] buf = new char[len];
        buf[0] = ALPHA[RND.nextInt(ALPHA.length)];
        for (int i = 1; i < len; i++) {
            buf[i] = ALNUM[RND.nextInt(ALNUM.length)];
        }
        return new String(buf).toLowerCase(Locale.US);
    }
}
