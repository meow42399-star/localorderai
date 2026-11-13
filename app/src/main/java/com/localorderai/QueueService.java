package com.localorderai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** خدمة Foreground بس عشان النظام ما يقتلش العملية بين المكالمات. */
public class QueueService extends Service {

    static final String ACTION_STOP = "com.localorderai.STOP_QUEUE";
    static final String ACTION_SPEAKER = "com.localorderai.TOGGLE_SPEAKER";
    static final String ACTION_MUTE = "com.localorderai.TOGGLE_MUTE";
    private static final String CHANNEL = "queue";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();
        if (ACTION_SPEAKER.equals(act)) {
            AudioControl.toggleSpeaker(this);
            return START_NOT_STICKY;
        }
        if (ACTION_MUTE.equals(act)) {
            AudioControl.setMuted(this, !AudioControl.isMuted(this));
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            CallQueue.get().stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "الاتصال التلقائي", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, QueueService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent spk = PendingIntent.getService(this, 3,
                new Intent(this, QueueService.class).setAction(ACTION_SPEAKER),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent mute = PendingIntent.getService(this, 4,
                new Intent(this, QueueService.class).setAction(ACTION_MUTE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("الاتصال التلقائي شغال")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_lock_silent_mode_off, "مكبر", spk)
                .addAction(android.R.drawable.ic_btn_speak_now, "ميوت", mute)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stop)
                .build();
        // زي التطبيق المرجعي: Foreground من نوع phoneCall دايماً (MANAGE_OWN_CALLS كفاية من غير Default Dialer).
        // لو النظام رفضه، بنرجع لـ dataSync.
        // المسار الصامت (SpeakerRoute) بيشغّل صوت، فبنجرّب نوع mediaPlayback مع phoneCall أولاً، وبعدين نرجع خطوة خطوة.
        int[] types = {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC};
        String[] names = {"phoneCall+mediaPlayback", "mediaPlayback", "phoneCall", "dataSync"};
        boolean started = false;
        for (int i = 0; i < types.length && !started; i++) {
            try {
                startForeground(1, n, types[i]);
                started = true;
                EventLog.add("foreground service type: " + names[i]);
            } catch (Exception e) {
                EventLog.add(names[i] + " FGS rejected: " + e.getClass().getSimpleName());
            }
        }
        if (!started) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
