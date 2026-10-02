package com.harshit.emilocker.loader;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

/**
 * Fast path (reference style):
 * SEARCH → IP + pair port auto
 * User enters only 6-digit code
 * PAIR → connect port auto (fast) → connect → device owner
 */
public class AdbHelper {

    private static final String TAG = "AdbHelper";

    public static final String TARGET_OWNER =
            "com.harshit.emilocker.customer/.MyAdminReceiver";

    public static class Result {
        public final boolean ok;
        public final String message;
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
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
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
            mgr.setTimeout(12, TimeUnit.SECONDS);
            boolean connected = mgr.connect(connectPort);
            if (connected) {
                return new Result(true, "Connected ✅ " + host + ":" + connectPort);
            }
            return new Result(false, "Connect failed ❌ " + host + ":" + connectPort);
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
            return new Result(false, "Connect error: " + t.getMessage());
        }
    }

    /**
     * Fast mDNS: stop as soon as preferred host is resolved (or timeout).
     */
    private static List<String[]> collectConnectEndpoints(Context context, String preferredHost,
                                                          long timeoutMs) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> list = new ArrayList<>();
        CountDownLatch preferredFound = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AdbDiscovery discovery = new AdbDiscovery(context);

        discovery.start(AdbDiscovery.TYPE_CONNECT, new AdbDiscovery.Listener() {
            @Override
            public void onFound(String host, int port, String serviceType) {
                if (stop.get()) return;
                String key = host + ":" + port;
                synchronized (seen) {
                    if (seen.add(key)) {
                        list.add(new String[]{host, String.valueOf(port)});
                        Log.d(TAG, "mDNS connect: " + key);
                        if (preferredHost != null && preferredHost.equals(host)) {
                            preferredFound.countDown();
                        }
                    }
                }
            }

            @Override
            public void onLost(String serviceType) {
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "discover: " + message);
            }
        });

        try {
            // Exit early when preferred host appears
            preferredFound.await(timeoutMs, TimeUnit.MILLISECONDS);
            // Tiny settle so resolve completes
            if (preferredFound.getCount() == 0) {
                Thread.sleep(300);
            }
        } catch (InterruptedException ignored) {
        }
        stop.set(true);
        discovery.stop();
        return list;
    }

    private static Result tryEndpoints(Context context, String pairHost,
                                       List<String[]> endpoints, StringBuilder log) {
        for (String[] ep : endpoints) {
            String h = ep[0];
            int p;
            try {
                p = Integer.parseInt(ep[1]);
            } catch (Exception e) {
                continue;
            }
            if (pairHost != null && !pairHost.equals(h)) continue;
            log.append("Trying ").append(h).append(":").append(p).append("\n");
            Result c = connect(context, h, p);
            if (c.ok) return new Result(true, log + c.message);
        }
        for (String[] ep : endpoints) {
            String h = ep[0];
            int p;
            try {
                p = Integer.parseInt(ep[1]);
            } catch (Exception e) {
                continue;
            }
            if (pairHost != null && pairHost.equals(h)) continue;
            log.append("Trying ").append(h).append(":").append(p).append("\n");
            Result c = connect(context, h, p);
            if (c.ok) return new Result(true, log + c.message);
        }
        return new Result(false, log.toString());
    }

    /**
     * Fast auto-connect after pair (target ~2–6s when network OK).
     */
    public static Result tryConnectAfterPair(Context context, String pairHost) {
        StringBuilder log = new StringBuilder();
        log.append("Connect port auto...\n");

        // Very short settle after pair dialog closes
        try {
            Thread.sleep(600);
        } catch (InterruptedException ignored) {
        }

        // Fast round: up to 6s, exits early on preferred host
        List<String[]> endpoints = collectConnectEndpoints(context, pairHost, 6000);
        Result r = tryEndpoints(context, pairHost, endpoints, log);
        if (r.ok) return r;

        // Second short round: 5s
        log.append("Retry...\n");
        endpoints = collectConnectEndpoints(context, pairHost, 5000);
        r = tryEndpoints(context, pairHost, endpoints, log);
        if (r.ok) return r;

        // Optional autoConnect
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            mgr.setHostAddress(pairHost);
            mgr.setTimeout(8, TimeUnit.SECONDS);
            try {
                java.lang.reflect.Method m = mgr.getClass()
                        .getMethod("autoConnect", Context.class, long.class);
                Object ok = m.invoke(mgr, context, 8000L);
                if (Boolean.TRUE.equals(ok)) {
                    return new Result(true, log + "Connected ✅ (auto)");
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable ignored) {
        }

        return new Result(false,
                log + "Connect port नहीं मिला।\n"
                        + "Wireless debugging मुख्य स्क्रीन खुली रखें।\n"
                        + "या PORT manually डालें।",
                true);
    }

    public static Result pairConnectAndGrant(Context context, String host, int pairPort, String code) {
        StringBuilder log = new StringBuilder();

        // Pre-scan connect mDNS WHILE pairing (saves time after pair)
        AtomicReference<List<String[]>> preFound = new AtomicReference<>(new ArrayList<>());
        AtomicBoolean preDone = new AtomicBoolean(false);
        Thread preScan = new Thread(() -> {
            try {
                // Long enough to cover pair + dialog close
                List<String[]> found = collectConnectEndpoints(context, host, 25000);
                preFound.set(found);
            } catch (Throwable ignored) {
            } finally {
                preDone.set(true);
            }
        }, "pre-connect-scan");
        preScan.start();

        Result pair = pair(context, host, pairPort, code);
        log.append(pair.message).append("\n");
        if (!pair.ok) {
            preScan.interrupt();
            return new Result(false, log.toString());
        }

        // Use anything pre-scan already found (often ready right after pair)
        try {
            Thread.sleep(400);
        } catch (InterruptedException ignored) {
        }

        List<String[]> early = preFound.get();
        if (early != null && !early.isEmpty()) {
            log.append("Connect port auto...\n");
            Result c = tryEndpoints(context, host, early, log);
            if (c.ok) {
                log.append(c.message).append("\n");
                Result grant = grantOwnership(context);
                log.append(grant.message);
                return new Result(grant.ok, log.toString());
            }
        }

        // Wait a bit more for pre-scan if still running
        if (!preDone.get()) {
            try {
                preScan.join(4000);
            } catch (InterruptedException ignored) {
            }
            early = preFound.get();
            if (early != null && !early.isEmpty()) {
                Result c = tryEndpoints(context, host, early, log);
                if (c.ok) {
                    log.append(c.message).append("\n");
                    Result grant = grantOwnership(context);
                    log.append(grant.message);
                    return new Result(grant.ok, log.toString());
                }
            }
        }

        Result conn = tryConnectAfterPair(context, host);
        log.append(conn.message).append("\n");
        if (!conn.ok) {
            return new Result(false, log.toString(), true);
        }

        Result grant = grantOwnership(context);
        log.append(grant.message);
        return new Result(grant.ok, log.toString());
    }

    public static Result connectAndGrant(Context context, String host, int connectPort) {
        StringBuilder log = new StringBuilder();
        Result conn = connect(context, host, connectPort);
        log.append(conn.message).append("\n");
        if (!conn.ok) return new Result(false, log.toString());

        Result grant = grantOwnership(context);
        log.append(grant.message);
        return new Result(grant.ok, log.toString());
    }

    private static String runShell(AbsAdbConnectionManager mgr, String command) throws Exception {
        try {
            AdbStream stream = mgr.openStream("shell:" + command);
            String out = readStreamFully(stream);
            if (out != null) return out;
        } catch (Exception e) {
            Log.w(TAG, "shell:cmd: " + e.getMessage());
        }
        AdbStream stream = mgr.openStream("shell:");
        try {
            OutputStream os = stream.openOutputStream();
            os.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            os.write("exit\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception e) {
            Log.w(TAG, "shell write: " + e.getMessage());
        }
        return readStreamFully(stream);
    }

    private static String readStreamFully(AdbStream stream) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            InputStream in = stream.openInputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < deadline) {
                int n;
                try {
                    n = in.read(buf);
                } catch (Exception e) {
                    break;
                }
                if (n == -1) break;
                if (n > 0) bos.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "readStream: " + e.getMessage());
        } finally {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
        try {
            return bos.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (Exception e) {
            return bos.toString().trim();
        }
    }

    public static Result grantOwnership(Context context) {
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            if (!mgr.isConnected()) {
                return new Result(false, "ADB connected नहीं है।");
            }

            String pmOut = runShell(mgr, "pm path com.harshit.emilocker.customer");
            if (pmOut == null) pmOut = "";
            if (!pmOut.contains("package:") && !pmOut.contains("com.harshit")) {
                return new Result(false,
                        "Customer app install नहीं।\nपहले Customer APK install करो।");
            }

            String out = runShell(mgr, "dpm set-device-owner " + TARGET_OWNER);
            if (out == null) out = "";
            String lower = out.toLowerCase();

            String verify = runShell(mgr, "dpm list-owners");
            if (verify == null) verify = "";

            boolean already = lower.contains("already")
                    || lower.contains("device owner is already set");
            boolean ownerSet = already
                    || verify.toLowerCase().contains("com.harshit.emilocker.customer")
                    || lower.contains("success");

            if (ownerSet || already) {
                return new Result(true,
                        "Ownership OK ✅\n"
                                + (already ? "Device Owner पहले से set है।\n" : "")
                                + "Customer App खोलें → Sync QR स्कैन करें।");
            }

            if (lower.contains("account")) {
                return new Result(false,
                        "Fail: Google/Mi account हटाएँ या factory reset।\n" + out);
            }
            if (lower.contains("not allowed") || lower.contains("provisioning")) {
                return new Result(false,
                        "Fail: Factory reset (बिना account) → app → loader।\n" + out);
            }

            if (out.isEmpty()) {
                verify = runShell(mgr, "dumpsys device_policy | grep -i owner");
                if (verify != null && (verify.toLowerCase().contains("com.harshit")
                        || verify.toLowerCase().contains("device owner"))) {
                    return new Result(true, "Ownership OK ✅\nCustomer App → Sync QR।");
                }
                return new Result(false, "Owner set confirm नहीं। Verify: "
                        + (verify == null || verify.isEmpty() ? "(empty)" : verify));
            }

            return new Result(false, "set-device-owner:\n" + out + "\n" + verify);
        } catch (Throwable t) {
            Log.e(TAG, "grant failed", t);
            if (t.getMessage() != null && t.getMessage().toLowerCase().contains("stream closed")) {
                return new Result(false, "Shell बंद। दोबारा try। Customer install + no account।");
            }
            return new Result(false, "Grant error: " + t.getMessage());
        }
    }
}
