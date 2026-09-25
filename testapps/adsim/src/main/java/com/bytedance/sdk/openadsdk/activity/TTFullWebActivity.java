package com.bytedance.sdk.openadsdk.activity;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** TEST ONLY: full-screen "ad" page with the same class name as the real Pangle ad activity. */
public class TTFullWebActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("【測試用假廣告】\n這是守門員的測試程式\n不是真的廣告");
        t.setTextSize(28); t.setGravity(Gravity.CENTER); t.setBackgroundColor(Color.rgb(255, 235, 200));
        setContentView(t);
    }
}
