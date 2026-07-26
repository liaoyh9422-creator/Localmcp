package com.apkstoapk.app.util;

import com.reandroid.apk.APKLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects merge logs for UI display.
 * Supports bilingual Chinese/English parallel logs.
 */
public class SimpleApkLogger implements APKLogger {
    public interface Listener {
        void onLog(String line);
    }

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public List<String> getLines() {
        return lines;
    }

    public void clear() {
        lines.clear();
    }

    /** Raw line for external sinks (MCP, etc.). */
    public void raw(String message) {
        append(message);
    }

    private void append(String message) {
        if (message == null) {
            return;
        }
        lines.add(message);
        for (Listener listener : listeners) {
            listener.onLog(message);
        }
    }

    /** Single bilingual line: 中文 | English */
    public void bi(String zh, String en) {
        append(formatBi(zh, en));
    }

    /** Bilingual line with a path/value suffix. */
    public void bi(String zh, String en, String detail) {
        if (detail == null || detail.isEmpty()) {
            bi(zh, en);
            return;
        }
        append(formatBi(zh, en) + " → " + detail);
    }

    /** Section header for clearer stage separation. */
    public void stage(String zh, String en) {
        append("");
        append("▸ " + formatBi(zh, en));
    }

    public void ok(String zh, String en) {
        append("✓ " + formatBi(zh, en));
    }

    public void ok(String zh, String en, String detail) {
        if (detail == null || detail.isEmpty()) {
            ok(zh, en);
            return;
        }
        append("✓ " + formatBi(zh, en) + " → " + detail);
    }

    public void warn(String zh, String en) {
        append("! " + formatBi(zh, en));
    }

    public void warn(String zh, String en, String detail) {
        if (detail == null || detail.isEmpty()) {
            warn(zh, en);
            return;
        }
        append("! " + formatBi(zh, en) + " → " + detail);
    }

    public void err(String zh, String en) {
        append("✗ " + formatBi(zh, en));
    }

    public void err(String zh, String en, String detail) {
        if (detail == null || detail.isEmpty()) {
            err(zh, en);
            return;
        }
        append("✗ " + formatBi(zh, en) + " → " + detail);
    }

    public void item(String text) {
        append("  • " + text);
    }

    public void item(String zh, String en) {
        append("  • " + formatBi(zh, en));
    }

    public void item(String zh, String en, String detail) {
        if (detail == null || detail.isEmpty()) {
            item(zh, en);
            return;
        }
        append("  • " + formatBi(zh, en) + " → " + detail);
    }

    public void blank() {
        append("");
    }

    private static String formatBi(String zh, String en) {
        boolean hasZh = zh != null && !zh.isEmpty();
        boolean hasEn = en != null && !en.isEmpty();
        if (hasZh && hasEn) {
            if (zh.equals(en)) return zh;
            return zh + " | " + en;
        }
        if (hasZh) return zh;
        if (hasEn) return en;
        return "";
    }

    @Override
    public void logMessage(String msg) {
        // Engine (REAndroid) English logs → keep, but tag for clarity
        if (msg == null) return;
        String trimmed = msg.trim();
        if (trimmed.isEmpty()) return;
        // Avoid double-prefix if already bilingual/business formatted
        if (trimmed.startsWith("▸ ")
                || trimmed.startsWith("✓ ")
                || trimmed.startsWith("! ")
                || trimmed.startsWith("✗ ")
                || trimmed.startsWith("  • ")
                || trimmed.contains(" | ")) {
            append(msg);
            return;
        }
        append("[引擎|Engine] " + msg);
    }

    @Override
    public void logError(String msg, Throwable tr) {
        if (msg != null && !msg.isEmpty()) {
            err("错误", "Error", msg);
        }
        if (tr != null) {
            append("  • " + tr);
        }
    }

    @Override
    public void logVerbose(String msg) {
        if (msg == null || msg.trim().isEmpty()) return;
        append("[详细|Verbose] " + msg);
    }
}