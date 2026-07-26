package com.apkstoapk.app.runtime;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Full CPython 3.x via Chaquopy (native libpython), not Jython.
 * Execution goes through mcp_runtime_bootstrap.run_code in real CPython.
 */
public final class PythonRuntime {
    private static volatile boolean started;

    private PythonRuntime() {}

    public static final class Result {
        public final boolean ok;
        public final String stdout;
        public final String stderr;
        public final String returnValue;
        public final String version;
        public final long elapsedMs;

        public Result(boolean ok, String stdout, String stderr, String returnValue,
                      String version, long elapsedMs) {
            this.ok = ok;
            this.stdout = stdout;
            this.stderr = stderr;
            this.returnValue = returnValue;
            this.version = version;
            this.elapsedMs = elapsedMs;
        }
    }

    public static synchronized void ensureStarted(Context context) {
        if (started && Python.isStarted()) return;
        Context app = context.getApplicationContext();
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(app));
        }
        started = true;
    }

    public static String version(Context context) {
        ensureStarted(context);
        Python py = Python.getInstance();
        PyObject boot = py.getModule("mcp_runtime_bootstrap");
        return String.valueOf(boot.callAttr("version_info"));
    }

    public static Result eval(Context context, String code, String cwd, Map<String, String> env) {
        long start = System.currentTimeMillis();
        ensureStarted(context);
        try {
            Python py = Python.getInstance();
            PyObject boot = py.getModule("mcp_runtime_bootstrap");
            PyObject envObj = null;
            if (env != null && !env.isEmpty()) {
                envObj = py.getBuiltins().callAttr("dict");
                for (Map.Entry<String, String> e : env.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        envObj.callAttr("__setitem__", e.getKey(), e.getValue());
                    }
                }
            }
            // Java null becomes Python None in Chaquopy
            PyObject result = boot.callAttr(
                    "run_code",
                    code == null ? "" : code,
                    cwd,
                    envObj
            );
            boolean ok = toBoolean(result.callAttr("get", "ok"));
            String stdout = strOrEmpty(result.callAttr("get", "stdout"));
            String stderr = strOrEmpty(result.callAttr("get", "stderr"));
            PyObject retPy = result.callAttr("get", "return_value");
            String ret = isPyNone(retPy) ? null : String.valueOf(retPy);
            return new Result(ok, stdout, stderr, ret, safeVersion(context),
                    System.currentTimeMillis() - start);
        } catch (Throwable t) {
            return new Result(false, "",
                    t.getClass().getSimpleName() + ": " + t.getMessage(),
                    null, null, System.currentTimeMillis() - start);
        }
    }

    public static Result evalFile(Context context, File file, String cwd, Map<String, String> env)
            throws Exception {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("python file missing: " + file);
        }
        String code = new String(readFile(file), StandardCharsets.UTF_8);
        String work = cwd != null ? cwd : file.getParent();
        return eval(context, code, work, env);
    }

    private static String safeVersion(Context context) {
        try {
            return version(context);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isPyNone(PyObject o) {
        if (o == null) return true;
        // Chaquopy versions differ: no reliable isNone(); compare to Python None.
        try {
            Python py = Python.getInstance();
            PyObject none = py.getBuiltins().get("None");
            return o.equals(none) || "None".equals(String.valueOf(o));
        } catch (Throwable t) {
            return "None".equals(String.valueOf(o));
        }
    }

    private static boolean toBoolean(PyObject o) {
        if (o == null) return false;
        try {
            return o.toBoolean();
        } catch (Throwable t) {
            String s = String.valueOf(o);
            return "True".equals(s) || "true".equals(s) || "1".equals(s);
        }
    }

    private static String strOrEmpty(PyObject o) {
        if (isPyNone(o)) return "";
        return String.valueOf(o);
    }

    private static byte[] readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
