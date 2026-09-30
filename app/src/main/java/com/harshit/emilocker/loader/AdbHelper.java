package com.harshit.emilocker.loader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Phase 1: ADB commands via shell.
 * Production में adb binary APK के अंदर (jniLibs/arm64-v8a) रखना बेहतर है।
 * अभी Runtime exec से try — डीलर फोन पर adb PATH में हो (Termux) तो काम करेगा।
 */
public class AdbHelper {

    public static class Result {
        public final int exitCode;
        public final String output;

        public Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    public static Result run(String... cmd) {
        StringBuilder out = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append("\n");
            }
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new Result(-1, out + "\nTimeout");
            }
            return new Result(p.exitValue(), out.toString().trim());
        } catch (Exception e) {
            return new Result(-1, "Error: " + e.getMessage());
        }
    }

    public static Result pair(String ip, String port, String code) {
        // adb pair IP:PORT CODE
        return run("adb", "pair", ip + ":" + port, code);
    }

    public static Result connect(String ip, String port) {
        return run("adb", "connect", ip + ":" + port);
    }

    public static Result setDeviceOwner() {
        return run(
                "adb", "shell", "dpm", "set-device-owner",
                "com.harshit.emilocker.customer/.MyAdminReceiver"
        );
    }

    public static Result devices() {
        return run("adb", "devices");
    }
}
