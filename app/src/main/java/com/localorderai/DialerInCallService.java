package com.localorderai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.CallEndpointException;
import android.telecom.InCallService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    // ---------------------------------------------------------------------------------
    // أندرويد 14+ (API 34): CallEndpoint هو البديل الرسمي لـ setAudioRoute (اللي بقى Deprecated).
    // الميثودات دي بتتنادى بس على 34+، وعلى الأقدم بتتجاهل.
    // ---------------------------------------------------------------------------------

    private volatile List<CallEndpoint> endpoints = Collections.emptyList();
    private volatile CallEndpoint currentEndpoint;

    @Override
    public void onAvailableCallEndpointsChanged(List<CallEndpoint> available) {
        endpoints = new ArrayList<>(available);
        StringBuilder b = new StringBuilder();
        for (CallEndpoint e : available) b.append(e.getEndpointType()).append(' ');
        EventLog.add("call endpoints available(types): " + b);
    }

    @Override
    public void onCallEndpointChanged(CallEndpoint endpoint) {
        currentEndpoint = endpoint;
        EventLog.add("call endpoint changed -> type " + endpoint.getEndpointType());
        notifyUi();
    }

    /**
     * أندرويد 14+: هل المسار الحالي مكبر؟ بيرجّع null لو مش معروف (أقدم من 34 أو لسه ما وصلش إشعار).
     */
    Boolean speakerFromEndpoint() {
        CallEndpoint e = currentEndpoint;
        if (Build.VERSION.SDK_INT < 34 || e == null) return null;
        return e.getEndpointType() == CallEndpoint.TYPE_SPEAKER;
    }

    /**
     * أندرويد 14+: بيطلب تحويل الصوت للمكبر (أو لسماعة الأذن/السلكية) عن طريق CallEndpoint.
     * بيرجّع true لو الطلب اتبعت فعلاً، و false لو مش متاح (نرجع للطريقة القديمة).
     * لو النظام رفض الطلب بعدين، بنجرّب setAudioRoute القديمة كاحتياطي، وكل ده بيتسجّل في EventLog.
     */
    boolean requestSpeaker(final boolean on) {
        if (Build.VERSION.SDK_INT < 34) return false;
        CallEndpoint target = null;
        for (CallEndpoint e : endpoints) {
            int t = e.getEndpointType();
            if (on) {
                if (t == CallEndpoint.TYPE_SPEAKER) {
                    target = e;
                    break;
                }
            } else if (t == CallEndpoint.TYPE_EARPIECE) {
                target = e;
                break;
            } else if (t == CallEndpoint.TYPE_WIRED_HEADSET && target == null) {
                target = e;
            }
        }
        if (target == null) {
            EventLog.add("endpoint request " + (on ? "SPEAKER" : "EARPIECE") + ": no matching endpoint available");
            return false;
        }
        final int type = target.getEndpointType();
        try {
            requestCallEndpointChange(target, getMainExecutor(), new OutcomeReceiver<Void, CallEndpointException>() {
                @Override
                public void onResult(Void result) {
                    EventLog.add("endpoint request type " + type + " -> OK");
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onError(CallEndpointException error) {
                    EventLog.add("endpoint request type " + type + " -> ERROR code " + error.getCode()
                            + ", falling back to setAudioRoute");
                    try {
                        setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_WIRED_OR_EARPIECE);
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Exception e) {
            EventLog.add("endpoint request threw " + e.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    /** سطر تشخيص قصير لمسار الـ InCallService (بيتضاف لتقرير المشاكل). */
    String describeEndpoints() {
        if (Build.VERSION.SDK_INT < 34) return "endpoints n/a (SDK<34)";
        CallEndpoint c = currentEndpoint;
        StringBuilder b = new StringBuilder("ep cur=").append(c == null ? "none" : String.valueOf(c.getEndpointType()));
        b.append(" avail=");
        for (CallEndpoint e : endpoints) b.append(e.getEndpointType()).append(',');
        return b.toString();
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
