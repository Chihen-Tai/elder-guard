package com.applovin.adview;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** TEST ONLY: an in-app interstitial page (the user is inside this app when it appears). */
public class AppLovinFullscreenActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this); t.setText("正常 App 內的插頁廣告（測試）"); t.setTextSize(24); setContentView(t);
    }
}
