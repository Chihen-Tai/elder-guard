package org.elderguard.testing.shop;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** TEST ONLY: a fake shopping app (the target of simulated ad redirects). */
public class Main extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setTextSize(28);
        t.setText("測試用購物 App\n（廣告跳轉的目的地）\n\n" + (getIntent().getData() != null ? getIntent().getData().toString() : ""));
        setContentView(t);
    }
}
