package com.apkstoapk.app.mcp;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

class McpShellTools {
    private final McpPathTools pathTools;

    McpShellTools(McpPathTools pathTools) {
        this.pathTools = pathTools;
    }

    JsonObject toolShell(JsonObject args) throws Exception {
        String cmd = args.has("cmd") ? args.get("cmd").getAsString() : "";
        if (cmd.length() == 0) {
            throw new Exception("缺少cmd");
        }
        File cwd = args.has("cwd") ? this.pathTools.resolve(args.get("cwd").getAsString()) : this.pathTools.getWorkDir();
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", cmd);
        if (cwd.exists() && cwd.isDirectory()) {
            builder.directory(cwd);
        }
        Process process = builder.start();
        String stdout = readStream(process.getInputStream());
        String stderr = readStream(process.getErrorStream());
        int exitCode = process.waitFor();
        JsonObject result = new JsonObject();
        result.addProperty("cmd", cmd);
        result.addProperty("cwd", cwd.getAbsolutePath());
        result.addProperty("exit_code", exitCode);
        result.addProperty("stdout", stdout);
        result.addProperty("stderr", stderr);
        return result;
    }

    private String readStream(InputStream inputStream) throws Exception {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            while (true) {
                int read = inputStream.read(buffer);
                if (read < 0) {
                    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
                }
                outputStream.write(buffer, 0, read);
            }
        } finally {
            inputStream.close();
        }
    }
}
