package com.apkstoapk.app.core;

import com.android.apksig.ApkVerifier;
import com.apkstoapk.app.util.SimpleApkLogger;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * APK signature verification facade over apksig {@link ApkVerifier}.
 * Not wired into UI / {@link ApksMerger}.
 */
public final class VerifyOps {
    private VerifyOps() {}

    public static final class Report {
        public final boolean verified;
        public final boolean v1;
        public final boolean v2;
        public final boolean v3;
        public final boolean v31;
        public final boolean v4;
        public final List<String> signerSubjects;
        public final List<String> errors;
        public final List<String> warnings;

        public Report(
                boolean verified,
                boolean v1,
                boolean v2,
                boolean v3,
                boolean v31,
                boolean v4,
                List<String> signerSubjects,
                List<String> errors,
                List<String> warnings
        ) {
            this.verified = verified;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
            this.v31 = v31;
            this.v4 = v4;
            this.signerSubjects = signerSubjects;
            this.errors = errors;
            this.warnings = warnings;
        }
    }

    public static Report verify(File apkFile, SimpleApkLogger logger) throws Exception {
        if (apkFile == null || !apkFile.isFile()) {
            throw new IllegalArgumentException("apk missing: " + apkFile);
        }
        if (logger != null) {
            logger.stage("校验 APK 签名", "Verify APK signature");
            logger.bi("路径", "Path", apkFile.getAbsolutePath());
        }
        ApkVerifier.Result result = new ApkVerifier.Builder(apkFile).build().verify();
        List<String> subjects = new ArrayList<>();
        List<X509Certificate> certs = result.getSignerCertificates();
        if (certs != null) {
            for (X509Certificate c : certs) {
                if (c != null && c.getSubjectDN() != null) {
                    subjects.add(c.getSubjectDN().toString());
                }
            }
        }
        List<String> errors = new ArrayList<>();
        if (result.getErrors() != null) {
            for (Object e : result.getErrors()) {
                if (e != null) errors.add(e.toString());
            }
        }
        List<String> warnings = new ArrayList<>();
        if (result.getWarnings() != null) {
            for (Object w : result.getWarnings()) {
                if (w != null) warnings.add(w.toString());
            }
        }
        Report report = new Report(
                result.isVerified(),
                result.isVerifiedUsingV1Scheme(),
                result.isVerifiedUsingV2Scheme(),
                result.isVerifiedUsingV3Scheme(),
                result.isVerifiedUsingV31Scheme(),
                result.isVerifiedUsingV4Scheme(),
                subjects,
                errors,
                warnings
        );
        if (logger != null) {
            logger.ok("校验结束", "Verify done",
                    "verified=" + report.verified
                            + " v1=" + report.v1
                            + " v2=" + report.v2
                            + " v3=" + report.v3
                            + " certs=" + subjects.size());
            for (String err : errors) logger.item("错误", "Error", err);
        }
        return report;
    }

    public static boolean isVerified(File apkFile) throws Exception {
        return verify(apkFile, null).verified;
    }
}