package com.harshit.emilocker.loader;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

public class AdbHelper {

    private static final String TAG = "AdbHelper";

    public static final String TARGET_OWNER =
            "com.harshit.emilocker.customer/.MyAdminReceiver";

    public static class Result {
        public final boolean ok;
        public final String message;

        public Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 1200 ? s.substring(0, 1200) + "…" : s;
    }

    public static Result pair(Context context, String host, int pairPort, String code) {
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            boolean success = mgr.pair(host, pairPort, code);
            if (success) {
                return new Result(true, "Pairing successful ✅");
            }
            return new Result(false, "Pairing failed ❌\nCode चेक करें। Pairing dialog खुला होना चाहिए।");
        } catch (Throwable t) {
            Log.e(TAG, "pair failed", t);
            return new Result(false, "Pair error:\n" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "\n\n" + stack(t));
        }
    }

    public static Result connect(Context context, String host, int connectPort) {
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            mgr.setHostAddress(host);
            boolean connected = mgr.connect(connectPort);
            if (connected) {
                return new Result(true, "Connected ✅");
            }
            return new Result(false, "Connect failed ❌");
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
            return new Result(false, "Connect error:\n" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "\n\n" + stack(t));
        }
    }

    /**
     * Connect port mDNS से खोजो (pairing के बाद wireless debugging connect service).
     */
    public static int discoverConnectPort(Context context, String expectedHost, long timeoutMs) {
        AtomicInteger portRef = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);
        AdbDiscovery discovery = new AdbDiscovery(context);

        discovery.start(AdbDiscovery.TYPE_CONNECT, new AdbDiscovery.Listener() {
            @Override
            public void onFound(String host, int port, String serviceType) {
                // Prefer same host as paired device
                if (expectedHost != null && host != null
                        && (host.equals(expectedHost) || expectedHost.equals(host))) {
                    portRef.set(port);
                    latch.countDown();
                    discovery.stop();
                } else if (portRef.get() < 0) {
                    // First any connect service as fallback
                    portRef.set(port);
                    latch.countDown();
                    discovery.stop();
                }
            }

            @Override
            public void onLost(String serviceType) {
            }

            @Override
            public void onError(String message) {
                latch.countDown();
            }
        });

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        discovery.stop();
        return portRef.get();
    }

    /**
     * Full auto: pair → discover connect port → connect → set-device-owner
     */
    public static Result pairConnectAndGrant(Context context, String host, int pairPort, String code) {
        StringBuilder log = new StringBuilder();

        Result pair = pair(context, host, pairPort, code);
        log.append(pair.message).append("\n");
        if (!pair.ok) return new Result(false, log.toString());

        log.append("Connect port खोज रहे हैं (mDNS)...\n");
        int connectPort = discoverConnectPort(context, host, 12000);
        if (connectPort <= 0) {
            // Retry once — service sometimes appears late after pair
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            connectPort = discoverConnectPort(context, host, 10000);
        }
        if (connectPort <= 0) {
            return new Result(false, log + "Connect port नहीं मिला।\n"
                    + "Wireless debugging स्क्रीन खुली रखें, same Wi‑Fi।\n"
                    + "Manual connect port बाद में try करें।");
        }
        log.append("Connect port: ").append(connectPort).append("\n");

        Result conn = connect(context, host, connectPort);
        log.append(conn.message).append("\n");
        if (!conn.ok) return new Result(false, log.toString());

        log.append("Device Owner set कर रहे हैं...\n");
        Result grant = grantOwnership(context);
        log.append(grant.message);
        return new Result(grant.ok, log.toString());
    }

    public static Result grantOwnership(Context context) {
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            String cmd = "dpm set-device-owner " + TARGET_OWNER;
            AdbStream stream = mgr.openStream("shell:" + cmd);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = stream.openInputStream();
            byte[] buf = new byte[4096];
            int n;
            long end = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < end && (n = in.read(buf)) != -1) {
                if (n > 0) bos.write(buf, 0, n);
            }
            try {
                stream.close();
            } catch (Exception ignored) {
            }

            String out = bos.toString(StandardCharsets.UTF_8.name()).trim();
            String lower = out.toLowerCase();

            if (lower.contains("success") || lower.contains("device owner") || out.isEmpty()) {
                return new Result(true,
                        "Ownership OK ✅\nOutput: " + (out.isEmpty() ? "(empty)" : out)
                                + "\n\nCustomer App खोलें → Dealer Sync QR स्कैन करें।");
            }
            if (lower.contains("already the device owner")) {
                return new Result(true, "पहले से Device Owner ✅\n" + out);
            }
            if (lower.contains("account")) {
                return new Result(false,
                        "Fail: फोन पर Google/Mi अकाउंट है। हटाएँ या factory reset।\n\n" + out);
            }
            if (lower.contains("not allowed") || lower.contains("provisioning")) {
                return new Result(false,
                        "Fail: इस स्टेट में Owner set नहीं हो सकता। Factory reset (बिना अकाउंट)।\n\n" + out);
            }
            return new Result(false, "set-device-owner:\n" + (out.isEmpty() ? "(no output)" : out));
        } catch (Throwable t) {
            Log.e(TAG, "grant failed", t);
            return new Result(false, "Grant error:\n" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "\n\n" + stack(t));
        }
    }
}
