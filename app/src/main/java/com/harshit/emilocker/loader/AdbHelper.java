package com.harshit.emilocker.loader;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

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
                return new Result(true, "Pairing successful ✅\nअब Connect Port डालकर CONNECT दबाएँ।");
            }
            return new Result(false, "Pairing failed ❌\nCode/Port चेक करें। Pairing dialog खुला होना चाहिए।");
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
                return new Result(true, "Connected ✅\nअब GRANT OWNERSHIP दबाएँ।");
            }
            return new Result(false, "Connect failed ❌\nConnect Port गलत हो सकता है।");
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
            return new Result(false, "Connect error:\n" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "\n\n" + stack(t));
        }
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
            try { stream.close(); } catch (Exception ignored) {}

            String out = bos.toString(StandardCharsets.UTF_8.name()).trim();
            String lower = out.toLowerCase();

            if (lower.contains("success") || lower.contains("device owner") || out.isEmpty()) {
                return new Result(true,
                        "Ownership command sent ✅\n\nOutput:\n"
                                + (out.isEmpty() ? "(empty)" : out)
                                + "\n\nअब Customer App खोलें और Dealer Sync QR स्कैन करें।");
            }
            if (lower.contains("already the device owner")) {
                return new Result(true, "पहले से Device Owner है ✅\n" + out);
            }
            if (lower.contains("account")) {
                return new Result(false,
                        "Fail: फोन पर अकाउंट लगा है।\nAccounts हटाएँ या factory reset।\n\n" + out);
            }
            if (lower.contains("not allowed") || lower.contains("provisioning")) {
                return new Result(false,
                        "Fail: Device Owner इस स्टेट में set नहीं हो सकता।\nFactory reset (बिना अकाउंट) ट्राई करें।\n\n" + out);
            }
            return new Result(false, "set-device-owner result:\n" + (out.isEmpty() ? "(no output)" : out));
        } catch (Throwable t) {
            Log.e(TAG, "grant failed", t);
            return new Result(false, "Grant error:\n" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "\n\n" + stack(t)
                    + "\n\nपहले PAIR और CONNECT सफल होना चाहिए।");
        }
    }
}
