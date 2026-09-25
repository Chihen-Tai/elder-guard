package org.elderguard.testing.normalapp;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Bundle;
import android.widget.TextView;

/** Posts ordinary notifications, including words like 更新/電池 alone, which must NOT be flagged. */
public class Main extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this); t.setText("測試用正常 App"); setContentView(t);
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("m", "messages", NotificationManager.IMPORTANCE_HIGH));
        if (getIntent().getBooleanExtra("interstitial", false))
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                startActivity(new android.content.Intent(this, com.applovin.adview.AppLovinFullscreenActivity.class)), 1500);
        String[][] msgs = {{"王小明", "今天晚上一起吃飯嗎？"}, {"App 更新", "新版本已經可以更新了"}, {"電池", "電池已充飽"}};
        for (int i = 0; i < msgs.length; i++)
            nm.notify(i + 1, new Notification.Builder(this, "m").setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(msgs[i][0]).setContentText(msgs[i][1]).build());
    }
}
