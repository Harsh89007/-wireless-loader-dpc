package com.harshit.emilocker.loader;

import android.content.Context;
import android.os.Build;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

public class AdbConnectionManager extends AbsAdbConnectionManager {

    private static AdbConnectionManager INSTANCE;

    public static synchronized AdbConnectionManager getInstance(Context context) throws Exception {
        if (INSTANCE == null) {
            INSTANCE = new AdbConnectionManager(context.getApplicationContext());
        }
        return INSTANCE;
    }

    private PrivateKey mPrivateKey;
    private Certificate mCertificate;

    private AdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);

        mPrivateKey = readPrivateKey(context);
        mCertificate = readCertificate(context);

        if (mPrivateKey == null || mCertificate == null) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair kp = kpg.generateKeyPair();
            PublicKey publicKey = kp.getPublic();
            mPrivateKey = kp.getPrivate();

            String subject = "CN=WirelessLoaderDPC";
            String algorithmName = "SHA256withRSA";
            long expiry = System.currentTimeMillis() + 86400000L * 365 * 10;

            CertificateExtensions extensions = new CertificateExtensions();
            extensions.set("SubjectKeyIdentifier",
                    new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));

            X500Name x500 = new X500Name(subject);
            Date notBefore = new Date(System.currentTimeMillis() - 86400000L);
            Date notAfter = new Date(expiry);
            extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

            CertificateValidity validity = new CertificateValidity(notBefore, notAfter);
            X509CertInfo info = new X509CertInfo();
            info.set("version", new CertificateVersion(2));
            info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
            info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
            info.set("subject", new CertificateSubjectName(x500));
            info.set("key", new CertificateX509Key(publicKey));
            info.set("validity", validity);
            info.set("issuer", new CertificateIssuerName(x500));
            info.set("extensions", extensions);

            X509CertImpl cert = new X509CertImpl(info);
            cert.sign(mPrivateKey, algorithmName);
            mCertificate = cert;

            writePrivateKey(context, mPrivateKey);
            writeCertificate(context, mCertificate);
        }
    }

    @Nullable
    private static PrivateKey readPrivateKey(Context context) {
        try {
            File f = new File(context.getFilesDir(), "adb_private.key");
            if (!f.exists()) return null;
            byte[] bytes = new byte[(int) f.length()];
            try (InputStream in = new FileInputStream(f)) {
                //noinspection ResultOfMethodCallIgnored
                in.read(bytes);
            }
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Certificate readCertificate(Context context) {
        try {
            File f = new File(context.getFilesDir(), "adb_cert.der");
            if (!f.exists()) return null;
            try (InputStream in = new FileInputStream(f)) {
                return CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void writePrivateKey(Context context, PrivateKey key) throws Exception {
        File f = new File(context.getFilesDir(), "adb_private.key");
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(key.getEncoded());
        }
    }

    private static void writeCertificate(Context context, Certificate cert) throws Exception {
        File f = new File(context.getFilesDir(), "adb_cert.der");
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(cert.getEncoded());
        }
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "WirelessLoaderDPC";
    }
}
