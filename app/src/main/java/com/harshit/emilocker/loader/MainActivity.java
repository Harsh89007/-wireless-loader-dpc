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
    private final Handler main = new Handler(Looper.getMainLooper());

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

        btnPair.setOnClickListener(v -> runPair());
        btnConnect.setOnClickListener(v -> runConnect());
        btnGrant.setOnClickListener(v -> runGrant());

        setStatus("1) कस्टमर फोन: Customer App इंस्टॉल\n"
                + "2) Wireless debugging → Pair device with pairing code\n"
                + "3) IP + Pairing Port + 6-digit Code डालो → PAIR\n"
                + "4) Connect Port डालो → CONNECT\n"
                + "5) GRANT OWNERSHIP\n\n"
                + "नोट: कस्टमर फोन पर Google/Mi अकाउंट न हो।");
    }

    private void setStatus(String s) {
        main.post(() -> tvStatus.setText(s));
    }

    private void setBusy(boolean busy) {
        main.post(() -> {
            btnPair.setEnabled(!busy);
            btnConnect.setEnabled(!busy);
            btnGrant.setEnabled(!busy);
        });
    }

    private void runPair() {
        String ip = etIp.getText().toString().trim();
        String portStr = etPairPort.getText().toString().trim();
        String code = etCode.getText().toString().trim();

        if (ip.isEmpty() || portStr.isEmpty() || code.length() != 6) {
            Toast.makeText(this, "IP, Pairing Port, 6-digit Code भरें", Toast.LENGTH_SHORT).show();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (Exception e) {
            Toast.makeText(this, "Invalid pairing port", Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        setStatus("Pairing...");

        executor.execute(() -> {
            AdbHelper.Result r = AdbHelper.pair(this, ip, port, code);
            setStatus(r.message);
            setBusy(false);
        });
    }

    private void runConnect() {
        String ip = etIp.getText().toString().trim();
        String portStr = etConnectPort.getText().toString().trim();

        if (ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "IP और Connect Port भरें", Toast.LENGTH_SHORT).show();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (Exception e) {
            Toast.makeText(this, "Invalid connect port", Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        setStatus("Connecting...");

        executor.execute(() -> {
            AdbHelper.Result r = AdbHelper.connect(this, ip, port);
            setStatus(r.message);
            setBusy(false);
        });
    }

    private void runGrant() {
        setBusy(true);
        setStatus("Setting Device Owner...\ncom.harshit.emilocker.customer/.MyAdminReceiver");

        executor.execute(() -> {
            AdbHelper.Result r = AdbHelper.grantOwnership(this);
            setStatus(r.message);
            setBusy(false);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
