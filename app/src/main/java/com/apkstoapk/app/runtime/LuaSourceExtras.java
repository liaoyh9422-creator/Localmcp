package com.apkstoapk.app.runtime;

import java.security.SecureRandom;

/**
 * Extra Lua source obfuscation passes (self-designed):
 * <ul>
 *   <li>A1 – split number literals into arithmetic expressions</li>
 *   <li>A3 – inject dead {@code if false then ... end} blocks</li>
 *   <li>A5 – split long string literals into concatenations</li>
 * </ul>
 * Runs before {@link LuaStringObfuscator}. Skips comments/strings for A1.
 */
public final class LuaSourceExtras {
    private static final SecureRandom RND = new SecureRandom();
    private static final int LONG_STR = 12; // A5 threshold
    private static final int CHUNK = 5;     // A5 piece length

    private LuaSourceExtras() {}

    public static final class Result {
        public final String source;
        public final String log;

        Result(String source, String log) {
            this.source = source;
            this.log = log;
        }
    }

    public static Result apply(String src) {
        if (src == null || src.isEmpty()) {
            return new Result(src == null ? "" : src, "source-extras: empty\n");
        }
        StringBuilder log = new StringBuilder();
        String s = src;
        int[] a5 = new int[1];
        s = splitLongStrings(s, a5);
        log.append("A5 long-string-split: ").append(a5[0]).append('\n');
        int[] a1 = new int[1];
        s = splitNumbers(s, a1);
        log.append("A1 number-split: ").append(a1[0]).append('\n');
        int[] a3 = new int[1];
        s = injectDeadCode(s, a3);
        log.append("A3 dead-code: ").append(a3[0]).append('\n');
        return new Result(s, log.toString());
    }

    // ----------------- A5 -----------------
    private static String splitLongStrings(String src, int[] count) {
        StringBuilder out = new StringBuilder(src.length() + 64);
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '-' && i + 1 < n && src.charAt(i + 1) == '-') {
                int end = skipComment(src, i);
                out.append(src, i, end);
                i = end;
                continue;
            }
            if (c == '[' && isLongOpen(src, i)) {
                int[] lr = readLong(src, i);
                if (lr == null) {
                    out.append(c);
                    i++;
                    continue;
                }
                String content = src.substring(lr[0], lr[1] - lr[2]);
                String body = stripLeadNl(content);
                if (body.length() >= LONG_STR) {
                    out.append(concatPieces(body));
                    count[0]++;
                } else {
                    out.append(src, i, lr[1]);
                }
                i = lr[1];
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = scanShortEnd(src, i);
                if (end < 0) {
                    out.append(c);
                    i++;
                    continue;
                }
                String body = src.substring(i + 1, end);
                String decoded = decodeShort(body);
                if (decoded.length() >= LONG_STR && body.indexOf('\\') < 0) {
                    // only plain content without escapes — safer re-encode
                    out.append(concatPieces(decoded));
                    count[0]++;
                } else {
                    out.append(src, i, end + 1);
                }
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String concatPieces(String s) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < s.length(); i += CHUNK) {
            int e = Math.min(s.length(), i + CHUNK);
            if (!first) sb.append("..");
            first = false;
            sb.append('"').append(escapeLua(s.substring(i, e))).append('"');
        }
        if (first) sb.append("\"\"");
        return sb.toString();
    }

    private static String escapeLua(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 32) {
                        b.append('\\').append(String.format("%03d", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    // ----------------- A1 -----------------
    private static String splitNumbers(String src, int[] count) {
        StringBuilder out = new StringBuilder(src.length() + 64);
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '-' && i + 1 < n && src.charAt(i + 1) == '-') {
                int end = skipComment(src, i);
                out.append(src, i, end);
                i = end;
                continue;
            }
            if (c == '[' && isLongOpen(src, i)) {
                int[] lr = readLong(src, i);
                if (lr == null) {
                    out.append(c);
                    i++;
                    continue;
                }
                out.append(src, i, lr[1]);
                i = lr[1];
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = scanShortEnd(src, i);
                if (end < 0) {
                    out.append(c);
                    i++;
                    continue;
                }
                out.append(src, i, end + 1);
                i = end + 1;
                continue;
            }
            // number? avoid letters/_ before (identifiers) and 0x hex for simplicity
            if (isDigit(c) && !isIdentChar(prevChar(src, i))) {
                // skip hex 0x...
                if (c == '0' && i + 1 < n && (src.charAt(i + 1) == 'x' || src.charAt(i + 1) == 'X')) {
                    int j = i + 2;
                    while (j < n && isHex(src.charAt(j))) j++;
                    out.append(src, i, j);
                    i = j;
                    continue;
                }
                int j = i;
                while (j < n && isDigit(src.charAt(j))) j++;
                // float?
                boolean isFloat = false;
                if (j < n && src.charAt(j) == '.') {
                    int k = j + 1;
                    if (k < n && isDigit(src.charAt(k))) {
                        isFloat = true;
                        j = k;
                        while (j < n && isDigit(src.charAt(j))) j++;
                    }
                }
                // exponent
                if (j < n && (src.charAt(j) == 'e' || src.charAt(j) == 'E')) {
                    isFloat = true;
                    int k = j + 1;
                    if (k < n && (src.charAt(k) == '+' || src.charAt(k) == '-')) k++;
                    if (k < n && isDigit(src.charAt(k))) {
                        j = k;
                        while (j < n && isDigit(src.charAt(j))) j++;
                    }
                }
                // don't touch if next is ident (1e is handled; 1foo rare)
                if (j < n && isIdentStart(src.charAt(j))) {
                    out.append(src, i, j);
                    i = j;
                    continue;
                }
                String num = src.substring(i, j);
                if (!isFloat && num.length() >= 1) {
                    try {
                        long v = Long.parseLong(num);
                        if (v >= 0 && v <= Integer.MAX_VALUE && (v >= 10 || RND.nextBoolean())) {
                            out.append(splitInt((int) v));
                            count[0]++;
                            i = j;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                out.append(num);
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String splitInt(int v) {
        if (v < 10) {
            // small: (v+k)-k
            int k = 1 + RND.nextInt(9);
            return "(" + (v + k) + "-" + k + ")";
        }
        int mode = RND.nextInt(3);
        if (mode == 0) {
            int a = 1 + RND.nextInt(Math.max(1, v / 2));
            return "(" + a + "+" + (v - a) + ")";
        }
        if (mode == 1 && v >= 4) {
            int a = 2 + RND.nextInt(Math.min(9, v / 2));
            int q = v / a;
            int r = v - q * a;
            if (r == 0) return "(" + q + "*" + a + ")";
            return "(" + q + "*" + a + "+" + r + ")";
        }
        int a = RND.nextInt(v);
        return "(" + a + "+" + (v - a) + ")";
    }

    // ----------------- A3 -----------------
    private static String injectDeadCode(String src, int[] count) {
        // inject 1–3 dead blocks near top (after any shebang/first line)
        int n = 1 + RND.nextInt(3);
        StringBuilder dead = new StringBuilder();
        for (int i = 0; i < n; i++) {
            dead.append(deadBlock(i)).append('\n');
            count[0]++;
        }
        int insertAt = 0;
        if (src.startsWith("#")) {
            int nl = src.indexOf('\n');
            insertAt = nl < 0 ? src.length() : nl + 1;
        }
        return src.substring(0, insertAt) + dead + src.substring(insertAt);
    }

    private static String deadBlock(int idx) {
        int x = RND.nextInt(1000);
        int y = RND.nextInt(1000);
        String junk = "z" + RND.nextInt(9999);
        // never executes; uses only locals
        return "if false then local " + junk + "=" + x + "; local _d" + idx
                + "=" + junk + "+" + y + "; end";
    }

    // ----------------- scan helpers -----------------
    private static int skipComment(String s, int i) {
        if (i + 3 < s.length() && s.charAt(i + 2) == '[' && isLongOpen(s, i + 2)) {
            int[] lr = readLong(s, i + 2);
            if (lr != null) return lr[1];
        }
        int end = s.indexOf('\n', i);
        return end < 0 ? s.length() : end + 1;
    }

    private static boolean isLongOpen(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') return false;
        int j = i + 1;
        while (j < s.length() && s.charAt(j) == '=') j++;
        return j < s.length() && s.charAt(j) == '[';
    }

    private static int[] readLong(String s, int i) {
        int j = i + 1;
        int eq = 0;
        while (j < s.length() && s.charAt(j) == '=') {
            eq++;
            j++;
        }
        if (j >= s.length() || s.charAt(j) != '[') return null;
        int contentStart = j + 1;
        String close = "]" + repeat('=', eq) + "]";
        int end = s.indexOf(close, contentStart);
        if (end < 0) return null;
        return new int[]{contentStart, end + close.length(), close.length()};
    }

    private static int scanShortEnd(String s, int start) {
        char q = s.charAt(start);
        int j = start + 1;
        while (j < s.length()) {
            char ch = s.charAt(j);
            if (ch == '\\' && j + 1 < s.length()) {
                j += 2;
                continue;
            }
            if (ch == q) return j;
            if (ch == '\n' || ch == '\r') return -1;
            j++;
        }
        return -1;
    }

    private static String stripLeadNl(String content) {
        if (content.startsWith("\r\n")) return content.substring(2);
        if (content.startsWith("\n") || content.startsWith("\r")) return content.substring(1);
        return content;
    }

    private static String decodeShort(String body) {
        // plain path for A5 when no escapes; if escapes, return as-is length check uses raw
        if (body.indexOf('\\') < 0) return body;
        return body; // don't split escaped strings
    }

    private static char prevChar(String s, int i) {
        return i > 0 ? s.charAt(i - 1) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isIdentChar(char c) {
        return isIdentStart(c) || isDigit(c);
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        char[] a = new char[n];
        for (int i = 0; i < n; i++) a[i] = c;
        return new String(a);
    }
}
