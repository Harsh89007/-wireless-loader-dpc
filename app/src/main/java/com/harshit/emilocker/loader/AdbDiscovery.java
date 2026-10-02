package com.harshit.emilocker.loader;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;

/**
 * Remote device mDNS discovery for Wireless Debugging.
 * libadb AdbMdns only accepts localhost — यहाँ LAN devices (customer phone) discover होते हैं.
 */
public class AdbDiscovery {

    private static final String TAG = "AdbDiscovery";

    public static final String TYPE_PAIRING = "_adb-tls-pairing._tcp";
    public static final String TYPE_CONNECT = "_adb-tls-connect._tcp";

    public interface Listener {
        void onFound(String host, int port, String serviceType);

        void onLost(String serviceType);

        void onError(String message);
    }

    private final NsdManager nsdManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean running;
    private String activeType;

    public AdbDiscovery(Context context) {
        nsdManager = (NsdManager) context.getApplicationContext()
                .getSystemService(Context.NSD_SERVICE);
    }

    public void start(String serviceType, Listener listener) {
        stop();
        activeType = serviceType;
        running = true;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                main.post(() -> listener.onError("Discovery start failed: " + errorCode));
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Started: " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Stopped: " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!running) return;
                try {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            Log.w(TAG, "Resolve failed: " + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo info) {
                            if (!running) return;
                            InetAddress host = info.getHost();
                            int port = info.getPort();
                            if (host == null || port <= 0) return;
                            String ip = host.getHostAddress();
                            if (ip == null) return;
                            // Skip IPv6 link-local noise if needed
                            if (ip.contains("%")) {
                                ip = ip.substring(0, ip.indexOf('%'));
                            }
                            final String finalIp = ip;
                            main.post(() -> listener.onFound(finalIp, port, serviceType));
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "resolve error", e);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                main.post(() -> listener.onLost(serviceType));
            }
        };

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            running = false;
            main.post(() -> listener.onError(e.getMessage() != null ? e.getMessage() : "NSD error"));
        }
    }

    public void stop() {
        running = false;
        if (discoveryListener != null && nsdManager != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
        }
        discoveryListener = null;
        activeType = null;
    }

    public boolean isRunning() {
        return running;
    }
}
