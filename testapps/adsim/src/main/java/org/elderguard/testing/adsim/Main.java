package org.elderguard.testing.adsim;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

/** First launch: starts the pop-up service and hides the launcher icon (TEST ONLY). */
public class Main extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("測試用 PDF 閱讀器（守門員測試程式）");
        setContentView(t);
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        startForegroundService(new android.content.Intent(this, PopService.class));
        if (!getIntent().getBooleanExtra("keepIcon", false)) {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, "org.elderguard.testing.adsim.Launcher"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
