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
        return s.length() > 800 ? s.substring(0, 800) + "…" : s;
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

    private static final List<String> lastDiscoverHosts = new ArrayList<>();
    private static final List<Integer> lastDiscoverPorts = new ArrayList<>();

    public static void discoverConnectEndpoints(Context context, String preferredHost, long timeoutMs) {
        lastDiscoverHosts.clear();
        lastDiscoverPorts.clear();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        AdbDiscovery discovery = new AdbDiscovery(context);

        discovery.start(AdbDiscovery.TYPE_CONNECT, new AdbDiscovery.Listener() {
            @Override
            public void onFound(String host, int port, String serviceType) {
                if (finished.get()) return;
                synchronized (lastDiscoverHosts) {
                    boolean exists = false;
                    for (int i = 0; i < lastDiscoverHosts.size(); i++) {
                        if (lastDiscoverHosts.get(i).equals(host)
                                && lastDiscoverPorts.get(i) == port) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        lastDiscoverHosts.add(host);
                        lastDiscoverPorts.add(port);
                        Log.d(TAG, "Connect service: " + host + ":" + port);
                    }
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
    }

    public static Result tryConnectAfterPair(Context context, String pairHost) {
        StringBuilder log = new StringBuilder();
        log.append("Connect port खोज रहे हैं...\n");

        try {
            Thread.sleep(2500);
        } catch (InterruptedException ignored) {
        }

        discoverConnectEndpoints(context, pairHost, 18000);

        for (int i = 0; i < lastDiscoverHosts.size(); i++) {
            String h = lastDiscoverHosts.get(i);
            int p = lastDiscoverPorts.get(i);
            if (pairHost != null && pairHost.equals(h)) {
                log.append("Trying ").append(h).append(":").append(p).append("\n");
                Result c = connect(context, h, p);
                if (c.ok) return new Result(true, log + c.message);
                log.append(c.message).append("\n");
            }
        }

        for (int i = 0; i < lastDiscoverHosts.size(); i++) {
            String h = lastDiscoverHosts.get(i);
            int p = lastDiscoverPorts.get(i);
            if (pairHost != null && pairHost.equals(h)) continue;
            log.append("Trying ").append(h).append(":").append(p).append("\n");
            Result c = connect(context, h, p);
            if (c.ok) return new Result(true, log + c.message);
            log.append(c.message).append("\n");
        }

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
            if (c.ok) return new Result(true, log + c.message);
        }

        return new Result(false,
                log + "Connect port auto नहीं मिला।\n"
                        + "Wireless debugging मुख्य स्क्रीन से PORT डालकर\n"
                        + "CONNECT AND GRANT दबाएँ।",
                true);
    }

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

    public static Result connectAndGrant(Context context, String host, int connectPort) {
        StringBuilder log = new StringBuilder();
        Result conn = connect(context, host, connectPort);
        log.append(conn.message).append("\n");
        if (!conn.ok) return new Result(false, log.toString());

        Result grant = grantOwnership(context);
        log.append(grant.message);
        return new Result(grant.ok, log.toString());
    }

    /**
     * Shell command चलाओ। Stream closed = command खत्म (अक्सर normal)।
     */
    private static String runShell(AbsAdbConnectionManager mgr, String command) throws Exception {
        // Method 1: shell:cmd in one openStream
        try {
            AdbStream stream = mgr.openStream("shell:" + command);
            String out = readStreamFully(stream);
            if (out != null) return out;
        } catch (Exception e) {
            Log.w(TAG, "shell:cmd failed: " + e.getMessage());
        }

        // Method 2: interactive shell + write command
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
                    // Stream closed / connection reset = remote finished
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
                return new Result(false, "ADB connected नहीं है। पहले Connect सफल करें।");
            }

            // Customer app install check
            String pmOut = runShell(mgr, "pm path com.harshit.emilocker.customer");
            if (pmOut == null) pmOut = "";
            if (!pmOut.contains("package:") && !pmOut.contains("com.harshit")) {
                return new Result(false,
                        "Customer app install नहीं है इस फोन पर।\n"
                                + "पहले Customer APK install करो, फिर ownership।\n\n"
                                + "pm: " + (pmOut.isEmpty() ? "(empty)" : pmOut));
            }

            String cmd = "dpm set-device-owner " + TARGET_OWNER;
            String out = runShell(mgr, cmd);
            if (out == null) out = "";
            String lower = out.toLowerCase();

            // Verify
            String verify = runShell(mgr, "dpm list-owners");
            if (verify == null) verify = "";
            boolean ownerSet = verify.toLowerCase().contains("com.harshit.emilocker.customer")
                    || lower.contains("success")
                    || lower.contains("already the device owner");

            if (ownerSet) {
                return new Result(true,
                        "Ownership OK ✅\n"
                                + (out.isEmpty() ? "" : "out: " + out + "\n")
                                + "owners: " + (verify.isEmpty() ? "(check app)" : verify)
                                + "\n\nCustomer App खोलें → Sync QR स्कैन करें।");
            }

            if (lower.contains("already the device owner")) {
                return new Result(true, "पहले से Device Owner ✅\n" + out);
            }
            if (lower.contains("account") || verify.toLowerCase().contains("account")) {
                return new Result(false,
                        "Fail: फोन पर Google/Mi अकाउंट है।\nहटाएँ या factory reset।\n\n" + out);
            }
            if (lower.contains("not allowed") || lower.contains("provisioning")
                    || lower.contains("several users") || lower.contains("user")) {
                return new Result(false,
                        "Fail: इस स्टेट में Owner set नहीं हो सकता।\n"
                                + "Factory reset (बिना अकाउंट) → app install → फिर loader।\n\n" + out);
            }

            // Stream closed with empty output — often still worked; check owners again
            if (out.isEmpty()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                verify = runShell(mgr, "dumpsys device_policy | grep -i owner");
                if (verify == null) verify = "";
                if (verify.toLowerCase().contains("com.harshit.emilocker.customer")
                        || verify.toLowerCase().contains("device owner")) {
                    return new Result(true, "Ownership OK ✅ (verified)\n" + verify);
                }
                return new Result(false,
                        "set-device-owner कोई output नहीं दिया।\n"
                                + "Customer app install है? अकाउंट तो नहीं?\n"
                                + "Verify: " + (verify.isEmpty() ? "(empty)" : verify));
            }

            return new Result(false, "set-device-owner:\n" + out
                    + "\n\nlist-owners: " + verify);
        } catch (Throwable t) {
            Log.e(TAG, "grant failed", t);
            String msg = t.getMessage() != null ? t.getMessage() : "";
            // Stream closed alone is not always failure — rare path
            if (msg.toLowerCase().contains("stream closed")) {
                return new Result(false,
                        "Shell stream बंद हो गया।\n"
                                + "दोबारा PAIR AND TRANSFER try करो।\n"
                                + "Customer app install + बिना Google account होना ज़रूरी।\n\n"
                                + stack(t));
            }
            return new Result(false, "Grant error:\n" + t.getClass().getSimpleName()
                    + ": " + msg + "\n\n" + stack(t));
        }
    }
}
