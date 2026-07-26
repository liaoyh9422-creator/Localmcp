package com.apkstoapk.app.core;

import com.apkstoapk.app.util.IoUtils;
import com.apkstoapk.app.util.SimpleApkLogger;
import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;

import java.io.File;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Readable AndroidManifest.xml export / import on a loaded module.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class ManifestXmlOps {
    private ManifestXmlOps() {}

    public static String toXml(ApkModule module, SimpleApkLogger logger) throws Exception {
        AndroidManifestBlock manifest = ManifestOps.requireManifest(module);
        FrameworkHelper.ensureAndroidFramework(module, logger);
        String xml = ManifestWorkflow.exportReadableXml(manifest, logger);
        return AndroidAttrNames.replaceUnknownAttrNames(xml);
    }

    public static File exportToFile(ApkModule module, File outXml, SimpleApkLogger logger)
            throws Exception {
        if (outXml == null) throw new IllegalArgumentException("outXml is null");
        String xml = toXml(module, logger);
        File parent = outXml.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (OutputStream os = IoUtils.getOutputStream(outXml);
             OutputStreamWriter w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            w.write(xml);
            w.flush();
        }
        if (logger != null) {
            logger.ok("Manifest 已导出", "Manifest exported", outXml.getAbsolutePath());
        }
        return outXml;
    }

    public static void applyXml(ApkModule module, String xml, SimpleApkLogger logger) throws Exception {
        ManifestWorkflow.applyReadableXml(module, xml, logger);
    }

    public static void applyXmlFile(ApkModule module, File xmlFile, SimpleApkLogger logger)
            throws Exception {
        if (xmlFile == null || !xmlFile.isFile()) {
            throw new IllegalArgumentException("xml missing: " + xmlFile);
        }
        byte[] bytes;
        try (java.io.InputStream in = IoUtils.getInputStream(xmlFile);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            IoUtils.copy(in, bos);
            bytes = bos.toByteArray();
        }
        String xml = new String(bytes, StandardCharsets.UTF_8);
        applyXml(module, xml, logger);
        if (logger != null) {
            logger.ok("Manifest XML 已应用", "Manifest XML applied", xmlFile.getAbsolutePath());
        }
    }
}