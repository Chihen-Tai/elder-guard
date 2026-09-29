package org.elderguard.testing.jumper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * TEST ONLY. Jumps to another app the way ads do (shake, a tap on a game screen that reports no click, an ad's X,
 * a timer, a loop) and the way users do (a normal button). Everything stops when this screen is left.
 * adb: am start -n org.elderguard.testing.jumper/.Main --es run auto|loop|ad|ad_auto --es to <target id>
 */
public class Main extends Activity implements SensorEventListener {
    /** A place to jump to. Only installed ones are offered. */
    static final class Target {
        final String id, label, pkg, uri;
        Target(String id, String label, String pkg, String uri) { this.id = id; this.label = label; this.pkg = pkg; this.uri = uri; }
    }

    static final Target[] ALL = {
        new Target("shopee", "蝦皮購物（購物 App）", "com.shopee.tw", null),
        new Target("store", "Play 商店（應用程式商店）", "com.android.vending", "market://details?id=com.shopee.tw"),
        new Target("web", "Chrome 網頁（瀏覽器）", "com.android.chrome", "https://example.com/"),
        new Target("youtube", "YouTube（影片 App）", "com.google.android.youtube", null),
        new Target("testshop", "測試用購物 App", "org.elderguard.testing.shop", null),
    };

    static boolean loop = false;
    final Handler h = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    TextView status;
    SensorManager sm;
    boolean shakeOn = false;
    float shakeThreshold = 8f;
    long lastJump = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("jumper", MODE_PRIVATE);
        sm = getSystemService(SensorManager.class);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32, 160, 32, 64);

        title(l, "跳轉測試器");
        note(l, "測試守門員「防止亂跳 App」用。只有這個畫面打開時才會動作。");
        status = note(l, "（還沒跳轉）");

        title(l, "1. 跳到哪裡");
        RadioGroup g = new RadioGroup(this);
        List<Target> installed = installedTargets();
        for (Target t : installed) {
            RadioButton r = new RadioButton(this);
            r.setText(t.label); r.setTextSize(20); r.setTag(t.id); r.setId(View.generateViewId());
            g.addView(r);
            if (t.id.equals(target().id)) r.setChecked(true);
        }
        g.setOnCheckedChangeListener((grp, id) -> prefs.edit().putString("to", (String) grp.findViewById(id).getTag()).apply());
        l.addView(g);
        if (installed.isEmpty()) note(l, "手機上找不到可以跳去的 App。");

        title(l, "2. 像廣告的行為（守門員應該擋或先問）");
        Switch shake = new Switch(this);
        shake.setText("搖一下就跳"); shake.setTextSize(20);
        shake.setOnCheckedChangeListener((v, on) -> { shakeOn = on; updateSensor(); });
        l.addView(shake);
        RadioGroup force = new RadioGroup(this);
        force.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {"輕", "中", "重"}; float[] th = {4f, 8f, 14f};
        for (int i = 0; i < 3; i++) {
            RadioButton r = new RadioButton(this); r.setText("搖晃強度：" + names[i] + "  "); r.setTextSize(16);
            final float v = th[i]; r.setId(View.generateViewId());
            r.setOnClickListener(x -> shakeThreshold = v);
            force.addView(r); if (i == 1) r.setChecked(true);
        }
        l.addView(force);
        note(l, "下面這塊是「遊戲畫面」：點一下就跳，但它刻意不回報點按（像 Unity 遊戲），守門員看不到您點了。");
        GameArea game = new GameArea(this);
        l.addView(game, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 360));
        button(l, "打開全螢幕假廣告（按 ✕ 會跳）", () -> startActivity(new Intent(this, com.applovin.adview.AppLovinFullscreenActivity.class)));
        button(l, "打開全螢幕假廣告（5 秒後自己跳）", () -> startActivity(new Intent(this, com.applovin.adview.AppLovinFullscreenActivity.class).putExtra("auto", true)));
        for (int sec : new int[]{3, 5, 10}) button(l, sec + " 秒後自動跳（按完不要再碰）", () -> later(sec));
        Switch loopSw = new Switch(this);
        loopSw.setText("回到這裡就再跳一次（連續跳轉）"); loopSw.setTextSize(20); loopSw.setChecked(loop);
        loopSw.setOnCheckedChangeListener((v, on) -> { loop = on; if (on) later(2); });
        l.addView(loopSw);

        title(l, "3. 正常行為（守門員應該放行）");
        button(l, "我要打開（一般按鈕，會回報點按）", () -> jump("一般按鈕"));
        button(l, "分享一段文字", () -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "跳轉測試"), "分享")));

        ScrollView sv = new ScrollView(this); sv.addView(l); setContentView(sv);
        run(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); run(i); }

    /** adb-driven scenarios (no tap at all, like an ad acting on its own). */
    void run(Intent i) {
        String to = i.getStringExtra("to");
        if (to != null) prefs.edit().putString("to", to).apply();
        String r = i.getStringExtra("run");
        if (r == null) return;
        switch (r) {
            case "auto": later(3); break;
            case "loop": loop = true; later(2); break;
            case "ad": startActivity(new Intent(this, com.applovin.adview.AppLovinFullscreenActivity.class)); break;
            case "ad_auto": startActivity(new Intent(this, com.applovin.adview.AppLovinFullscreenActivity.class).putExtra("auto", true)); break;
            case "stop": loop = false; h.removeCallbacksAndMessages(null); break;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        updateSensor();
        if (loop) later(2);
    }

    @Override protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        if (!loop) h.removeCallbacksAndMessages(null);
    }

    void updateSensor() {
        sm.unregisterListener(this);
        if (shakeOn) sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        double g = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        if (Math.abs(g - 9.81) > shakeThreshold) jump("搖一下");
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    void later(int sec) {
        status.setText(sec + " 秒後自動跳轉…（不要碰手機）");
        h.postDelayed(() -> jump(sec + " 秒後自動"), sec * 1000L);
    }

    Target target() { return target(this); }

    static Target target(Context c) {
        String id = c.getSharedPreferences("jumper", MODE_PRIVATE).getString("to", null);
        List<Target> inst = installedTargets(c);
        for (Target t : inst) if (t.id.equals(id)) return t;
        return inst.isEmpty() ? ALL[0] : inst.get(0);
    }

    List<Target> installedTargets() { return installedTargets(this); }

    static List<Target> installedTargets(Context c) {
        List<Target> out = new ArrayList<>();
        for (Target t : ALL) {
            try { c.getPackageManager().getPackageInfo(t.pkg, 0); out.add(t); } catch (Exception ignored) {}
        }
        return out;
    }

    /** How an ad opens the target: its store page / web page / launcher entry, in a new task. */
    public static Intent jumpIntent(Context c) {
        Target t = target(c);
        Intent i = t.uri != null ? new Intent(Intent.ACTION_VIEW, Uri.parse(t.uri)).setPackage(t.pkg)
            : c.getPackageManager().getLaunchIntentForPackage(t.pkg);
        return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    void jump(String why) {
        long now = System.currentTimeMillis();
        if (now - lastJump < 1500) return; // one jump per shake
        lastJump = now;
        Target t = target();
        Intent i = jumpIntent(this);
        if (i == null) { status.setText("找不到 " + t.label); return; }
        status.setText(new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date()) + "　" + why + " → " + t.label);
        try { startActivity(i); } catch (Exception ex) { status.setText("打不開 " + t.label); }
    }

    TextView title(LinearLayout l, String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(22); t.setPadding(0, 36, 0, 8);
        t.setTextColor(Color.rgb(31, 90, 138)); l.addView(t); return t;
    }

    TextView note(LinearLayout l, String s) { TextView t = new TextView(this); t.setText(s); t.setTextSize(16); l.addView(t); return t; }

    void button(LinearLayout l, String s, Runnable r) {
        Button b = new Button(this); b.setText(s); b.setTextSize(18); b.setAllCaps(false); b.setOnClickListener(v -> r.run()); l.addView(b);
    }

    /**
     * A "game screen": handles the touch itself and deliberately never calls performClick(), so no accessibility
     * click event is sent — exactly like a game drawn on a SurfaceView.
     */
    final class GameArea extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        GameArea(Context c) { super(c); setBackgroundColor(Color.rgb(40, 110, 60)); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            p.setColor(Color.WHITE); p.setTextSize(52); p.setTextAlign(Paint.Align.CENTER);
            c.drawText("遊戲畫面：點一下就跳", getWidth() / 2f, getHeight() / 2f, p);
            p.setTextSize(34);
            c.drawText("（不回報點按）", getWidth() / 2f, getHeight() / 2f + 56, p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) jump("遊戲畫面");
            return true;
        }
    }
}
