package com.apkstoapk.app.core;

import android.content.Context;

import com.android.apksig.ApkSigner;
import com.android.apksig.apk.ApkFormatException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * Signs merged APK with embedded debug keystore (copied from AntiSplit-M).
 */
public final class SignHelper {
    private static final String ASSET_KEYSTORE = "debug23.keystore";
    private static final String KEYSTORE_PASSWORD = "android";

    private SignHelper() {}

    public static void signWithDebugKey(Context context, File inputApk, File outputApk)
            throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException,
            UnrecoverableEntryException, ApkFormatException, SignatureException, InvalidKeyException {
        try (InputStream key = context.getAssets().open(ASSET_KEYSTORE)) {
            sign(key, KEYSTORE_PASSWORD, inputApk, outputApk, true, true, true);
        }
    }

    public static void sign(
            InputStream keystoreStream,
            String password,
            File inputApk,
            File outputApk,
            boolean v1,
            boolean v2,
            boolean v3
    ) throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException,
            UnrecoverableEntryException, ApkFormatException, SignatureException, InvalidKeyException {
        char[] pw = password.toCharArray();
        KeyStore keystore = KeyStore.getInstance("BKS");
        keystore.load(keystoreStream, pw);
        String alias = keystore.aliases().nextElement();
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keystore.getEntry(
                alias,
                new KeyStore.PasswordProtection(pw)
        );

        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                "CERT",
                entry.getPrivateKey(),
                Collections.singletonList((X509Certificate) entry.getCertificate())
        ).build();

        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setCreatedBy("ApksToApk")
                .setV1SigningEnabled(v1)
                .setV2SigningEnabled(v2)
                .setV3SigningEnabled(v3)
                .build()
                .sign();
    }
}
