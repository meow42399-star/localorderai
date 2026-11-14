package com.localorderai;

import android.app.Notification;
import android.app.PendingIntent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.regex.Pattern;

/**
 * بيقرا إشعار المكالمة الجارية (بتاع تطبيق الهاتف) وبيدوس على زرار المكبر اللي فيه لو موجود.
 * بيهتم بإشعارات المكالمات بس، ومش بيقرا ولا بيحفظ محتوى أي إشعار تاني.
 * بيسجّل في تقرير المشكلة أسماء أزرار إشعار المكالمة (مش نصه) عشان نعرف الموجود فعلاً.
 */
public class CallNotificationService extends NotificationListenerService {

    private static final Pattern SPEAKER = Pattern.compile(
            "speaker|loudspeaker|مكبر|مكبّر|haut-parleur|altavoz|lautsprecher|alto-falante|hoparl",
            Pattern.CASE_INSENSITIVE);
    private static final String[] CALL_HINTS = {"incallui", "dialer", "telecom", "phone", "contacts", "call"};

    private static volatile CallNotificationService instance;
    private static String lastLogged = "";

    static boolean isConnected() {
        return instance != null;
    }

    /** بيدوس على زرار المكبر في إشعار المكالمة، وبيرجّع رسالة قصيرة للمستخدم. */
    static String pressSpeaker() {
        CallNotificationService s = instance;
        if (s == null) return "فعّل «الوصول للإشعارات» الأول";
        return s.press();
    }

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (isCallNotification(sbn)) logOnce("call notification: " + describe(sbn));
    }

    private String press() {
        StatusBarNotification[] list;
        try {
            list = getActiveNotifications();
        } catch (Exception e) {
            return "ما قدرتش أقرا الإشعارات";
        }
        StringBuilder seen = new StringBuilder();
        boolean sawCall = false;
        for (StatusBarNotification sbn : list) {
            if (!isCallNotification(sbn)) continue;
            sawCall = true;
            Notification.Action[] acts = sbn.getNotification().actions;
            seen.append(titles(acts)).append(' ');
            if (acts == null) continue;
            for (Notification.Action a : acts) {
                CharSequence t = a.title;
                if (t == null || a.actionIntent == null || !SPEAKER.matcher(t).find()) continue;
                try {
                    a.actionIntent.send();
                    EventLog.add("notification speaker: دست على «" + t + "»");
                    return "دست على «" + t + "» في إشعار المكالمة";
                } catch (PendingIntent.CanceledException e) {
                    EventLog.add("notification speaker: الزرار «" + t + "» مش شغّال (canceled)");
                    return "زرار المكبر في الإشعار مش شغّال";
                }
            }
        }
        String msg = sawCall
                ? "إشعار المكالمة مفيهوش زرار مكبر (الأزرار: " + seen.toString().trim() + ")"
                : "ما لقيتش إشعار مكالمة";
        EventLog.add("notification speaker: " + msg);
        return msg;
    }

    private boolean isCallNotification(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName().equals(getPackageName())) return false;
        Notification n = sbn.getNotification();
        if (Notification.CATEGORY_CALL.equals(n.category)) return true;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return false;
        String pkg = sbn.getPackageName().toLowerCase();
        for (String h : CALL_HINTS) {
            if (pkg.contains(h)) return true;
        }
        return false;
    }

    private static String describe(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        return "pkg=" + sbn.getPackageName() + " category=" + n.category + " actions=" + titles(n.actions);
    }

    private static String titles(Notification.Action[] acts) {
        if (acts == null || acts.length == 0) return "[]";
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < acts.length; i++) {
            if (i > 0) b.append(", ");
            b.append(acts[i].title);
        }
        return b.append(']').toString();
    }

    private static void logOnce(String s) {
        if (s.equals(lastLogged)) return;
        lastLogged = s;
        EventLog.add(s);
    }
}
