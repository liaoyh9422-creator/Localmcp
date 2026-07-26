package com.apkstoapk.app.runtime;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Full Lua runtime via LuaJ (Lua 5.2 VM). In-process interpreter, not shell.
 */
public final class LuaRuntime {
    private LuaRuntime() {}

    public static final class Result {
        public final boolean ok;
        public final String stdout;
        public final String stderr;
        public final String returnValue;
        public final long elapsedMs;

        public Result(boolean ok, String stdout, String stderr, String returnValue, long elapsedMs) {
            this.ok = ok;
            this.stdout = stdout;
            this.stderr = stderr;
            this.returnValue = returnValue;
            this.elapsedMs = elapsedMs;
        }
    }

    public static Result eval(String code, String cwd, Map<String, String> env) {
        if (code == null) code = "";
        long start = System.currentTimeMillis();
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream prevOut = System.out;
        PrintStream prevErr = System.err;
        try {
            PrintStream outPs = new PrintStream(outBuf, true, StandardCharsets.UTF_8.name());
            PrintStream errPs = new PrintStream(errBuf, true, StandardCharsets.UTF_8.name());
            System.setOut(outPs);
            System.setErr(errPs);

            Globals globals = JsePlatform.standardGlobals();
            if (cwd != null && !cwd.isEmpty()) {
                globals.set("CWD", LuaValue.valueOf(cwd));
                File dir = new File(cwd);
                if (dir.isDirectory()) {
                    // package.path for require of local modules
                    String path = dir.getAbsolutePath() + "/?.lua;"
                            + dir.getAbsolutePath() + "/?/init.lua;"
                            + globals.get("package").get("path").tojstring();
                    globals.get("package").set("path", path);
                }
            }
            if (env != null) {
                LuaValue envTable = LuaValue.tableOf();
                for (Map.Entry<String, String> e : env.entrySet()) {
                    if (e.getKey() != null) {
                        envTable.set(e.getKey(), e.getValue() == null ? LuaValue.NIL
                                : LuaValue.valueOf(e.getValue()));
                    }
                }
                globals.set("ENV", envTable);
            }

            LuaValue chunk = globals.load(code, "mcp_lua");
            Varargs ret = chunk.invoke();
            StringBuilder retSb = new StringBuilder();
            for (int i = 1; i <= ret.narg(); i++) {
                if (i > 1) retSb.append('\t');
                retSb.append(ret.arg(i).tojstring());
            }
            outPs.flush();
            errPs.flush();
            return new Result(true, outBuf.toString(StandardCharsets.UTF_8.name()),
                    errBuf.toString(StandardCharsets.UTF_8.name()),
                    retSb.toString(), System.currentTimeMillis() - start);
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            String err = errBuf.toString() + (errBuf.size() > 0 ? "\n" : "") + msg;
            return new Result(false, outBuf.toString(), err, null,
                    System.currentTimeMillis() - start);
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
    }

    public static Result evalFile(File file, String cwd, Map<String, String> env) throws Exception {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("lua file missing: " + file);
        }
        byte[] bytes = readFile(file);
        String code = new String(bytes, StandardCharsets.UTF_8);
        String work = cwd != null ? cwd : file.getParent();
        return eval(code, work, env);
    }

    private static byte[] readFile(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f);
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
