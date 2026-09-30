package com.harshit.emilocker.loader;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

public class AdbHelper {

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

    public static Result pair(Context context, String host, int pairPort, String code) {
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            boolean success = mgr.pair(host, pairPort, code);
            if (success) {
                return new Result(true, "Pairing successful ✅\nअब Connect Port डालकर CONNECT दबाएँ।");
            }
            return new Result(false, "Pairing failed ❌\nCode/Port चेक करें। Pairing dialog खुला होना चाहिए।");
        } catch (Exception e) {
            return new Result(false, "Pair error:\n" + e.getMessage());
        }
    }

    public static Result connect(Context context, String host, int connectPort) {
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            mgr.setHostAddress(host);
            boolean connected = mgr.connect(connectPort);
            if (connected) {
                return new Result(true, "Connected ✅\nअब GRANT OWNERSHIP दबाएँ।");
            }
            return new Result(false, "Connect failed ❌\nConnect Port गलत हो सकता है।");
        } catch (Exception e) {
            return new Result(false, "Connect error:\n" + e.getMessage());
        }
    }

    public static Result grantOwnership(Context context) {
        try {
            AbsAdbConnectionManager mgr = AdbConnectionManager.getInstance(context);
            // shell:dpm set-device-owner ...
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
            try { stream.close(); } catch (Exception ignored) {}

            String out = bos.toString(StandardCharsets.UTF_8.name()).trim();
            String lower = out.toLowerCase();

            if (lower.contains("success")
                    || lower.contains("device owner set")
                    || out.isEmpty()) {
                // कुछ devices खाली output + success देते हैं
                boolean likelyOk = out.isEmpty() || lower.contains("success") || lower.contains("device owner");
                if (likelyOk) {
                    return new Result(true,
                            "Ownership command sent ✅\n\nOutput:\n" + (out.isEmpty() ? "(empty)" : out)
                                    + "\n\nअब Customer App खोलें और Dealer Sync QR स्कैन करें।");
                }
            }

            if (lower.contains("already the device owner")) {
                return new Result(true, "पहले से Device Owner है ✅\n" + out);
            }
            if (lower.contains("account")) {
                return new Result(false,
                        "Fail: फोन पर अकाउंट लगा है।\nSettings → Accounts हटाएँ या factory reset करें।\n\n" + out);
            }
            if (lower.contains("not allowed") || lower.contains("provisioning")) {
                return new Result(false,
                        "Fail: इस स्टेट में Device Owner set नहीं हो सकता।\nFactory reset के बाद (बिना अकाउंट) ट्राई करें।\n\n" + out);
            }

            return new Result(false, "set-device-owner result:\n" + (out.isEmpty() ? "(no output)" : out));
        } catch (Exception e) {
            return new Result(false, "Grant error:\n" + e.getMessage()
                    + "\n\nपहले PAIR और CONNECT सफल होना चाहिए।");
        }
    }
}
