package org.elderguard.testing.redirsim;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * TEST ONLY. Buttons = what a user taps (these send click events). The "run" extra starts a scenario WITHOUT any tap
 * (adb: am start -n org.elderguard.testing.redirsim/.Main --es run auto_shop), like an ad acting on its own.
 */
public class Main extends Activity {
    static final String SHOP = "org.elderguard.testing.shop";
    final Handler h = new Handler(Looper.getMainLooper());
    static boolean loop = false;
    SensorManager sm;
    SensorEventListener shake;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, 200, 0, 0); // below the status bar (edge-to-edge)
        TextView t = new TextView(this); t.setText("測試用小遊戲（守門員跳轉測試）"); t.setTextSize(22); l.addView(t);
        add(l, "使用者點按：開 YouTube", () -> openPkg("com.google.android.youtube"));
        add(l, "使用者點按：開網頁", () -> open("https://example.com/"));
        add(l, "使用者點按：開購物 App", () -> openPkg(SHOP));
        add(l, "使用者點按：開 Play 商店", () -> open("market://details?id=org.elderguard"));
        add(l, "8 秒後自動跳購物 App", () -> h.postDelayed(() -> openPkg(SHOP), 8000));
        add(l, "打開全螢幕廣告（按 ✕ 會跳購物）", () -> startActivity(new Intent(this, com.applovin.adview.AppLovinFullscreenActivity.class)));
        add(l, "搖一搖廣告（搖手機就跳購物）", this::armShake);
        add(l, "重複跳轉（回來就再跳）", () -> { loop = true; h.postDelayed(() -> openPkg(SHOP), 1000); });
        add(l, "停止重複跳轉", () -> loop = false);
        add(l, "分享", () -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "test"), "分享")));
        add(l, "登入（開瀏覽器）", () -> open("https://accounts.google.com/"));
        ScrollView sv = new ScrollView(this); sv.addView(l); setContentView(sv);
        run(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); run(i); }

    void run(Intent i) {
        String r = i.getStringExtra("run");
        if (r == null) return;
        switch (r) {
            case "auto_shop": h.postDelayed(() -> openPkg(SHOP), 3000); break;
            case "auto_store": h.postDelayed(() -> open("market://details?id=org.elderguard"), 3000); break;
            case "ad_auto": startActivity(new Intent(this, com.applovin.adview.AppLovinFullscreenActivity.class).putExtra("auto", true)); break;
            case "loop": loop = true; h.postDelayed(() -> openPkg(SHOP), 1500); break;
            case "shake": armShake(); break;
            case "dnsbench": dnsBench(i.getIntExtra("n", 40)); break;
            case "resolve": resolve(i.getStringExtra("names")); break;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (loop) h.postDelayed(() -> { if (loop) openPkg(SHOP); }, 1200);
    }

    /** Resolves n never-seen names (no cache anywhere on the phone) and logs the median / p90 lookup time. */
    void dnsBench(int n0) {
        final int nn = n0;
        new Thread(() -> {
            int n = nn;
            long[] ms = new long[n];
            int ok = 0;
            String[] names = ("google.com,youtube.com,facebook.com,wikipedia.org,amazon.com,apple.com,microsoft.com,github.com,"
                + "cloudflare.com,mozilla.org,bbc.co.uk,nytimes.com,cnn.com,line.me,yahoo.com,bing.com,reddit.com,twitter.com,"
                + "instagram.com,linkedin.com,netflix.com,spotify.com,adobe.com,dropbox.com,paypal.com,ebay.com,booking.com,"
                + "gov.tw,edu.tw,shopee.tw,momoshop.com.tw,pchome.com.tw,udn.com,ettoday.net,ltn.com.tw,chinatimes.com,"
                + "bilibili.com,weibo.com,qq.com,baidu.com").split(",");
            n = Math.min(n, names.length);
            for (int k = 0; k < n; k++) {
                long t0 = System.nanoTime();
                try { java.net.InetAddress.getAllByName(names[k]); ok++; } catch (Exception e) { }
                ms[k] = (System.nanoTime() - t0) / 1_000_000;
            }
            ms = java.util.Arrays.copyOf(ms, n);
            java.util.Arrays.sort(ms);
            android.util.Log.i("EG-BENCH", "dns ok=" + ok + " n=" + n + " median=" + ms[n / 2] + "ms p90=" + ms[n * 9 / 10] + "ms max=" + ms[n - 1] + "ms");
        }).start();
    }

    void resolve(String names) {
        new Thread(() -> {
            for (String nm : names.split(",")) {
                String r;
                try { r = java.util.Arrays.toString(java.net.InetAddress.getAllByName(nm)); } catch (Exception e) { r = "ERR " + e.getClass().getSimpleName(); }
                android.util.Log.i("EG-BENCH", "resolve " + nm + " -> " + r);
            }
        }).start();
    }

    void armShake() {
        sm = getSystemService(SensorManager.class);
        shake = new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) {
                double g = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
                if (Math.abs(g - 9.81) > 6) { sm.unregisterListener(this); openPkg(SHOP); }
            }
            public void onAccuracyChanged(Sensor s, int a) {}
        };
        sm.registerListener(shake, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME);
    }

    void add(LinearLayout l, String text, Runnable r) {
        Button b = new Button(this); b.setText(text); b.setTextSize(18); b.setOnClickListener(v -> r.run()); l.addView(b);
    }

    void openPkg(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    void open(String uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
    }
}
