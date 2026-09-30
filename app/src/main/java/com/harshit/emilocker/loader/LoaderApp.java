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

        // Android का टूटा BC हटाओ, पूरा BC लगाओ
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
            Log.i(TAG, "BouncyCastle installed at 1");
        } catch (Throwable t) {
            Log.e(TAG, "BouncyCastle failed", t);
        }

        try {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1);
                Log.i(TAG, "Conscrypt installed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Conscrypt failed", t);
        }
    }
}
