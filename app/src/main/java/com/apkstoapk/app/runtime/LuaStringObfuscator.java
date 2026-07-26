package com.apkstoapk.app.runtime;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Compile-time Lua string literal obfuscation (self-designed; inspired by the
 * goal of tools like mi.lua, not a port of its bytecode/JMP pipeline).
 *
 * <p>Readable string literals are rewritten to a small local decoder call with
 * a numeric byte table, so generated classes/dex no longer contain those
 * plain UTF-8 constants.
 *
 * <p>Scheme: each UTF-8 byte b is stored as (b + key) % 256; key is random per
 * compile. Decoder uses only Lua 5.2 string/table APIs.
 */
public final class LuaStringObfuscator {
    private static final SecureRandom RND = new SecureRandom();

    private LuaStringObfuscator() {}

    public static final class Result {
        public final String source;
        public final int replaced;
        public final int skipped;
        public final String log;

        Result(String source, int replaced, int skipped, String log) {
            this.source = source;
            this.replaced = replaced;
            this.skipped = skipped;
            this.log = log;
        }
    }

    public static Result obfuscate(String luaSource) {
        if (luaSource == null || luaSource.isEmpty()) {
            return new Result(luaSource == null ? "" : luaSource, 0, 0, "string-obf: empty\n");
        }
        int key = 1 + RND.nextInt(254);
        String fn = "__s" + (1000 + RND.nextInt(9000));
        StringBuilder out = new StringBuilder(luaSource.length() + 256);
        StringBuilder log = new StringBuilder();
        int replaced = 0;
        int skipped = 0;
        int i = 0;
        int n = luaSource.length();

        while (i < n) {
            char c = luaSource.charAt(i);

            // comments
            if (c == '-' && i + 1 < n && luaSource.charAt(i + 1) == '-') {
                if (i + 3 < n && luaSource.charAt(i + 2) == '[' && isLongBracketOpen(luaSource, i + 2)) {
                    int[] longRange = readLongBracket(luaSource, i + 2);
                    if (longRange == null) {
                        out.append(luaSource.substring(i));
                        break;
                    }
                    // keep whole --[=[...]=] as-is
                    out.append(luaSource, i, longRange[1]);
                    i = longRange[1];
                    continue;
                }
                int end = luaSource.indexOf('\n', i);
                if (end < 0) {
                    out.append(luaSource.substring(i));
                    break;
                }
                out.append(luaSource, i, end + 1);
                i = end + 1;
                continue;
            }

            // long string [=[...]=]
            if (c == '[' && isLongBracketOpen(luaSource, i)) {
                int[] longRange = readLongBracket(luaSource, i);
                if (longRange == null) {
                    out.append(luaSource.substring(i));
                    break;
                }
                String content = luaSource.substring(longRange[0], longRange[1] - longRange[2]);
                // strip one leading newline per Lua rules
                if (content.startsWith("\r\n")) {
                    content = content.substring(2);
                } else if (content.startsWith("\n") || content.startsWith("\r")) {
                    content = content.substring(1);
                }
                if (shouldEncode(content)) {
                    out.append(encodeCall(fn, content, key));
                    replaced++;
                } else {
                    out.append(luaSource, i, longRange[1]);
                    skipped++;
                }
                i = longRange[1];
                continue;
            }

            // short string
            if (c == '"' || c == '\'') {
                int end = scanShortStringEnd(luaSource, i);
                if (end < 0) {
                    out.append(c);
                    i++;
                    continue;
                }
                String body = luaSource.substring(i + 1, end);
                String content = decodeShortString(body);
                if (shouldEncode(content)) {
                    out.append(encodeCall(fn, content, key));
                    replaced++;
                } else {
                    out.append(luaSource, i, end + 1);
                    skipped++;
                }
                i = end + 1;
                continue;
            }

            out.append(c);
            i++;
        }

        if (replaced == 0) {
            log.append("string-obf: no literals replaced\n");
            return new Result(luaSource, 0, skipped, log.toString());
        }

        String finalSrc = buildPreamble(fn, key) + "\n" + out;
        log.append("string-obf: key=").append(key)
                .append(" fn=").append(fn)
                .append(" replaced=").append(replaced)
                .append(" skipped=").append(skipped)
                .append('\n');
        return new Result(finalSrc, replaced, skipped, log.toString());
    }

    private static boolean shouldEncode(String content) {
        return content != null && !content.isEmpty();
    }

    private static String encodeCall(String fn, String content, int key) {
        byte[] raw = content.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(fn.length() + raw.length * 4 + 8);
        sb.append(fn).append("({");
        for (int i = 0; i < raw.length; i++) {
            if (i > 0) sb.append(',');
            int b = raw[i] & 0xff;
            sb.append((b + key) % 256);
        }
        sb.append("})");
        return sb.toString();
    }

    private static String buildPreamble(String fn, int key) {
        return "local " + fn + ";do local __k=" + key + ";"
                + fn + "=function(t) local o={} for i=1,#t do "
                + "local v=t[i]-__k; if v<0 then v=v+256 end; o[i]=string.char(v) end; "
                + "return table.concat(o) end end";
    }

    private static boolean isLongBracketOpen(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') return false;
        int j = i + 1;
        while (j < s.length() && s.charAt(j) == '=') j++;
        return j < s.length() && s.charAt(j) == '[';
    }

    /**
     * @return int[]{contentStart, endExclusive, closeLen} or null
     */
    private static int[] readLongBracket(String s, int i) {
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

    /** index of closing quote, or -1 */
    private static int scanShortStringEnd(String s, int start) {
        char quote = s.charAt(start);
        int j = start + 1;
        int n = s.length();
        while (j < n) {
            char ch = s.charAt(j);
            if (ch == '\\' && j + 1 < n) {
                j += 2;
                continue;
            }
            if (ch == quote) return j;
            if (ch == '\n' || ch == '\r') return -1;
            j++;
        }
        return -1;
    }

    private static String decodeShortString(String body) {
        StringBuilder lit = new StringBuilder(body.length());
        int i = 0;
        int n = body.length();
        while (i < n) {
            char ch = body.charAt(i);
            if (ch != '\\' || i + 1 >= n) {
                lit.append(ch);
                i++;
                continue;
            }
            char e = body.charAt(i + 1);
            i += 2;
            switch (e) {
                case 'a': lit.append('\u0007'); break;
                case 'b': lit.append('\b'); break;
                case 'f': lit.append('\f'); break;
                case 'n': lit.append('\n'); break;
                case 'r': lit.append('\r'); break;
                case 't': lit.append('\t'); break;
                case 'v': lit.append('\u000B'); break;
                case '\\': lit.append('\\'); break;
                case '"': lit.append('"'); break;
                case '\'': lit.append('\''); break;
                case '\n': lit.append('\n'); break;
                case 'z':
                    while (i < n && Character.isWhitespace(body.charAt(i))) i++;
                    break;
                case 'x': {
                    if (i + 1 < n) {
                        int hi = hex(body.charAt(i));
                        int lo = hex(body.charAt(i + 1));
                        if (hi >= 0 && lo >= 0) {
                            lit.append((char) ((hi << 4) | lo));
                            i += 2;
                            break;
                        }
                    }
                    lit.append('x');
                    break;
                }
                default:
                    if (e >= '0' && e <= '9') {
                        int val = e - '0';
                        int digits = 1;
                        while (digits < 3 && i < n) {
                            char d = body.charAt(i);
                            if (d < '0' || d > '9') break;
                            val = val * 10 + (d - '0');
                            i++;
                            digits++;
                        }
                        lit.append((char) (val & 0xff));
                    } else {
                        lit.append(e);
                    }
                    break;
            }
        }
        return lit.toString();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        char[] a = new char[n];
        for (int i = 0; i < n; i++) a[i] = c;
        return new String(a);
    }
}
