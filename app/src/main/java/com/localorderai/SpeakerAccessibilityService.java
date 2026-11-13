package com.localorderai;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Pattern;

/**
 * بيدوس على زر المكبر في شاشة المكالمة بتاعة تطبيق الهاتف، من غير ما التطبيق يبقى Default Dialer.
 * بيشتغل بس بعد request() (أثناء مكالمة من الطابور)، وبيدوّر على زر المكبر بس ومش بيلمس أي حاجة تانية.
 * الميكروفون (الميوت) ملوش علاقة بالخدمة دي.
 */
public class SpeakerAccessibilityService extends AccessibilityService {

    /** أقل وقت بعد بداية الطلب قبل أول ضغطة (شاشة المكالمة بتحتاج لحظة تظهر). */
    private static final long MIN_WAIT_MS = 800;
    /** أقل فاصل بين ضغطتين. */
    private static final long CLICK_GAP_MS = 1200;
    /** زرار المكبر اليدوي: أقل وقت قبل الضغطة (شاشة المكالمة لسه راجعة قدّام). */
    private static final long TOGGLE_MIN_WAIT_MS = 400;
    /** زرار المكبر اليدوي: لو شاشة المكالمة ما ظهرتش في المدة دي نلغي الطلب (عشان ما يتنفذش في وقت غلط). */
    private static final long TOGGLE_TTL_MS = 25000;

    private static final Pattern SPEAKER = Pattern.compile(
            "speaker|loudspeaker|مكبر|مكبّر|haut-parleur|altavoz|lautsprecher|alto-falante|hoparl",
            Pattern.CASE_INSENSITIVE);
    private static final String[] CALL_UI_HINTS = {"incallui", "dialer", "telecom", "phone", "contacts", "call"};

    private static volatile SpeakerAccessibilityService instance;
    private static volatile boolean active;
    private static volatile boolean want;
    private static int attempts;
    private static long startMs;
    private static long lastClickMs;
    private static String lastLogged = "";
    /** وضع "بدّل مرة واحدة": ضغطة واحدة على زرار شاشة المكالمة بدل الوصول لحالة معينة. */
    private static volatile boolean toggleMode;
    /** وقت انتهاء الطلب (0 = من غير مهلة). */
    private static long deadlineMs;

    /** آخر حالة معروفة لزرار المكبر في شاشة المكالمة (null = مجهولة). */
    static volatile Boolean lastKnownState;

    /** آخر حاجة الخدمة شافتها (للتشخيص، بتظهر في الشاشة الرئيسية وفي التقرير). */
    static volatile String lastInfo = "";

    static boolean isConnected() {
        return instance != null;
    }

    /** ابدأ دوّر على زر المكبر وخلّيه على الحالة المطلوبة. */
    static void request(boolean on) {
        want = on;
        toggleMode = false;
        deadlineMs = 0;
        active = true;
        attempts = 0;
        lastClickMs = 0;
        startMs = SystemClock.uptimeMillis();
        note(instance == null ? "خدمة المكبر مش مفعّلة" : "بدوّر على زر المكبر...");
        nudge();
    }

    /** زرار المكبر اليدوي: دوس مرة واحدة على زرار شاشة المكالمة (بيبدّل الحالة زي الزرار الحقيقي). */
    static void requestToggle() {
        toggleMode = true;
        active = true;
        attempts = 0;
        lastClickMs = 0;
        startMs = SystemClock.uptimeMillis();
        deadlineMs = startMs + TOGGLE_TTL_MS;
        note(instance == null ? "خدمة المكبر مش مفعّلة" : "بدّل المكبر: بدوّر على شاشة المكالمة...");
        nudge();
    }

    /** المكالمة خلصت أو الطابور وقف. */
    static void stop() {
        active = false;
        toggleMode = false;
    }

    /** إعادة فحص من غير ما نصفّر عدّاد المحاولات (آمنة للتكرار). */
    static void nudge() {
        SpeakerAccessibilityService s = instance;
        if (s != null && active) s.scan();
    }

    private static void note(String text) {
        lastInfo = text;
        if (!text.equals(lastLogged)) {
            lastLogged = text;
            EventLog.add("speaker: " + text);
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable readStateRunnable = new Runnable() {
        @Override
        public void run() {
            readState();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        lastInfo = "الخدمة شغالة";
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (active) scan();
    }

    @Override
    public void onInterrupt() {
    }

    private void scan() {
        if (deadlineMs != 0 && SystemClock.uptimeMillis() > deadlineMs) {
            active = false;
            toggleMode = false;
            note("انتهت المهلة: شاشة المكالمة ما ظهرتش");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence p = root.getPackageName();
        String pkg = p == null ? "" : p.toString();
        if (pkg.equals(getPackageName())) return;
        if (!looksLikeCallUi(pkg)) {
            note("الشاشة الحالية: " + pkg + " (مش شاشة مكالمة)");
            return;
        }
        AccessibilityNodeInfo n = find(root, 0);
        if (n == null) {
            note("شاشة المكالمة: " + pkg + " — مش لاقي زر المكبر");
            return;
        }
        Boolean state = stateOf(n);
        String info = "شاشة المكالمة: " + pkg + " — زر المكبر: " + describe(n)
                + " — حالته: " + (state == null ? "مجهولة" : (state ? "شغال" : "مقفول"));
        lastKnownState = state;
        if (toggleMode) {
            if (SystemClock.uptimeMillis() - startMs < TOGGLE_MIN_WAIT_MS) {
                note(info);
                return;
            }
            AccessibilityNodeInfo t = clickableOf(n);
            if (t == null) {
                note(info + " — مش قابل للضغط");
                return;
            }
            t.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            toggleMode = false;
            active = false;
            note(info + " — بدّلت المكبر");
            handler.postDelayed(readStateRunnable, 700); // نقرا الحالة بعد الضغط
            return;
        }
        if (state != null && state == want) {
            active = false;
            note(info + " ✔");
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - startMs < MIN_WAIT_MS || now - lastClickMs < CLICK_GAP_MS) {
            note(info);
            return;
        }
        if (state == null && isAlreadyAsWanted()) {
            // حالة الزرار مجهولة والنظام بيقول إن المكبر على الوضع المطلوب: ما نضغطش عشان ما نقلبوش
            active = false;
            note(info + " — النظام بيقول إنه مضبوط أصلاً ✔");
            return;
        }
        // لو حالة الزرار مجهولة بنضغط مرة واحدة بس، عشان ما نقلبش الحالة
        if (attempts >= (state == null ? 1 : 3)) return;
        AccessibilityNodeInfo target = clickableOf(n);
        if (target == null) {
            note(info + " — مش قابل للضغط");
            return;
        }
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        attempts++;
        lastClickMs = now;
        note(info + " — دست عليه (" + attempts + ")");
    }

    /** قراءة حالة الزرار بعد الضغط (من غير أي ضغط). */
    private void readState() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo n = find(root, 0);
        if (n == null) return;
        Boolean st = stateOf(n);
        lastKnownState = st;
        note("المكبر بعد الضغط: " + (st == null ? "حالته مجهولة (شوف شاشة المكالمة)" : (st ? "شغال" : "مقفول")));
    }

    @SuppressWarnings("deprecation")
    private boolean isAlreadyAsWanted() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        return am != null && am.isSpeakerphoneOn() == want;
    }

    private boolean looksLikeCallUi(String pkg) {
        String l = pkg.toLowerCase();
        for (String h : CALL_UI_HINTS) {
            if (l.contains(h)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 25) return null;
        if (matches(n) && clickableOf(n) != null) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = find(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private boolean matches(AccessibilityNodeInfo n) {
        String id = n.getViewIdResourceName();
        if (id != null && id.toLowerCase().contains("speaker")) return true;
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() < 40 && SPEAKER.matcher(d).find()) return true;
        CharSequence t = n.getText();
        return t != null && t.length() < 30 && SPEAKER.matcher(t).find();
    }

    private AccessibilityNodeInfo clickableOf(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        int up = 0;
        while (c != null && !c.isClickable() && up < 4) {
            c = c.getParent();
            up++;
        }
        return (c != null && c.isClickable()) ? c : null;
    }

    private Boolean stateOf(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickableOf(n);
        AccessibilityNodeInfo[] nodes = c == null ? new AccessibilityNodeInfo[]{n} : new AccessibilityNodeInfo[]{n, c};
        for (AccessibilityNodeInfo x : nodes) {
            if (x.isCheckable()) return x.isChecked();
            if (x.isSelected()) return true;
            if (Build.VERSION.SDK_INT >= 30) {
                CharSequence sd = x.getStateDescription();
                if (sd != null) {
                    String t = sd.toString().toLowerCase();
                    if (t.contains("off") || t.contains("not") || t.contains("مقفل") || t.contains("متوقف")) return false;
                    if (t.contains("on") || t.contains("شغال") || t.contains("مفعل")) return true;
                }
            }
        }
        return null;
    }

    private String describe(AccessibilityNodeInfo n) {
        String id = n.getViewIdResourceName();
        if (id != null && id.contains("/")) id = id.substring(id.indexOf('/') + 1);
        CharSequence d = n.getContentDescription();
        return (id == null ? "?" : id) + (d == null ? "" : " (" + d + ")");
    }
}
