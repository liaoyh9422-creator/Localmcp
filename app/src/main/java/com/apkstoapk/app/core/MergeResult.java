package com.apkstoapk.app.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MergeResult {
    public final File outputApk;
    public final boolean signed;
    public final List<String> logs;
    public final long elapsedMs;

    public MergeResult(File outputApk, boolean signed, List<String> logs, long elapsedMs) {
        this.outputApk = outputApk;
        this.signed = signed;
        this.logs = Collections.unmodifiableList(new ArrayList<>(logs));
        this.elapsedMs = elapsedMs;
    }
}
