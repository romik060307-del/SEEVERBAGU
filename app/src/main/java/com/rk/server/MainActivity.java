package com.rk.server;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(244, 247, 246);
    private static final int INK = Color.rgb(24, 43, 42);
    private static final int MUTED = Color.rgb(99, 116, 113);
    private static final int GREEN = Color.rgb(36, 122, 102);
    private static final int WHITE = Color.WHITE;

    private TextView statusText;
    private TextView addressText;
    private TextView detailText;
    private Button startButton;
    private Button stopButton;
    private final Handler handler = new Handler();
    private boolean foreground;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshStatus();
            if (foreground) handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (android.os.Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(Color.rgb(16, 45, 43));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setContentView(buildLayout());
        refreshStatus();
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(24));
        scroll.addView(root);

        TextView eyebrow = new TextView(this);
        eyebrow.setText("RKM  /  PERSONAL SERVER");
        eyebrow.setTextColor(GREEN);
        eyebrow.setTextSize(12);
        if (android.os.Build.VERSION.SDK_INT >= 21) eyebrow.setLetterSpacing(0.08f);
        root.addView(eyebrow, matchWrap());

        TextView title = new TextView(this);
        title.setText("RKM Server");
        title.setTextColor(INK);
        title.setTextSize(30);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(7);
        root.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("RKM Server ringan untuk web chat pribadi, gambar, status, dan sinyal panggilan suara. Nyalakan server ini, lalu biarkan RK Tunnel Lite tetap aktif agar teman dapat terhubung dari internet.");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(15);
        subtitle.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams subParams = matchWrap();
        subParams.topMargin = dp(8);
        root.addView(subtitle, subParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundColor(WHITE);
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.topMargin = dp(22);
        root.addView(card, cardParams);

        statusText = new TextView(this);
        statusText.setText("SERVER MATI");
        statusText.setTextColor(MUTED);
        statusText.setTextSize(12);
        statusText.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(statusText, matchWrap());

        addressText = new TextView(this);
        addressText.setText("Belum ada alamat server");
        addressText.setTextColor(INK);
        addressText.setTextSize(18);
        addressText.setTypeface(null, android.graphics.Typeface.BOLD);
        addressText.setTextIsSelectable(true);
        LinearLayout.LayoutParams addressParams = matchWrap();
        addressParams.topMargin = dp(9);
        card.addView(addressText, addressParams);

        detailText = new TextView(this);
        detailText.setText("Port 8080 · web dan data chat berjalan dari tablet ini");
        detailText.setTextColor(MUTED);
        detailText.setTextSize(12);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(8);
        card.addView(detailText, detailParams);

        startButton = makeButton("MULAI SERVER", GREEN, WHITE);
        LinearLayout.LayoutParams startParams = matchWrap();
        startParams.topMargin = dp(18);
        card.addView(startButton, startParams);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, ServerService.class);
                intent.setAction(ServerService.ACTION_START);
                startService(intent);
                Toast.makeText(MainActivity.this, "Server sedang dinyalakan…", Toast.LENGTH_SHORT).show();
                handler.postDelayed(new Runnable() { @Override public void run() { refreshStatus(); } }, 800L);
            }
        });

        stopButton = makeButton("MATIKAN SERVER", Color.rgb(232, 238, 236), INK);
        LinearLayout.LayoutParams stopParams = matchWrap();
        stopParams.topMargin = dp(9);
        card.addView(stopButton, stopParams);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, ServerService.class));
                refreshStatus();
                Toast.makeText(MainActivity.this, "Permintaan mematikan server dikirim", Toast.LENGTH_SHORT).show();
            }
        });

        Button openButton = makeButton("BUKA HALAMAN CHAT", Color.rgb(232, 238, 236), INK);
        LinearLayout.LayoutParams openParams = matchWrap();
        openParams.topMargin = dp(9);
        card.addView(openButton, openParams);
        openButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = getServerUrl();
                if (url == null) {
                    Toast.makeText(MainActivity.this, "Nyalakan server dan sambungkan tablet ke Wi-Fi terlebih dahulu", Toast.LENGTH_LONG).show();
                    return;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "Browser tidak ditemukan", Toast.LENGTH_LONG).show(); }
            }
        });

        Button copyButton = makeButton("SALIN ALAMAT SERVER", Color.rgb(232, 238, 236), INK);
        LinearLayout.LayoutParams copyParams = matchWrap();
        copyParams.topMargin = dp(9);
        card.addView(copyButton, copyParams);
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = getServerUrl();
                if (url == null) {
                    Toast.makeText(MainActivity.this, "Belum ada alamat IP. Pastikan Wi-Fi tersambung", Toast.LENGTH_LONG).show();
                    return;
                }
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Alamat RKM Server", url));
                Toast.makeText(MainActivity.this, "Alamat disalin: " + url, Toast.LENGTH_LONG).show();
            }
        });

        TextView stepsTitle = new TextView(this);
        stepsTitle.setText("CARA MENGUJI");
        stepsTitle.setTextColor(INK);
        stepsTitle.setTextSize(12);
        stepsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams stp = matchWrap();
        stp.topMargin = dp(22);
        root.addView(stepsTitle, stp);

        TextView steps = new TextView(this);
        steps.setText("1. Sambungkan tablet ke Wi-Fi.\n2. Tekan MULAI SERVER.\n3. Uji alamat lokal yang muncul.\n4. Aktifkan RK Tunnel Lite ke port 8080.\n5. Buka tautan publik untuk menggunakan RKM Chat dari HP lain.");
        steps.setTextColor(MUTED);
        steps.setTextSize(14);
        steps.setLineSpacing(dp(5), 1.0f);
        LinearLayout.LayoutParams stepsParams = matchWrap();
        stepsParams.topMargin = dp(10);
        root.addView(steps, stepsParams);

        TextView warning = new TextView(this);
        warning.setText("DATA: Akun, pesan, gambar, status dan sinyal panggilan disimpan di tablet ini. Untuk dipakai dari luar Wi-Fi, aktifkan RK Tunnel Lite menuju port 8080. Jaga keamanan tautan publik dan tablet server.");
        warning.setTextColor(Color.rgb(130, 76, 25));
        warning.setTextSize(12);
        warning.setPadding(dp(13), dp(13), dp(13), dp(13));
        warning.setBackgroundColor(Color.rgb(255, 242, 222));
        LinearLayout.LayoutParams warningParams = matchWrap();
        warningParams.topMargin = dp(20);
        root.addView(warning, warningParams);

        return scroll;
    }

    private Button makeButton(String text, int background, int foreground) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setTextColor(foreground);
        button.setBackgroundColor(background);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(7), dp(12), dp(7));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String getServerUrl() {
        String ip = getLocalIpAddress();
        if (TextUtils.isEmpty(ip) || "0.0.0.0".equals(ip)) return null;
        return "http://" + ip + ":" + ServerService.PORT + "/";
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.isWifiEnabled()) {
                int address = wifi.getConnectionInfo().getIpAddress();
                if (address != 0) {
                    return String.format(Locale.US, "%d.%d.%d.%d", (address & 0xff), (address >> 8 & 0xff), (address >> 16 & 0xff), (address >> 24 & 0xff));
                }
            }
        } catch (Exception ignored) { }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void refreshStatus() {
        boolean running = ServerService.isRunning;
        String url = getServerUrl();
        if (running) {
            statusText.setText("●  SERVER AKTIF");
            statusText.setTextColor(GREEN);
            addressText.setText(url == null ? "Server aktif · cek koneksi Wi-Fi" : url);
            detailText.setText("Port " + ServerService.PORT + " · Buka alamat ini dari HP pada Wi-Fi yang sama");
            startButton.setEnabled(false);
            startButton.setAlpha(0.55f);
            stopButton.setEnabled(true);
        } else {
            statusText.setText("○  SERVER BELUM AKTIF");
            statusText.setTextColor(MUTED);
            String error = ServerService.lastError;
            addressText.setText(url == null ? "IP belum terbaca · sambungkan Wi-Fi" : url);
            detailText.setText(!TextUtils.isEmpty(error) ? "Error terakhir: " + error : "Port " + ServerService.PORT + " · Server berhenti sampai kamu menyalakannya");
            startButton.setEnabled(true);
            startButton.setAlpha(1.0f);
            stopButton.setEnabled(false);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override protected void onPause() {
        foreground = false;
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }
}
