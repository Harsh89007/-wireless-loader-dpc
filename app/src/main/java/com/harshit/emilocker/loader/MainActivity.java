package com.harshit.emilocker.loader;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search → pair code → PAIR & TRANSFER
 * अगर connect port auto न मिले → manual Connect Port + CONNECT & GRANT
 */
public class MainActivity extends AppCompatActivity {

    private EditText etIp, etPairPort, etCode, etConnectPort;
    private TextView tvStatus, tvConnectHint;
    private Button btnSearch, btnPairTransfer, btnConnectGrant;
    private View layoutManualConnect;

    private AdbDiscovery discovery;
    private String discoveredHost;
    private int discoveredPairPort = -1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etIp = findViewById(R.id.etIp);
        etPairPort = findViewById(R.id.etPairPort);
        etCode = findViewById(R.id.etCode);
        etConnectPort = findViewById(R.id.etConnectPort);
        tvStatus = findViewById(R.id.tvStatus);
        tvConnectHint = findViewById(R.id.tvConnectHint);
        btnSearch = findViewById(R.id.btnSearch);
        btnPairTransfer = findViewById(R.id.btnPairTransfer);
        btnConnectGrant = findViewById(R.id.btnConnectGrant);
        layoutManualConnect = findViewById(R.id.layoutManualConnect);

        if (layoutManualConnect != null) {
            layoutManualConnect.setVisibility(View.GONE);
        }

        discovery = new AdbDiscovery(this);

        btnSearch.setOnClickListener(v -> startSearch());
        btnPairTransfer.setOnClickListener(v -> runPairAndTransfer());
        if (btnConnectGrant != null) {
            btnConnectGrant.setOnClickListener(v -> runManualConnectAndGrant());
        }

        setStatus("1) Same Wi‑Fi, mobile data OFF\n"
                + "2) Customer: Wireless debugging → Pair with pairing code\n"
                + "3) SEARCH → IP/Port auto\n"
                + "4) 6-digit code → PAIR & TRANSFER\n"
                + "5) अगर Connect port न मिले: Wireless debugging मुख्य स्क्रीन पर\n"
                + "   IP:PORT में से PORT डालकर CONNECT & GRANT");
    }

    private void setStatus(String s) {
        main.post(() -> tvStatus.setText(s));
    }

    private void setBusy(boolean b) {
        busy = b;
        main.post(() -> {
            btnSearch.setEnabled(!b);
            btnPairTransfer.setEnabled(!b);
            if (btnConnectGrant != null) btnConnectGrant.setEnabled(!b);
        });
    }

    private void showManualConnect(boolean show) {
        main.post(() -> {
            if (layoutManualConnect != null) {
                layoutManualConnect.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            if (tvConnectHint != null && show) {
                tvConnectHint.setText(
                        "Customer → Settings → Developer options → Wireless debugging\n"
                                + "(pairing dialog नहीं — मुख्य स्क्रीन)\n"
                                + "वहाँ IP:PORT दिखेगा जैसे 10.47.123.183:45677\n"
                                + "सिर्फ PORT (45677) नीचे डालो → CONNECT & GRANT");
            }
        });
    }

    private void startSearch() {
        if (busy) return;
        discoveredHost = null;
        discoveredPairPort = -1;
        showManualConnect(false);
        setStatus("🔍 Searching...\nPairing code dialog customer पर खुला रखो।");

        discovery.stop();
        discovery.start(AdbDiscovery.TYPE_PAIRING, new AdbDiscovery.Listener() {
            @Override
            public void onFound(String host, int port, String serviceType) {
                discoveredHost = host;
                discoveredPairPort = port;
                main.post(() -> {
                    etIp.setText(host);
                    etPairPort.setText(String.valueOf(port));
                    setStatus("✅ Found: " + host + " : " + port
                            + "\n\n6-digit code डालो → PAIR & TRANSFER");
                    Toast.makeText(MainActivity.this, "Found " + host, Toast.LENGTH_SHORT).show();
                });
                discovery.stop();
            }

            @Override
            public void onLost(String serviceType) {
            }

            @Override
            public void onError(String message) {
                setStatus("Search error: " + message);
            }
        });

        main.postDelayed(() -> {
            if (discoveredHost == null && discovery.isRunning()) {
                setStatus("⏳ नहीं मिला। Same Wi‑Fi? Pairing dialog खुला?\nSearch फिर से / IP manual डालो।");
            }
        }, 15000);
    }

    private void runPairAndTransfer() {
        String ip = etIp.getText().toString().trim();
        String portStr = etPairPort.getText().toString().trim();
        String code = etCode.getText().toString().trim();

        if (ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "SEARCH करो या IP + Pairing Port भरें", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code.length() != 6) {
            Toast.makeText(this, "6-digit pairing code", Toast.LENGTH_SHORT).show();
            return;
        }

        int pairPort;
        try {
            pairPort = Integer.parseInt(portStr);
        } catch (Exception e) {
            Toast.makeText(this, "Invalid pairing port", Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        setStatus("Pairing + Connect + Ownership...\nWait...");

        executor.execute(() -> {
            AdbHelper.Result r = AdbHelper.pairConnectAndGrant(this, ip, pairPort, code);
            setStatus(r.message);
            setBusy(false);
            if (r.needManualConnectPort) {
                showManualConnect(true);
            }
            main.post(() -> Toast.makeText(this,
                    r.ok ? "Done ✅" : (r.needManualConnectPort
                            ? "Connect port डालो (नीचे)"
                            : "Failed"),
                    Toast.LENGTH_LONG).show());
        });
    }

    private void runManualConnectAndGrant() {
        String ip = etIp.getText().toString().trim();
        String portStr = etConnectPort != null
                ? etConnectPort.getText().toString().trim() : "";

        if (ip.isEmpty()) {
            Toast.makeText(this, "IP भरें", Toast.LENGTH_SHORT).show();
            return;
        }
        if (portStr.isEmpty()) {
            Toast.makeText(this, "Connect Port डालें (Wireless debugging मुख्य स्क्रीन)", Toast.LENGTH_LONG).show();
            return;
        }

        int connectPort;
        try {
            connectPort = Integer.parseInt(portStr);
        } catch (Exception e) {
            Toast.makeText(this, "Invalid connect port", Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        setStatus("Connecting port " + connectPort + " + Ownership...");

        executor.execute(() -> {
            AdbHelper.Result r = AdbHelper.connectAndGrant(this, ip, connectPort);
            setStatus(r.message);
            setBusy(false);
            main.post(() -> Toast.makeText(this,
                    r.ok ? "Done ✅" : "Failed", Toast.LENGTH_LONG).show());
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discovery != null) discovery.stop();
        executor.shutdownNow();
    }
}
