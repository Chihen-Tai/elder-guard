package org.elderguard.testing.adsim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * TEST ONLY. After a delay, repeatedly: (1) starts the full-screen "ad" activity over whatever is on screen,
 * (2) posts a notification disguised as a system warning, (3) if allowed, shows an overlay fake warning for 4 s.
 * Mirrors the field incident so the guard can be verified without installing real adware.
 */
public class PopService extends Service {
    private final Handler h = new Handler(Looper.getMainLooper());
    private int n = 0;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("s", "status", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel("w", "warnings", NotificationManager.IMPORTANCE_HIGH));
        Notification fg = new Notification.Builder(this, "s").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("PDF").build();
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(7, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); else startForeground(7, fg);
        h.postDelayed(this::pop, 20_000);
    }

    private void pop() {
        n++;
        try {
            // alternate a normal ad page and a "sticky" one that ignores BACK
            Class<?> page = (n % 2 == 1) ? com.bytedance.sdk.openadsdk.activity.TTFullWebActivity.class
                : com.bytedance.sdk.openadsdk.activity.single.TTFullScreenExpressVideoActivity.class;
            startActivity(new Intent(this, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
        Notification w = new Notification.Builder(this, "w").setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("⚠ 警告（測試）").setContentText("您的手機中毒了，請立即清理（這是測試通知）").build();
        getSystemService(NotificationManager.class).notify(100 + n, w);
        if (Settings.canDrawOverlays(this)) {
            TextView v = new TextView(this);
            v.setText("⚠ 手機記憶體不足！立即清理（測試懸浮視窗）");
            v.setBackgroundColor(Color.rgb(255, 250, 200)); v.setTextSize(22);
            WindowManager wm = getSystemService(WindowManager.class);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, 400,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            try { wm.addView(v, lp); h.postDelayed(() -> { try { wm.removeView(v); } catch (Exception ignored) { } }, 4_000); } catch (Exception ignored) { }
        }
        h.postDelayed(this::pop, 20_000);
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
}
