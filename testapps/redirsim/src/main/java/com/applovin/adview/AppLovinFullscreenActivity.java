package com.applovin.adview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** TEST ONLY: a full-screen "ad" whose close button is wired to the ad link (the trap seen in real ads). */
public class AppLovinFullscreenActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(0, 300, 0, 0);
        ImageButton x = new ImageButton(this);
        x.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        x.setContentDescription("關閉");
        x.setOnClickListener(v -> openShop());
        l.addView(x);
        TextView t = new TextView(this); t.setText("（假廣告）超值特賣！"); t.setTextSize(30); l.addView(t);
        setContentView(l);
        if (getIntent().getBooleanExtra("auto", false)) new Handler(Looper.getMainLooper()).postDelayed(this::openShop, 4000);
    }

    void openShop() {
        Intent i = getPackageManager().getLaunchIntentForPackage("org.elderguard.testing.shop");
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
