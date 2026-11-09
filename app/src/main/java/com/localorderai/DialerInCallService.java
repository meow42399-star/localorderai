package com.localorderai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import java.util.concurrent.CopyOnWriteArrayList;

public class DialerInCallService extends InCallService {

    public static volatile DialerInCallService instance;
    public static final CopyOnWriteArrayList<Runnable> UI_LISTENERS = new CopyOnWriteArrayList<>();

    private static final String CHANNEL = "incoming_calls";
    private static final int NOTIF_ID = 2001;

    public static String numberOf(Call call) {
        Uri h = call.getDetails().getHandle();
        return h == null ? "رقم غير معروف" : h.getSchemeSpecificPart();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onCallAdded(final Call call) {
        CallQueue.get().onCallAdded(call);
        if (call.getState() == Call.STATE_RINGING) {
            showIncomingNotification(call);
            call.registerCallback(new Call.Callback() {
                @Override
                public void onStateChanged(Call c, int state) {
                    if (state != Call.STATE_RINGING) cancelIncomingNotification();
                    notifyUi();
                }
            });
        } else {
            Intent i = new Intent(this, CallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
        notifyUi();
    }

    @Override
    public void onCallRemoved(Call call) {
        cancelIncomingNotification();
        notifyUi();
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        notifyUi();
    }

    private void notifyUi() {
        for (Runnable r : UI_LISTENERS) r.run();
    }

    private void showIncomingNotification(Call call) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "المكالمات الواردة", NotificationManager.IMPORTANCE_HIGH));
        Intent i = new Intent(this, CallActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("مكالمة واردة")
                .setContentText(numberOf(call))
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build();
        nm.notify(NOTIF_ID, n);
    }

    private void cancelIncomingNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(NOTIF_ID);
    }
}
