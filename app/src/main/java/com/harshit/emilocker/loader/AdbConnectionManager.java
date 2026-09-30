package com.harshit.emilocker.loader;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

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
        File keyFile = new File(context.getFilesDir(), "adb_key");
        File certFile = new File(context.getFilesDir(), "adb_cert");

        mPrivateKey = readPrivateKey(keyFile);
        mCertificate = readCertificate(certFile);

        if (mPrivateKey == null || mCertificate == null) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new SecureRandom());
            KeyPair kp = kpg.generateKeyPair();
            mPrivateKey = kp.getPrivate();

            long now = System.currentTimeMillis();
            X500Name name = new X500Name("CN=WirelessLoaderDPC");
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name,
                    BigInteger.valueOf(now),
                    new Date(now - 86400000L),
                    new Date(now + 86400000L * 365 * 10),
                    name,
                    kp.getPublic()
            );
            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(mPrivateKey);
            X509CertificateHolder holder = builder.build(signer);
            mCertificate = new JcaX509CertificateConverter().getCertificate(holder);

            writeBytes(keyFile, mPrivateKey.getEncoded());
            writeBytes(certFile, mCertificate.getEncoded());
        }
    }

    @Nullable
    private static PrivateKey readPrivateKey(File file) {
        try {
            if (!file.exists()) return null;
            byte[] data = readBytes(file);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(data));
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Certificate readCertificate(File file) {
        try {
            if (!file.exists()) return null;
            return CertificateFactory.getInstance("X.509")
                    .generateCertificate(new FileInputStream(file));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readBytes(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int r = in.read(buf);
            if (r != buf.length) throw new Exception("short read");
            return buf;
        }
    }

    private static void writeBytes(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
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
