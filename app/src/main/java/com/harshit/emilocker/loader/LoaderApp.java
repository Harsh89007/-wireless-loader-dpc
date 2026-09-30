package com.harshit.emilocker.loader;

import android.app.Application;
import android.util.Log;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.conscrypt.Conscrypt;

import java.security.Security;

public class LoaderApp extends Application {

    private static final String TAG = "LoaderApp";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // Wireless ADB pairing / TLS के लिए जरूरी
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1);
                Log.i(TAG, "Conscrypt installed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Conscrypt failed", t);
        }

        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
                Log.i(TAG, "BouncyCastle installed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "BouncyCastle failed", t);
        }
    }
}
