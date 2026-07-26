package com.apkstoapk.app.ui.editor;

import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

/**
 * Lightweight XML syntax highlighter (no external editor lib).
 * Colors tuned for dark industrial theme.
 */
public final class XmlSyntaxHighlighter {
    // Dark theme tokens
    public static final int COLOR_DEFAULT = 0xFFE2E8F0;
    public static final int COLOR_TAG = 0xFF7DD3FC;       // light blue — tags
    public static final int COLOR_ATTR = 0xFFFCD34D;      // amber — attributes
    public static final int COLOR_STRING = 0xFF86EFAC;    // green — values
    public static final int COLOR_COMMENT = 0xFF64748B;   // muted — comments
    public static final int COLOR_DECL = 0xFFC4B5FD;      // purple — <?xml ...?>
    public static final int COLOR_SYMBOL = 0xFF94A3B8;    // gray — < > = /

    private XmlSyntaxHighlighter() {}

    public static Spannable highlight(CharSequence source) {
        if (source == null) source = "";
        SpannableStringBuilder sb = new SpannableStringBuilder(source);
        // base color
        sb.setSpan(new ForegroundColorSpan(COLOR_DEFAULT), 0, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        String text = sb.toString();
        int n = text.length();
        int i = 0;
        while (i < n) {
            // comment <!-- ... -->
            if (i + 3 < n && text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + 4);
                if (end < 0) end = n;
                else end += 3;
                color(sb, i, end, COLOR_COMMENT);
                i = end;
                continue;
            }
            // declaration <? ... ?>
            if (i + 1 < n && text.charAt(i) == '<' && text.charAt(i + 1) == '?') {
                int end = text.indexOf("?>", i + 2);
                if (end < 0) end = n;
                else end += 2;
                color(sb, i, end, COLOR_DECL);
                i = end;
                continue;
            }
            // tag <...>
            if (text.charAt(i) == '<') {
                int end = findTagEnd(text, i);
                if (end < 0) {
                    i++;
                    continue;
                }
                highlightTag(sb, text, i, end + 1);
                i = end + 1;
                continue;
            }
            i++;
        }
        return sb;
    }

    private static int findTagEnd(String text, int start) {
        boolean inStr = false;
        char quote = 0;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (c == quote) inStr = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                quote = c;
                continue;
            }
            if (c == '>') return i;
        }
        return -1;
    }

    private static void highlightTag(SpannableStringBuilder sb, String text, int start, int end) {
        // symbols < / > =
        color(sb, start, start + 1, COLOR_SYMBOL);
        if (end - start >= 2 && text.charAt(end - 2) == '/') {
            color(sb, end - 2, end, COLOR_SYMBOL);
        } else {
            color(sb, end - 1, end, COLOR_SYMBOL);
        }
        boolean closing = start + 1 < end && text.charAt(start + 1) == '/';
        int nameStart = closing ? start + 2 : start + 1;
        // skip whitespace
        while (nameStart < end && Character.isWhitespace(text.charAt(nameStart))) nameStart++;
        int nameEnd = nameStart;
        while (nameEnd < end - 1) {
            char c = text.charAt(nameEnd);
            if (Character.isWhitespace(c) || c == '/' || c == '>') break;
            nameEnd++;
        }
        if (nameEnd > nameStart) {
            color(sb, nameStart, nameEnd, COLOR_TAG);
        }
        // attributes
        int i = nameEnd;
        while (i < end - 1) {
            while (i < end - 1 && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= end - 1 || text.charAt(i) == '/' || text.charAt(i) == '>') break;
            int attrStart = i;
            while (i < end - 1 && text.charAt(i) != '=' && !Character.isWhitespace(text.charAt(i))
                    && text.charAt(i) != '/' && text.charAt(i) != '>') {
                i++;
            }
            if (i > attrStart) color(sb, attrStart, i, COLOR_ATTR);
            while (i < end - 1 && Character.isWhitespace(text.charAt(i))) i++;
            if (i < end - 1 && text.charAt(i) == '=') {
                color(sb, i, i + 1, COLOR_SYMBOL);
                i++;
            }
            while (i < end - 1 && Character.isWhitespace(text.charAt(i))) i++;
            if (i < end - 1 && (text.charAt(i) == '"' || text.charAt(i) == '\'')) {
                char q = text.charAt(i);
                int vStart = i;
                i++;
                while (i < end - 1 && text.charAt(i) != q) i++;
                if (i < end - 1 && text.charAt(i) == q) i++;
                color(sb, vStart, i, COLOR_STRING);
            }
        }
        if (closing && start + 1 < end) {
            color(sb, start + 1, start + 2, COLOR_SYMBOL);
        }
    }

    private static void color(SpannableStringBuilder sb, int start, int end, int color) {
        if (start < 0 || end > sb.length() || start >= end) return;
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
