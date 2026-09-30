package com.harshit.emilocker.loader;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private EditText etIp, etPairPort, etCode, etConnectPort;
    private TextView tvStatus;
    private Button btnPair, btnConnect, btnGrant;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etIp = findViewById(R.id.etIp);
        etPairPort = findViewById(R.id.etPairPort);
        etCode = findViewById(R.id.etCode);
        etConnectPort = findViewById(R.id.etConnectPort);
        tvStatus = findViewById(R.id.tvStatus);
        btnPair = findViewById(R.id.btnPair);
        btnConnect = findViewById(R.id.btnConnect);
        btnGrant = findViewById(R.id.btnGrant);

        btnPair.setOnClickListener(v -> doPair());
        btnConnect.setOnClickListener(v -> doConnect());
        btnGrant.setOnClickListener(v -> doGrant());
    }

    private void setStatus(String msg) {
        mainHandler.post(() -> tvStatus.setText(msg));
    }

    private void doPair() {
        String ip = etIp.getText().toString().trim();
        String port = etPairPort.getText().toString().trim();
        String code = etCode.getText().toString().trim();

        if (ip.isEmpty() || port.isEmpty() || code.length() != 6) {
            Toast.makeText(this, "IP, Pairing Port और 6-digit Code भरें", Toast.LENGTH_SHORT).show();
            return;
        }

        btnPair.setEnabled(false);
        setStatus("Pairing...");

        executor.execute(() -> {
            AdbHelper.Result r = AdbHelper.pair(ip, port, code);
            setStatus("PAIR:\n" + r.output + "\n\nexit=" + r.exitCode);
            mainHandler.post(() -> btnPair.setEnabled(true));
        });
    }

    private void doConnect() {
        String ip = etIp.getText().toString().trim();
        String port = etConnectPort.getText().toString().trim();

        if (ip.isEmpty() || port.isEmpty()) {
            Toast.makeText(this, "IP और Connect Port भरें", Toast.LENGTH_SHORT).show();
            return;
        }

        btnConnect.setEnabled(false);
        setStatus("Connecting...");

        executor.execute(() -> {
            AdbHelper.Result r = AdbHelper.connect(ip, port);
            AdbHelper.Result devices = AdbHelper.devices();
            setStatus("CONNECT:\n" + r.output + "\n\nDEVICES:\n" + devices.output);
            mainHandler.post(() -> btnConnect.setEnabled(true));
        });
    }

    private void doGrant() {
        btnGrant.setEnabled(false);
        setStatus("Setting Device Owner...\n\nनोट: फोन पर Google/Mi अकाउंट न हो तो ही success मिलेगा।");

        executor.execute(() -> {
            AdbHelper.Result r = AdbHelper.setDeviceOwner();
            String msg = "GRANT OWNERSHIP:\n" + r.output + "\n\nexit=" + r.exitCode;
            if (r.ok() || (r.output != null && r.output.toLowerCase().contains("success"))) {
                msg += "\n\n✅ Success! अब Customer App खोलें और Dealer Sync QR स्कैन करें।";
            } else {
                msg += "\n\n❌ Fail हो सकता है अगर:\n"
                        + "• adb इस फोन पर उपलब्ध नहीं\n"
                        + "• कस्टमर फोन पर अकाउंट लगा है\n"
                        + "• पहले से कोई Device Owner है\n"
                        + "• Customer App इंस्टॉल नहीं";
            }
            setStatus(msg);
            mainHandler.post(() -> btnGrant.setEnabled(true));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
