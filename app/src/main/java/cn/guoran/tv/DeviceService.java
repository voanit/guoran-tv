package cn.guoran.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;

/** 家长网页与盒子同机运行，手机管理不依赖开发电脑。 */
public final class DeviceService extends Service {
    private static final String CHANNEL_ID = "parent_service";
    private static final int NOTIFICATION_ID = 8787;
    private ParentServer server;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastTick;
    private final Runnable watchTick = new Runnable() {
        @Override public void run() {
            try {
                PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                long now = SystemClock.elapsedRealtime();
                int seconds = lastTick == 0 ? 0 : (int) Math.min(5, (now - lastTick) / 1000);
                lastTick = now;
                Store store = Store.get(DeviceService.this);
                store.tickBoxUsage(power != null && power.isInteractive(), seconds);
                MouseNavigationService.enforceWatchRules();
            } catch (RuntimeException error) { Log.w("GuoranTV", "盒子计时暂不可用：" + error.getClass().getSimpleName()); }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        try {
            // Android 8 起须及时进入前台，不能等配置读取或 HTTP 监听完成后才显示通知。
            startForeground(NOTIFICATION_ID, serviceNotification());
            server = new ParentServer(this, new ParentServer.Launcher() {
                @Override public void install(final File apk, final String uploadId) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            Intent intent = new Intent(DeviceService.this, InstallActivity.class);
                            intent.putExtra("uploadId", uploadId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        }
                    });
                }
            });
            server.start();
            handler.post(watchTick);
        } catch (Exception error) {
            Log.e("GuoranTV", "家长服务启动失败：" + error.getClass().getSimpleName());
            stopSelf();
        }
    }

    private Notification serviceNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) throw new IllegalStateException("通知服务不可用");
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "家长管理与观看计时", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持局域网家长管理和观看计时运行");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // 返回入口固定指向本应用；支持不可变 PendingIntent 的系统禁止外部改写。
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, flags);
        return builder.setSmallIcon(R.drawable.app_icon)
                .setContentTitle("果然 TV 正在运行")
                .setContentText("家长管理与观看计时已开启，点击返回果然 TV")
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (server != null) {
            server.close();
            server = null;
        }
        lastTick = 0;
        stopForeground(true);
        super.onDestroy();
    }
}
