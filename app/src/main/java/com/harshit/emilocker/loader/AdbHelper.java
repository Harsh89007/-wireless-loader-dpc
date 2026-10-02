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
 * Reference APK style:
 * SEARCH → auto IP + pairing port
 * User only types 6-digit code
 * One button → pair → auto find connect port → connect → set device owner
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
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
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
            mgr.setTimeout(25, TimeUnit.SECONDS);
            boolean connected = mgr.connect(connectPort);
            if (connected) {
                return new Result(true, "Connected ✅ " + host + ":" + connectPort);
            }
            return new Result(false, "Connect failed ❌ " + host + ":" + connectPort);
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
            return new Result(false, "Connect error:\n" + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    /**
     * Continuous mDNS for connect service — collects all host:port seen.
     */
    private static List<String[]> collectConnectEndpoints(Context context, String preferredHost,
                                                          long totalMs) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> list = new ArrayList<>();
        long end = System.currentTimeMillis() + totalMs;
        AdbDiscovery discovery = new AdbDiscovery(context);
        AtomicBoolean stop = new AtomicBoolean(false);

        discovery.start(AdbDiscovery.TYPE_CONNECT, new AdbDiscovery.Listener() {
            @Override
            public void onFound(String host, int port, String serviceType) {
                if (stop.get()) return;
                String key = host + ":" + port;
                synchronized (seen) {
                    if (seen.add(key)) {
                        list.add(new String[]{host, String.valueOf(port)});
                        Log.d(TAG, "mDNS connect: " + key);
                    }
                }
            }

            @Override
            public void onLost(String serviceType) {
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "discover err: " + message);
            }
        });

        // Poll in rounds while discovery runs
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                break;
            }
            // If we already have preferred host, can exit early after a bit
            synchronized (seen) {
                if (preferredHost != null) {
                    for (String[] ep : list) {
                        if (preferredHost.equals(ep[0])) {
                            // keep scanning a little more then stop
                            if (System.currentTimeMillis() + 3000 >= end) {
                                stop.set(true);
                            }
                        }
                    }
                }
            }
        }
        stop.set(true);
        discovery.stop();
        return list;
    }

    /**
     * After pair: long auto-search for connect port (reference APK style).
     * Customer must keep Wireless debugging MAIN screen open (not only pairing dialog).
     */
    public static Result tryConnectAfterPair(Context context, String pairHost) {
        StringBuilder log = new StringBuilder();
        log.append("Connect port auto खोज रहे हैं...\n");
        log.append("(Customer: Wireless debugging खुला रखें)\n");

        // Short settle after pair dialog closes
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
        }

        // Round 1: 20s continuous mDNS
        List<String[]> endpoints = collectConnectEndpoints(context, pairHost, 20000);
        Result r = tryEndpoints(context, pairHost, endpoints, log);
        if (r.ok) return r;

        // Round 2: another 20s (service often appears late)
        log.append("दोबारा खोज...\n");
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }
        endpoints = collectConnectEndpoints(context, pairHost, 20000);
        r = tryEndpoints(context, pairHost, endpoints, log);
        if (r.ok) return r;

        // Round 3: libadb autoConnect if available
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            mgr.setHostAddress(pairHost);
            mgr.setTimeout(15, TimeUnit.SECONDS);
            // Some libadb versions: autoConnect(context, timeoutMs)
            try {
                java.lang.reflect.Method m = mgr.getClass().getMethod(
                        "autoConnect", Context.class, long.class);
                Object ok = m.invoke(mgr, context, 15000L);
                if (Boolean.TRUE.equals(ok) || (ok instanceof Boolean && (Boolean) ok)) {
                    log.append("autoConnect ✅\n");
                    return new Result(true, log + "Connected ✅ (auto)");
                }
            } catch (NoSuchMethodException ignored) {
                try {
                    java.lang.reflect.Method m2 = AbsAdbConnectionManager.class
                            .getMethod("autoConnect", Context.class, long.class);
                    Object ok = m2.invoke(mgr, context, 15000L);
                    if (Boolean.TRUE.equals(ok)) {
                        log.append("autoConnect ✅\n");
                        return new Result(true, log + "Connected ✅ (auto)");
                    }
                } catch (Exception ignored2) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "autoConnect skip: " + t.getMessage());
        }

        return new Result(false,
                log + "\nConnect port auto नहीं मिला।\n\n"
                        + "Customer फोन:\n"
                        + "Settings → Developer options → Wireless debugging\n"
                        + "मुख्य स्क्रीन खुली रखो (Pair dialog नहीं)।\n"
                        + "वहाँ IP:PORT दिखेगा → सिर्फ PORT नीचे डालो।",
                true);
    }

    private static Result tryEndpoints(Context context, String pairHost,
                                       List<String[]> endpoints, StringBuilder log) {
        // Prefer same host as pairing IP
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
            log.append("  fail\n");
        }
        // Any other host
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
            log.append("  fail\n");
        }
        if (endpoints.isEmpty()) {
            log.append("(mDNS पर कोई connect service नहीं)\n");
        }
        return new Result(false, log.toString());
    }

    /**
     * Full one-button flow: pair → auto connect → grant
     * Connect discovery starts in parallel during pair when possible.
     */
    public static Result pairConnectAndGrant(Context context, String host, int pairPort, String code) {
        StringBuilder log = new StringBuilder();

        // Start connect discovery in background BEFORE pair finishes
        // so we don't miss early mDNS after dialog closes
        AtomicReference<List<String[]>> preFound = new AtomicReference<>(new ArrayList<>());
        Thread preScan = new Thread(() -> {
            try {
                List<String[]> found = collectConnectEndpoints(context, host, 45000);
                preFound.set(found);
            } catch (Throwable ignored) {
            }
        }, "pre-connect-scan");
        preScan.start();

        Result pair = pair(context, host, pairPort, code);
        log.append(pair.message).append("\n");
        if (!pair.ok) {
            preScan.interrupt();
            return new Result(false, log.toString());
        }

        // Prefer endpoints found during/after pair
        try {
            preScan.join(8000);
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
            long deadline = System.currentTimeMillis() + 20000;
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
                        "Customer app install नहीं।\nपहले Customer APK install करो।\n\npm: "
                                + (pmOut.isEmpty() ? "(empty)" : pmOut));
            }

            String cmd = "dpm set-device-owner " + TARGET_OWNER;
            String out = runShell(mgr, cmd);
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
                        "Fail: फोन पर Google/Mi account है।\nहटाएँ या factory reset।\n\n" + out);
            }
            if (lower.contains("not allowed") || lower.contains("provisioning")
                    || lower.contains("several users")) {
                return new Result(false,
                        "Fail: Owner set नहीं हो सकता इस state में।\n"
                                + "Factory reset (बिना account) → app install → loader।\n\n" + out);
            }

            if (out.isEmpty()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                verify = runShell(mgr, "dumpsys device_policy | grep -i owner");
                if (verify == null) verify = "";
                if (verify.toLowerCase().contains("com.harshit.emilocker.customer")
                        || verify.toLowerCase().contains("device owner")) {
                    return new Result(true, "Ownership OK ✅\nCustomer App → Sync QR।");
                }
                return new Result(false,
                        "set-device-owner empty।\nCustomer install? Account?\nVerify: "
                                + (verify.isEmpty() ? "(empty)" : verify));
            }

            return new Result(false, "set-device-owner:\n" + out
                    + "\n\nlist-owners: " + verify);
        } catch (Throwable t) {
            Log.e(TAG, "grant failed", t);
            String msg = t.getMessage() != null ? t.getMessage() : "";
            if (msg.toLowerCase().contains("stream closed")) {
                return new Result(false,
                        "Shell बंद। दोबारा try।\nCustomer install + बिना Google account।");
            }
            return new Result(false, "Grant error:\n" + t.getClass().getSimpleName()
                    + ": " + msg + "\n\n" + stack(t));
        }
    }
}
