package com.harshit.emilocker.loader;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reference APK जैसा flow:
 * Search → mDNS से IP + Pairing port auto
 * सिर्फ 6-digit code → PAIR & TRANSFER (auto connect + ownership)
 */
public class MainActivity extends AppCompatActivity {

    private EditText etIp, etPairPort, etCode;
    private TextView tvStatus;
    private Button btnSearch, btnPairTransfer;

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
        tvStatus = findViewById(R.id.tvStatus);
        btnSearch = findViewById(R.id.btnSearch);
        btnPairTransfer = findViewById(R.id.btnPairTransfer);

        discovery = new AdbDiscovery(this);

        btnSearch.setOnClickListener(v -> startSearch());
        btnPairTransfer.setOnClickListener(v -> runPairAndTransfer());

        // 6 digit भरते ही optional auto-start (user can still press button)
        etCode.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Auto only when IP+port already filled from search
                if (s != null && s.length() == 6
                        && discoveredHost != null && discoveredPairPort > 0
                        && !busy) {
                    // Don't force auto if user still typing — optional toast
                }
            }
        });

        setStatus("1) दोनों फोन same Wi‑Fi\n"
                + "2) Customer: Developer options → Wireless debugging ON\n"
                + "3) Pair device with pairing code (dialog खुला रखो)\n"
                + "4) यहाँ SEARCH दबाओ → IP/Port auto भरेंगे\n"
                + "5) 6-digit code डालो → PAIR & TRANSFER\n"
                + "   (Connect + Ownership automatic)\n\n"
                + "नोट: Customer फोन पर Google/Mi account न हो।");
    }

    private void setStatus(String s) {
        main.post(() -> tvStatus.setText(s));
    }

    private void setBusy(boolean b) {
        busy = b;
        main.post(() -> {
            btnSearch.setEnabled(!b);
            btnPairTransfer.setEnabled(!b);
        });
    }

    private void startSearch() {
        if (busy) return;
        discoveredHost = null;
        discoveredPairPort = -1;
        setStatus("🔍 Searching pairing service...\n"
                + "Customer पर 'Pair device with pairing code' खुला होना चाहिए।");

        discovery.stop();
        discovery.start(AdbDiscovery.TYPE_PAIRING, new AdbDiscovery.Listener() {
            @Override
            public void onFound(String host, int port, String serviceType) {
                discoveredHost = host;
                discoveredPairPort = port;
                main.post(() -> {
                    etIp.setText(host);
                    etPairPort.setText(String.valueOf(port));
                    setStatus("✅ Found!\nIP: " + host + "\nPairing port: " + port
                            + "\n\nअब 6-digit pairing code डालकर\nPAIR & TRANSFER दबाएँ।");
                    Toast.makeText(MainActivity.this, "Device found: " + host, Toast.LENGTH_SHORT).show();
                });
                // Keep discovery running briefly for updates, or stop
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

        // Timeout message
        main.postDelayed(() -> {
            if (discoveredHost == null && discovery.isRunning()) {
                setStatus("⏳ अभी तक नहीं मिला...\n"
                        + "• Same Wi‑Fi?\n"
                        + "• Pairing code dialog खुला है?\n"
                        + "• Router mDNS/multicast block तो नहीं?\n"
                        + "Search फिर से try करो। IP manual भी डाल सकते हो।");
            }
        }, 15000);
    }

    private void runPairAndTransfer() {
        String ip = etIp.getText().toString().trim();
        String portStr = etPairPort.getText().toString().trim();
        String code = etCode.getText().toString().trim();

        if (ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "पहले SEARCH करो या IP + Pairing Port भरें", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code.length() != 6) {
            Toast.makeText(this, "6-digit pairing code डालें", Toast.LENGTH_SHORT).show();
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
        setStatus("Pairing + Connect + Ownership...\nकृपया wait करें।");

        executor.execute(() -> {
            AdbHelper.Result r = AdbHelper.pairConnectAndGrant(this, ip, pairPort, code);
            setStatus(r.message);
            setBusy(false);
            main.post(() -> Toast.makeText(this,
                    r.ok ? "Done ✅" : "Failed — status देखो",
                    Toast.LENGTH_LONG).show());
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discovery != null) discovery.stop();
        executor.shutdownNow();
    }
}
