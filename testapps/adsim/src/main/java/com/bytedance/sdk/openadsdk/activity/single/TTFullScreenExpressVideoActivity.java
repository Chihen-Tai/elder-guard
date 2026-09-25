package com.bytedance.sdk.openadsdk.activity.single;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/** TEST ONLY: a "sticky" fake ad that swallows BACK (as real ads with a countdown do), to test the HOME fallback. */
public class TTFullScreenExpressVideoActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("【測試用假廣告（返回鍵無效）】\n這是守門員的測試程式");
        t.setTextSize(26); t.setGravity(Gravity.CENTER); t.setBackgroundColor(Color.rgb(220, 235, 255));
        setContentView(t);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, () -> { });
    }
    @SuppressWarnings("deprecation") @Override public void onBackPressed() { /* swallow */ }
}
