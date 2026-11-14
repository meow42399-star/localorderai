package com.localorderai;

import android.Manifest;
import android.app.Notification;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * استقبال العملاء من جروب واتساب:
 * إشعار الجروب (CallNotificationService) ← أحدث صورة في مجلد واتساب ← Claude (الاسم/الرقم/التفاصيل)
 * ← فحص الرقم ← إضافة للطابور وبدء الاتصال تلقائياً.
 * الرقم المشكوك فيه أو المكرر ما بيتصلش بيه، وبيظهر في سجل الاستقبال.
 */
final class Intake {

    interface Listener {
        void onIntakeChanged();
    }

    private static final String PREFS = "prefs";
    private static final String K_ON = "wa_on";
    private static final String K_GROUP = "wa_group";
    private static final String K_KEY = "api_key";
    private static final String K_LOG = "intake_log";
    private static final String K_SEEN = "intake_seen";
    private static final String K_NUMS = "intake_nums";

    private static final long DEDUPE_MS = 24L * 3600 * 1000;
    private static final double MIN_CONFIDENCE = 0.7;
    private static final int LOG_MAX = 30;
    private static final int SEEN_MAX = 200;

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Intake() {
    }

    // ------------------------------------------------------------ الإعدادات

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(K_ON, false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_ON, on).apply();
    }

    static String group(Context c) {
        return prefs(c).getString(K_GROUP, "");
    }

    static String apiKey(Context c) {
        return prefs(c).getString(K_KEY, "");
    }

    static void save(Context c, String group, String key) {
        prefs(c).edit().putString(K_GROUP, group).putString(K_KEY, key).apply();
    }

    static boolean isWhatsApp(String pkg) {
        return "com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg);
    }

    static String[] imagePermissions() {
        return Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_IMAGES}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    static boolean hasImagePermission(Context c) {
        return c.checkSelfPermission(imagePermissions()[0]) == PackageManager.PERMISSION_GRANTED;
    }

    static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    // ------------------------------------------------------------ الإشعارات

    /** بتتنادى من CallNotificationService لكل إشعار واتساب. */
    static void onWhatsAppNotification(final Context c, StatusBarNotification sbn) {
        if (!isEnabled(c)) return;
        String group = group(c).trim();
        if (group.isEmpty()) return;
        Bundle e = sbn.getNotification().extras;
        if (e == null || !matchesGroup(e, group) || !lastMessageIsImage(e)) return;
        final Context app = c.getApplicationContext();
        final long post = sbn.getPostTime();
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                process(app, post);
            }
        });
    }

    private static boolean matchesGroup(Bundle e, String group) {
        String g = group.toLowerCase();
        String[] keys = {Notification.EXTRA_CONVERSATION_TITLE, Notification.EXTRA_TITLE,
                Notification.EXTRA_TITLE_BIG, Notification.EXTRA_SUB_TEXT};
        for (String k : keys) {
            CharSequence v = e.getCharSequence(k);
            if (v != null && v.toString().toLowerCase().contains(g)) return true;
        }
        return false;
    }

    /** آخر رسالة في الإشعار لازم تكون صورة (الإشعار بيتحدّث مع كل رسالة جديدة). */
    @SuppressWarnings("deprecation")
    private static boolean lastMessageIsImage(Bundle e) {
        Parcelable[] msgs = e.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            Bundle m = (Bundle) msgs[msgs.length - 1];
            String type = m.getString("type");
            if (type != null && type.startsWith("image/")) return true;
            return isPhotoText(m.getCharSequence("text"));
        }
        return isPhotoText(e.getCharSequence(Notification.EXTRA_TEXT));
    }

    private static boolean isPhotoText(CharSequence t) {
        if (t == null) return false;
        String s = t.toString().trim().toLowerCase();
        return s.contains("\uD83D\uDCF7") || s.matches(".*(^|[:\\s])(photo|image|صورة)\\s*$");
    }

    // ------------------------------------------------------------ المعالجة

    private static void process(Context c, long postMs) {
        try {
            String key = apiKey(c);
            if (key.isEmpty()) {
                log(c, "error", "مفيش مفتاح API في الإعدادات");
                return;
            }
            if (!hasImagePermission(c)) {
                log(c, "error", "مفيش صلاحية الصور (اختار «السماح بالكل»)");
                return;
            }
            Uri img = WhatsAppImages.waitForNewest(c, postMs, loadSeen(c));
            if (img == null) {
                log(c, "review", "وصلت صورة بس ما لقيتهاش في مجلد واتساب (التحميل التلقائي شغّال؟)");
                return;
            }
            markSeen(c, ContentUris.parseId(img));

            Bitmap bmp = WhatsAppImages.load(c, img, 1600);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            VisionClient.Result r = VisionClient.extract(key, Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));

            String name = r.name == null ? "" : r.name.trim();
            String who = name.isEmpty() ? "" : name + " — ";
            String phone = PhoneNumbers.normalize(r.phone);
            if (phone == null) {
                log(c, "review", who + "الرقم مش واضح أو مش موبايل مصري صالح" + shortDetails(r.details));
                return;
            }
            String shown = who + EventLog.mask(phone) + shortDetails(r.details);
            if (r.confidence < MIN_CONFIDENCE) {
                log(c, "review", shown + " — ثقة قليلة (" + Math.round(r.confidence * 100) + "%)، ما اتصلتش");
                return;
            }
            if (isDuplicate(c, phone)) {
                log(c, "skip", shown + " — مكرر خلال 24 ساعة");
                return;
            }
            rememberNumber(c, phone);
            final Context app = c;
            final String number = phone;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    CallQueue.get().add(app, number);
                }
            });
            log(c, "added", shown);
        } catch (SecurityException e) {
            log(c, "error", "مفيش صلاحية لقراءة الصورة");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log(c, "error", "فشل القراءة: " + e.getMessage());
        }
    }

    private static String shortDetails(String d) {
        if (d == null) return "";
        d = d.trim().replace('\n', ' ');
        if (d.isEmpty()) return "";
        return " — " + (d.length() > 60 ? d.substring(0, 60) + "…" : d);
    }

    // ------------------------------------------------------------ السجل (اسم العميل بيتحفظ هنا بس، مش في التقارير)

    private static void log(Context c, String status, String text) {
        String icon = "added".equals(status) ? "✔" : "review".equals(status) ? "⚠" : "skip".equals(status) ? "↺" : "✘";
        String line = new SimpleDateFormat("HH:mm", Locale.US).format(new Date()) + " " + icon + " " + text;
        SharedPreferences sp = prefs(c);
        JSONArray old = readArray(sp.getString(K_LOG, "[]"));
        JSONArray neu = new JSONArray();
        neu.put(line);
        for (int i = 0; i < old.length() && neu.length() < LOG_MAX; i++) neu.put(old.optString(i));
        sp.edit().putString(K_LOG, neu.toString()).apply();
        EventLog.add("intake " + status); // من غير أسماء ولا تفاصيل (التقارير بتتنشر على GitHub العام)
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) l.onIntakeChanged();
            }
        });
    }

    static String logText(Context c, int max) {
        JSONArray a = readArray(prefs(c).getString(K_LOG, "[]"));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length() && i < max; i++) b.append(a.optString(i)).append('\n');
        return b.toString();
    }

    // ------------------------------------------------------------ منع التكرار

    private static boolean isDuplicate(Context c, String phone) {
        long t = readObject(prefs(c).getString(K_NUMS, "{}")).optLong(phone, 0);
        return t != 0 && System.currentTimeMillis() - t < DEDUPE_MS;
    }

    private static void rememberNumber(Context c, String phone) {
        JSONObject old = readObject(prefs(c).getString(K_NUMS, "{}"));
        JSONObject neu = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            Iterator<String> it = old.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (now - old.optLong(k, 0) < DEDUPE_MS) neu.put(k, old.optLong(k, 0));
            }
            neu.put(phone, now);
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(K_NUMS, neu.toString()).apply();
    }

    private static Set<Long> loadSeen(Context c) {
        Set<Long> s = new HashSet<>();
        JSONArray a = readArray(prefs(c).getString(K_SEEN, "[]"));
        for (int i = 0; i < a.length(); i++) s.add(a.optLong(i));
        return s;
    }

    private static void markSeen(Context c, long id) {
        JSONArray old = readArray(prefs(c).getString(K_SEEN, "[]"));
        JSONArray neu = new JSONArray();
        neu.put(id);
        for (int i = 0; i < old.length() && neu.length() < SEEN_MAX; i++) neu.put(old.optLong(i));
        prefs(c).edit().putString(K_SEEN, neu.toString()).apply();
    }

    private static JSONArray readArray(String s) {
        try {
            return new JSONArray(s);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static JSONObject readObject(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
