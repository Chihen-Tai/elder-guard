package com.applovin.adview;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** TEST ONLY: a full-screen "ad" whose close button is wired to the ad link (the trap seen in real ads). */
public class AppLovinFullscreenActivity extends Activity {
    final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32, 200, 32, 32);
        l.setBackgroundColor(Color.rgb(255, 236, 200));
        Button x = new Button(this);
        x.setText("✕ 關閉");
        x.setTextSize(20);
        x.setOnClickListener(v -> openAndClose());
        l.addView(x);
        TextView t = new TextView(this);
        t.setText("（測試用假廣告）\n\n超值特賣！\n\n按「✕ 關閉」會跳到您在測試器選的 App。");
        t.setTextSize(26);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        setContentView(l);
        if (getIntent().getBooleanExtra("auto", false)) h.postDelayed(this::openAndClose, 5000);
    }

    @Override protected void onPause() { super.onPause(); h.removeCallbacksAndMessages(null); }

    /** The ad itself opens the target (like a real ad click-through), then closes. */
    void openAndClose() {
        android.content.Intent i = org.elderguard.testing.jumper.Main.jumpIntent(this);
        if (i != null) try { startActivity(i); } catch (Exception ignored) {}
        finish();
    }
}
