package com.apkstoapk.app.runtime;

import android.content.Context;
import android.os.Build;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full C++ runtime on device (no interpreter / no shell-wrapper downgrade):
 * <ol>
 *   <li>Real Clang++ from Android NDK (aarch64 host build)</li>
 *   <li>Compile source → PIE ELF linked against NDK sysroot / libc++</li>
 *   <li>Execute via Android linker64 with captured stdout/stderr</li>
 * </ol>
 *
 * Toolchain is installed under {@code context.getFilesDir()/cpp_toolchain/} (on-demand download).
 */
public final class CppRuntime {
    public static final String DEFAULT_NDK_URL =
            "https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.7z";
    public static final String DEFAULT_API = "24";

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final Object INSTALL_LOCK = new Object();

    private CppRuntime() {}

    public static final class Result {
        public final boolean ok;
        public final String stdout;
        public final String stderr;
        public final int exitCode;
        public final String compiler;
        public final String binary;
        public final long compileMs;
        public final long runMs;
        public final long elapsedMs;

        public Result(boolean ok, String stdout, String stderr, int exitCode,
                      String compiler, String binary, long compileMs, long runMs, long elapsedMs) {
            this.ok = ok;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
            this.compiler = compiler;
            this.binary = binary;
            this.compileMs = compileMs;
            this.runMs = runMs;
            this.elapsedMs = elapsedMs;
        }
    }

    public static final class ToolchainInfo {
        public final boolean ready;
        public final String root;
        public final String clangxx;
        public final String hostAbi;
        public final String message;

        public ToolchainInfo(boolean ready, String root, String clangxx, String hostAbi, String message) {
            this.ready = ready;
            this.root = root;
            this.clangxx = clangxx;
            this.hostAbi = hostAbi;
            this.message = message;
        }
    }

    public static File toolchainRoot(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "cpp_toolchain");
    }

    public static ToolchainInfo info(Context context) {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
        File clang = findClangxx(context);
        if (clang != null) {
            return new ToolchainInfo(true, clang.getParentFile().getParentFile() != null
                    ? guessNdkRoot(clang).getAbsolutePath() : clang.getParent(),
                    clang.getAbsolutePath(), abi, "clang++ ready");
        }
        if (!isAarch64Host(abi)) {
            return new ToolchainInfo(false, toolchainRoot(context).getAbsolutePath(), null, abi,
                    "Host ABI " + abi + " unsupported for bundled NDK (need arm64-v8a). "
                            + "Place a full NDK under files/cpp_toolchain and ensure clang++ exists.");
        }
        return new ToolchainInfo(false, toolchainRoot(context).getAbsolutePath(), null, abi,
                "NDK toolchain not installed. Call install_cpp_toolchain (downloads real clang NDK ~330MB).");
    }

    /**
     * Install toolchain from network URL and/or a local .7z archive.
     *
     * @param urlOrNull   download URL; null → default NDK URL when no local archive
     * @param localArchiveOrNull existing .7z file (skips download when present & size OK)
     * @param force       re-download / re-extract even if clang++ already exists
     */
    public static ToolchainInfo install(
            Context context,
            String urlOrNull,
            File localArchiveOrNull,
            boolean force
    ) throws Exception {
        synchronized (INSTALL_LOCK) {
            String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                    ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
            if (!isAarch64Host(abi)) {
                throw new IllegalStateException(
                        "install_cpp_toolchain only auto-downloads aarch64 NDK; host=" + abi);
            }
            File root = toolchainRoot(context);
            File clang = findClangxx(context);
            if (clang != null && !force) {
                return info(context);
            }
            if (!root.exists() && !root.mkdirs()) {
                throw new IllegalStateException("Cannot create " + root);
            }

            // Clean stale partial downloads
            File part = new File(root, "android-ndk-r29-aarch64.7z.part");
            if (part.exists()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }

            File archive = new File(root, "android-ndk-r29-aarch64.7z");
            boolean haveLocal = localArchiveOrNull != null
                    && localArchiveOrNull.isFile()
                    && localArchiveOrNull.length() > 50L * 1024L * 1024L; // >50MB sanity
            if (haveLocal) {
                // copy local archive into toolchain root if needed
                if (!localArchiveOrNull.getAbsolutePath().equals(archive.getAbsolutePath())) {
                    copyFile(localArchiveOrNull, archive);
                }
            } else {
                String url = (urlOrNull == null || urlOrNull.trim().isEmpty())
                        ? DEFAULT_NDK_URL : urlOrNull.trim();
                // Accept file path passed as url for convenience
                if (url.startsWith("/") || url.startsWith("file:")) {
                    File f = url.startsWith("file:")
                            ? new File(URI.create(url))
                            : new File(url);
                    if (!f.isFile()) {
                        throw new IllegalArgumentException("local NDK archive missing: " + f);
                    }
                    copyFile(f, archive);
                } else {
                    downloadTo(url, archive);
                }
            }
            if (!archive.isFile() || archive.length() < 50L * 1024L * 1024L) {
                throw new IllegalStateException(
                        "NDK archive incomplete/missing: " + archive
                                + " size=" + (archive.isFile() ? archive.length() : -1));
            }

            File extractDir = new File(root, "ndk");
            if (extractDir.exists()) {
                deleteRecursively(extractDir);
            }
            extract7z(archive, extractDir);
            // 7z often materializes symlinks as tiny text files ("clang++" -> "clang").
            // Repair critical compiler/linker names before chmod.
            repairFlattenedSymlinks(extractDir);
            chmodTree(extractDir);
            clang = findClangxx(context);
            if (clang == null) {
                throw new IllegalStateException(
                        "Extracted NDK but clang++ not found under " + extractDir);
            }
            // Drop archive to free ~330MB after successful extract
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            return info(context);
        }
    }

    /** Back-compat: URL-only install. */
    public static ToolchainInfo install(Context context, String urlOrNull, boolean force)
            throws Exception {
        return install(context, urlOrNull, null, force);
    }

    private static void copyFile(File src, File dest) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192 * 4];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    public static Result eval(
            Context context,
            String code,
            String cwd,
            List<String> extraArgs,
            List<String> linkLibs,
            String std,
            Map<String, String> env
    ) throws Exception {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("code is empty");
        }
        File work = new File(context.getCacheDir(), "cpp_rt_" + SEQ.getAndIncrement());
        if (!work.mkdirs()) throw new IllegalStateException("Cannot create " + work);
        try {
            boolean hasMain = code.contains("main(") || code.contains("main (");
            String src = hasMain ? code : wrapSnippet(code);
            File srcFile = new File(work, "main.cpp");
            writeUtf8(srcFile, src);
            return evalFile(context, srcFile, cwd, extraArgs, linkLibs, std, env, work);
        } finally {
            // keep work only if caller wants — default cleanup binary parent later
        }
    }

    public static Result evalFile(
            Context context,
            File srcFile,
            String cwd,
            List<String> extraArgs,
            List<String> linkLibs,
            String std,
            Map<String, String> env
    ) throws Exception {
        File work = new File(context.getCacheDir(), "cpp_rt_" + SEQ.getAndIncrement());
        if (!work.mkdirs()) throw new IllegalStateException("Cannot create " + work);
        return evalFile(context, srcFile, cwd, extraArgs, linkLibs, std, env, work);
    }

    private static Result evalFile(
            Context context,
            File srcFile,
            String cwd,
            List<String> extraArgs,
            List<String> linkLibs,
            String std,
            Map<String, String> env,
            File work
    ) throws Exception {
        long t0 = System.currentTimeMillis();
        File clang = findClangxx(context);
        if (clang == null) {
            return new Result(false, "", info(context).message + " Use install_cpp_toolchain first.",
                    -1, null, null, 0, 0, System.currentTimeMillis() - t0);
        }
        ensureExecutable(clang);

        String cxxStd = (std == null || std.trim().isEmpty()) ? "c++17" : std.trim();
        File outBin = new File(work, "a.out");
        File ndkRoot = guessNdkRoot(clang);
        String api = DEFAULT_API;
        String target = "aarch64-linux-android" + api;

        List<String> cmd = new ArrayList<>();
        cmd.add(clang.getAbsolutePath());
        cmd.add("--target=" + target);
        cmd.add("-std=" + cxxStd);
        cmd.add("-O0");
        cmd.add("-fPIE");
        cmd.add("-pie");
        cmd.add("-Wl,--export-dynamic");
        // Prefer NDK libc++ if present
        File llvm = clang.getParentFile() != null ? clang.getParentFile().getParentFile() : null;
        if (llvm != null) {
            File sysroot = new File(llvm, "sysroot");
            if (sysroot.isDirectory()) {
                cmd.add("--sysroot=" + sysroot.getAbsolutePath());
            }
        }
        cmd.add("-o");
        cmd.add(outBin.getAbsolutePath());
        cmd.add(srcFile.getAbsolutePath());
        if (linkLibs != null) {
            for (String lib : linkLibs) {
                if (lib != null && !lib.trim().isEmpty()) {
                    String L = lib.trim();
                    if (L.startsWith("-")) cmd.add(L);
                    else cmd.add("-l" + L);
                }
            }
        }
        // default link libc++ when available via clang driver
        cmd.add("-lc++_shared");
        cmd.add("-lm");
        cmd.add("-ldl");
        cmd.add("-llog");
        if (extraArgs != null) {
            for (String a : extraArgs) {
                if (a != null && !a.isEmpty()) cmd.add(a);
            }
        }

        long c0 = System.currentTimeMillis();
        Proc compile = runProcess(cmd, cwd != null ? new File(cwd) : work, env, 120_000);
        long compileMs = System.currentTimeMillis() - c0;
        if (compile.exitCode != 0 || !outBin.isFile()) {
            return new Result(false, compile.stdout,
                    "clang++ compile failed (exit " + compile.exitCode + "):\n" + compile.stderr,
                    compile.exitCode, clang.getAbsolutePath(), outBin.getAbsolutePath(),
                    compileMs, 0, System.currentTimeMillis() - t0);
        }
        ensureExecutable(outBin);

        // Run via linker64 so non-exported exec works from app data
        File linker = resolveLinker();
        List<String> runCmd = new ArrayList<>();
        if (linker != null) {
            runCmd.add(linker.getAbsolutePath());
            runCmd.add(outBin.getAbsolutePath());
        } else {
            runCmd.add(outBin.getAbsolutePath());
        }
        // LD_LIBRARY_PATH for libc++_shared.so
        Map<String, String> runEnv = env == null
                ? new java.util.HashMap<String, String>()
                : new java.util.HashMap<String, String>(env);
        String libPath = buildLibPath(clang, context);
        if (libPath != null) {
            String old = runEnv.get("LD_LIBRARY_PATH");
            runEnv.put("LD_LIBRARY_PATH", old == null || old.isEmpty() ? libPath : libPath + ":" + old);
        }

        long r0 = System.currentTimeMillis();
        Proc run = runProcess(runCmd, cwd != null ? new File(cwd) : work, runEnv, 60_000);
        long runMs = System.currentTimeMillis() - r0;

        boolean ok = run.exitCode == 0;
        return new Result(ok, run.stdout, run.stderr, run.exitCode,
                clang.getAbsolutePath(), outBin.getAbsolutePath(),
                compileMs, runMs, System.currentTimeMillis() - t0);
    }

    private static String wrapSnippet(String body) {
        // NDK has no bits/stdc++.h — use portable headers only
        return "#include <iostream>\n#include <string>\n#include <vector>\n#include <cmath>\n"
                + "#include <cstdio>\n#include <cstdlib>\n#include <cstring>\n"
                + "using namespace std;\nint main(){\n"
                + body + "\nreturn 0;}\n";
    }

    private static boolean isAarch64Host(String abi) {
        if (abi == null) return false;
        String a = abi.toLowerCase(Locale.US);
        return a.contains("arm64") || a.contains("aarch64");
    }

    public static File findClangxx(Context context) {
        List<File> roots = new ArrayList<>();
        roots.add(toolchainRoot(context));
        String prop = System.getProperty("mcp.cpp.toolchain");
        if (prop != null && !prop.isEmpty()) roots.add(new File(prop));
        String env = System.getenv("CPP_TOOLCHAIN");
        if (env != null && !env.isEmpty()) roots.add(new File(env));
        // optional external storage drop path
        File ext = context.getExternalFilesDir(null);
        if (ext != null) roots.add(new File(ext, "cpp_toolchain"));

        // Prefer real compiler binary. 7z extract may turn symlinks into tiny text files
        // (clang++ -> "clang", clang -> "clang-21"). Resolve those.
        String[] names = new String[]{
                "clang++", "clang++.exe",
                "clang-21", "clang-20", "clang-19", "clang-18", "clang-17",
                "clang"
        };
        for (File root : roots) {
            if (root == null || !root.exists()) continue;
            for (String name : names) {
                File hit = findFileNamed(root, new String[]{name}, 14);
                if (hit == null) continue;
                File resolved = resolveCompilerBinary(hit);
                if (resolved != null && isLikelyElf(resolved)) {
                    return resolved;
                }
            }
        }
        return null;
    }

    /** Resolve 7z-flattened text symlinks and wrappers to a real ELF compiler. */
    private static File resolveCompilerBinary(File f) {
        if (f == null || !f.isFile()) return null;
        File cur = f;
        // Follow tiny text redirect files up to a few hops: "clang++\n" / "clang-21\n"
        for (int hop = 0; hop < 6; hop++) {
            if (isLikelyElf(cur)) return cur;
            if (cur.length() > 0 && cur.length() < 256) {
                String text = readTextFileLimited(cur, 200).trim();
                if (text.isEmpty()) break;
                // single token path or basename
                String first = text.split("\\s+")[0].trim();
                if (first.contains("/")) {
                    File next = new File(first);
                    if (!next.isFile()) next = new File(cur.getParentFile(), first);
                    if (next.isFile()) { cur = next; continue; }
                } else {
                    File next = new File(cur.getParentFile(), first);
                    if (next.isFile()) { cur = next; continue; }
                }
            }
            break;
        }
        // If still not ELF but name is clang++, try sibling clang-21
        File dir = cur.getParentFile();
        if (dir != null) {
            String[] alts = new String[]{"clang-21", "clang-20", "clang-19", "clang-18", "clang"};
            for (String a : alts) {
                File alt = new File(dir, a);
                if (isLikelyElf(alt)) return alt;
            }
        }
        return isLikelyElf(cur) ? cur : null;
    }

    private static boolean isLikelyElf(File f) {
        if (f == null || !f.isFile() || f.length() < 64) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] mag = new byte[4];
            if (in.read(mag) != 4) return false;
            return mag[0] == 0x7f && mag[1] == 'E' && mag[2] == 'L' && mag[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private static String readTextFileLimited(File f, int max) {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[Math.min(max, 256)];
            int n = in.read(buf);
            if (n > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static File guessNdkRoot(File clangxx) {
        // .../toolchains/llvm/prebuilt/<host>/bin/clang++
        File bin = clangxx.getParentFile();
        if (bin == null) return clangxx;
        File prebuilt = bin.getParentFile();
        if (prebuilt == null) return bin;
        File llvm = prebuilt.getParentFile();
        if (llvm == null) return prebuilt;
        File toolchains = llvm.getParentFile();
        if (toolchains == null) return llvm;
        File ndk = toolchains.getParentFile();
        return ndk != null ? ndk : toolchains;
    }

    private static String buildLibPath(File clangxx, Context context) {
        List<String> paths = new ArrayList<>();
        File llvmBin = clangxx.getParentFile();
        if (llvmBin != null) {
            File prebuilt = llvmBin.getParentFile();
            if (prebuilt != null) {
                File lib64 = new File(prebuilt, "lib64");
                if (lib64.isDirectory()) paths.add(lib64.getAbsolutePath());
                File lib = new File(prebuilt, "lib");
                if (lib.isDirectory()) paths.add(lib.getAbsolutePath());
                File sysrootLib = new File(prebuilt, "sysroot/usr/lib/aarch64-linux-android");
                if (sysrootLib.isDirectory()) paths.add(sysrootLib.getAbsolutePath());
                // libc++_shared often under toolchains/llvm/prebuilt/.../lib
                File cxx = new File(prebuilt, "lib/clang");
                // also search nearby
            }
        }
        File ndk = guessNdkRoot(clangxx);
        File[] more = ndk.listFiles();
        if (more != null) {
            File hit = findFileNamed(ndk, new String[]{"libc++_shared.so"}, 10);
            if (hit != null && hit.getParentFile() != null) {
                paths.add(hit.getParentFile().getAbsolutePath());
            }
        }
        // app native lib dir
        try {
            String ni = context.getApplicationInfo().nativeLibraryDir;
            if (ni != null) paths.add(ni);
        } catch (Throwable ignored) {
        }
        if (paths.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) sb.append(':');
            sb.append(paths.get(i));
        }
        return sb.toString();
    }

    private static File resolveLinker() {
        String[] c = new String[]{
                "/system/bin/linker64",
                "/apex/com.android.runtime/bin/linker64",
                "/system/bin/linker"
        };
        for (String p : c) {
            File f = new File(p);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static File findFileNamed(File root, String[] names, int maxDepth) {
        return findFileNamed0(root, names, maxDepth, 0);
    }

    private static File findFileNamed0(File dir, String[] names, int maxDepth, int depth) {
        if (dir == null || !dir.isDirectory() || depth > maxDepth) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isFile()) {
                String n = k.getName();
                for (String want : names) {
                    if (want.equals(n)) return k;
                }
            }
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                // skip huge irrelevant trees a bit
                String n = k.getName();
                if (n.equals(".git") || n.equals("sources") && depth > 2) continue;
                File hit = findFileNamed0(k, names, maxDepth, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static void downloadTo(String urlSpec, File dest) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(dest.getAbsolutePath() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(urlSpec).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(600000);
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        int code = conn.getResponseCode();
        if (code >= 300 && code < 400) {
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(loc).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(600000);
            conn.connect();
            code = conn.getResponseCode();
        }
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Download HTTP " + code + " for " + urlSpec);
        }
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            conn.disconnect();
        }
        if (dest.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
        }
        if (!tmp.renameTo(dest)) {
            // fallback copy
            try (InputStream in = new FileInputStream(tmp);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void extract7z(File archive, File destDir) throws Exception {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IllegalStateException("Cannot create " + destDir);
        }
        // commons-compress 1.21+ prefers builder; File ctor still works on 1.26
        SevenZFile sevenZFile = SevenZFile.builder().setFile(archive).get();
        try {
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null) continue;
                // zip-slip guard
                File out = new File(destDir, name);
                String destPath = destDir.getCanonicalPath();
                String outPath = out.getCanonicalPath();
                if (!outPath.startsWith(destPath + File.separator) && !outPath.equals(destPath)) {
                    throw new IllegalStateException("Blocked path traversal entry: " + name);
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = sevenZFile.read(buffer)) > 0) {
                        fos.write(buffer, 0, n);
                    }
                }
            }
        } finally {
            sevenZFile.close();
        }
    }

    /**
     * After 7z extract, recreate critical symlinks that became tiny text files.
     * Typical: clang++ -> clang -> clang-21, ld.lld -> lld, ld -> ld.lld.
     */
    private static void repairFlattenedSymlinks(File root) {
        if (root == null || !root.isDirectory()) return;
        File bin = findFileNamed(root, new String[]{"clang-21"}, 14);
        if (bin == null) bin = findFileNamed(root, new String[]{"clang-20"}, 14);
        if (bin == null) return;
        File binDir = bin.getParentFile();
        if (binDir == null) return;

        // Prefer real ELF clang-XX as clang, then clang++ -> clang
        File clangReal = null;
        String[] reals = new String[]{"clang-21", "clang-20", "clang-19", "clang-18"};
        for (String r : reals) {
            File f = new File(binDir, r);
            if (isLikelyElf(f)) {
                clangReal = f;
                break;
            }
        }
        if (clangReal != null) {
            replaceWithSymlink(new File(binDir, "clang"), clangReal.getName());
            replaceWithSymlink(new File(binDir, "clang++"), "clang");
        }
        File lld = new File(binDir, "lld");
        if (isLikelyElf(lld)) {
            replaceWithSymlink(new File(binDir, "ld.lld"), "lld");
            replaceWithSymlink(new File(binDir, "ld"), "ld.lld");
        }
    }

    private static void replaceWithSymlink(File link, String targetName) {
        if (link == null || targetName == null) return;
        // If already a good ELF, leave it.
        if (isLikelyElf(link)) return;
        // Remove tiny text placeholder
        //noinspection ResultOfMethodCallIgnored
        link.delete();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "ln", "-sf", targetName, link.getAbsolutePath()
            });
            p.waitFor();
            if (!link.exists()) {
                // fallback: write a tiny shell wrapper (still real compiler under the hood)
                String sh = "#!/system/bin/sh\nexec \"$(dirname \"$0\")/" + targetName + "\" \"$@\"\n";
                writeUtf8(link, sh);
            }
            ensureExecutable(link);
        } catch (Throwable t) {
            try {
                String sh = "#!/system/bin/sh\nexec \"$(dirname \"$0\")/" + targetName + "\" \"$@\"\n";
                writeUtf8(link, sh);
                ensureExecutable(link);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void chmodTree(File root) {
        if (root == null || !root.exists()) return;
        ensureExecutable(root);
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) chmodTree(k);
            else {
                String n = k.getName();
                if (n.contains("clang") || n.contains("ld") || n.contains("llvm")
                        || n.endsWith(".so") || n.equals("as") || n.equals("ar")
                        || n.equals("clang++") || n.equals("clang")) {
                    ensureExecutable(k);
                }
            }
        }
    }

    private static void ensureExecutable(File f) {
        if (f == null || !f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.setExecutable(true, false);
        try {
            // best-effort native chmod
            Runtime.getRuntime().exec(new String[]{"chmod", "755", f.getAbsolutePath()}).waitFor();
        } catch (Throwable ignored) {
        }
    }

    private static final class Proc {
        final int exitCode;
        final String stdout;
        final String stderr;

        Proc(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static Proc runProcess(
            List<String> cmd,
            File cwd,
            Map<String, String> env,
            long timeoutMs
    ) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null && cwd.isDirectory()) pb.directory(cwd);
        Map<String, String> pbEnv = pb.environment();
        if (env != null) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    pbEnv.put(e.getKey(), e.getValue());
                }
            }
        }
        Process p = pb.start();
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread tOut = pump(p.getInputStream(), outBuf);
        Thread tErr = pump(p.getErrorStream(), errBuf);
        tOut.start();
        tErr.start();
        boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            tOut.join(1000);
            tErr.join(1000);
            return new Proc(124, outBuf.toString("UTF-8"),
                    errBuf.toString("UTF-8") + "\nprocess timeout after " + timeoutMs + "ms");
        }
        tOut.join();
        tErr.join();
        return new Proc(p.exitValue(), outBuf.toString("UTF-8"), errBuf.toString("UTF-8"));
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream out) {
        return new Thread(() -> {
            try {
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) out.write(buf, 0, n);
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }, "cpp-rt-pump");
    }

    private static void writeUtf8(File f, String s) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
