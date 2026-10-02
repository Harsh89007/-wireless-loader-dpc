package com.harshit.emilocker.loader;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

public class AdbHelper {

    private static final String TAG = "AdbHelper";

    public static final String TARGET_OWNER =
            "com.harshit.emilocker.customer/.MyAdminReceiver";

    public static class Result {
        public final boolean ok;
        public final String message;
        /** Pair ok था लेकिन connect port auto नहीं मिला — UI manual port माँगे */
        public final boolean needManualConnectPort;

        public Result(boolean ok, String message) {
            this(ok, message, false);
        }

        public Result(boolean ok, String message, boolean needManualConnectPort) {
            this.ok = ok;
            this.message = message;
            this.needManualConnectPort = needManualConnectPort;
        }
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 1000 ? s.substring(0, 1000) + "…" : s;
    }

    public static Result pair(Context context, String host, int pairPort, String code) {
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            boolean success = mgr.pair(host, pairPort, code);
            if (success) {
                return new Result(true, "Pairing successful ✅");
            }
            return new Result(false, "Pairing failed ❌\nCode / dialog चेक करें।");
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
            mgr.setTimeout(20, TimeUnit.SECONDS);
            boolean connected = mgr.connect(connectPort);
            if (connected) {
                return new Result(true, "Connected ✅");
            }
            return new Result(false, "Connect failed ❌ port " + connectPort);
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
            return new Result(false, "Connect error:\n" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "\n\n" + stack(t));
        }
    }

    /**
     * mDNS से connect ports इकट्ठा करो (remote devices — custom discovery).
     */
    public static List<int[]> discoverConnectEndpoints(Context context, String preferredHost, long timeoutMs) {
        List<int[]> results = new ArrayList<>(); // not used; use string list
        List<String> hosts = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        AdbDiscovery discovery = new AdbDiscovery(context);

        discovery.start(AdbDiscovery.TYPE_CONNECT, new AdbDiscovery.Listener() {
            @Override
            public void onFound(String host, int port, String serviceType) {
                if (finished.get()) return;
                synchronized (hosts) {
                    boolean exists = false;
                    for (int i = 0; i < hosts.size(); i++) {
                        if (hosts.get(i).equals(host) && ports.get(i) == port) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        hosts.add(host);
                        ports.add(port);
                        Log.d(TAG, "Connect service: " + host + ":" + port);
                    }
                    // Prefer matching host — can finish early
                    if (preferredHost != null && preferredHost.equals(host)) {
                        finished.set(true);
                        done.countDown();
                        discovery.stop();
                    }
                }
            }

            @Override
            public void onLost(String serviceType) {
            }

            @Override
            public void onError(String message) {
                done.countDown();
            }
        });

        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        finished.set(true);
        discovery.stop();

        List<int[]> endpoints = new ArrayList<>();
        // Prefer preferred host first
        synchronized (hosts) {
            for (int i = 0; i < hosts.size(); i++) {
                if (preferredHost != null && preferredHost.equals(hosts.get(i))) {
                    endpoints.add(new int[]{i, ports.get(i)}); // marker
                }
            }
        }
        // Build ordered host:port via parallel lists return as encoded
        // Simpler: return ports for preferred host, then all
        List<Integer> orderedPorts = new ArrayList<>();
        synchronized (hosts) {
            for (int i = 0; i < hosts.size(); i++) {
                if (preferredHost != null && preferredHost.equals(hosts.get(i))) {
                    orderedPorts.add(ports.get(i));
                }
            }
            for (int i = 0; i < hosts.size(); i++) {
                if (preferredHost == null || !preferredHost.equals(hosts.get(i))) {
                    // still try same subnet later in caller with host list
                    orderedPorts.add(ports.get(i));
                }
            }
        }
        // Store hosts in static for last discovery — cleaner API below
        lastDiscoverHosts.clear();
        lastDiscoverPorts.clear();
        synchronized (hosts) {
            lastDiscoverHosts.addAll(hosts);
            lastDiscoverPorts.addAll(ports);
        }
        return endpoints;
    }

    private static final List<String> lastDiscoverHosts = new ArrayList<>();
    private static final List<Integer> lastDiscoverPorts = new ArrayList<>();

    public static Result tryConnectAfterPair(Context context, String pairHost) {
        StringBuilder log = new StringBuilder();
        log.append("Connect port खोज रहे हैं...\n");

        // Wait for wireless debugging to advertise connect service after pair dialog closes
        try {
            Thread.sleep(2500);
        } catch (InterruptedException ignored) {
        }

        discoverConnectEndpoints(context, pairHost, 18000);

        // First: exact host match
        for (int i = 0; i < lastDiscoverHosts.size(); i++) {
            String h = lastDiscoverHosts.get(i);
            int p = lastDiscoverPorts.get(i);
            if (pairHost != null && pairHost.equals(h)) {
                log.append("Trying ").append(h).append(":").append(p).append("\n");
                Result c = connect(context, h, p);
                if (c.ok) {
                    return new Result(true, log + c.message);
                }
                log.append(c.message).append("\n");
            }
        }

        // Second: any discovered connect service
        for (int i = 0; i < lastDiscoverHosts.size(); i++) {
            String h = lastDiscoverHosts.get(i);
            int p = lastDiscoverPorts.get(i);
            if (pairHost != null && pairHost.equals(h)) continue; // already tried
            log.append("Trying ").append(h).append(":").append(p).append("\n");
            Result c = connect(context, h, p);
            if (c.ok) {
                return new Result(true, log + c.message);
            }
            log.append(c.message).append("\n");
        }

        // Third: retry discovery once more
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
        }
        discoverConnectEndpoints(context, pairHost, 12000);
        for (int i = 0; i < lastDiscoverHosts.size(); i++) {
            String h = lastDiscoverHosts.get(i);
            int p = lastDiscoverPorts.get(i);
            log.append("Retry ").append(h).append(":").append(p).append("\n");
            Result c = connect(context, h, p);
            if (c.ok) {
                return new Result(true, log + c.message);
            }
        }

        return new Result(false,
                log + "Connect port auto नहीं मिला।\n\n"
                        + "Customer फोन → Wireless debugging स्क्रीन (मुख्य),\n"
                        + "नीचे IP:PORT दिखता है — सिर्फ PORT नंबर\n"
                        + "नीचे Connect Port में डालकर CONNECT & GRANT दबाएँ।",
                true);
    }

    /**
     * Full flow: pair → auto connect → grant.
     * अगर connect auto fail → needManualConnectPort=true
     */
    public static Result pairConnectAndGrant(Context context, String host, int pairPort, String code) {
        StringBuilder log = new StringBuilder();

        Result pair = pair(context, host, pairPort, code);
        log.append(pair.message).append("\n");
        if (!pair.ok) return new Result(false, log.toString());

        Result conn = tryConnectAfterPair(context, host);
        log.append(conn.message).append("\n");
        if (!conn.ok) {
            return new Result(false, log.toString(), true);
        }

        log.append("Device Owner set...\n");
        Result grant = grantOwnership(context);
        log.append(grant.message);
        return new Result(grant.ok, log.toString());
    }

    /**
     * Manual connect port के बाद: connect + grant
     */
    public static Result connectAndGrant(Context context, String host, int connectPort) {
        StringBuilder log = new StringBuilder();
        Result conn = connect(context, host, connectPort);
        log.append(conn.message).append("\n");
        if (!conn.ok) return new Result(false, log.toString());

        Result grant = grantOwnership(context);
        log.append(grant.message);
        return new Result(grant.ok, log.toString());
    }

    public static Result grantOwnership(Context context) {
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            if (!mgr.isConnected()) {
                return new Result(false, "ADB connected नहीं है। पहले Connect सफल करें।");
            }
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
                        "Ownership OK ✅\n" + (out.isEmpty() ? "" : out + "\n")
                                + "Customer App खोलें → Sync QR स्कैन करें।");
            }
            if (lower.contains("already the device owner")) {
                return new Result(true, "पहले से Device Owner ✅\n" + out);
            }
            if (lower.contains("account")) {
                return new Result(false,
                        "Fail: फोन पर Google/Mi अकाउंट है। हटाएँ / factory reset।\n\n" + out);
            }
            if (lower.contains("not allowed") || lower.contains("provisioning")) {
                return new Result(false,
                        "Fail: Owner set नहीं हो सकता इस स्टेट में।\nFactory reset (बिना अकाउंट)।\n\n" + out);
            }
            return new Result(false, "set-device-owner:\n" + (out.isEmpty() ? "(no output)" : out));
        } catch (Throwable t) {
            Log.e(TAG, "grant failed", t);
            return new Result(false, "Grant error:\n" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "\n\n" + stack(t));
        }
    }
}
