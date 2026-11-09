package com.localorderai;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Pattern;

/**
 * بيدوس على زر المكبر في شاشة المكالمة بتاعة تطبيق الهاتف (من غير Default Dialer).
 * بيشتغل بس أثناء مكالمة من الطابور، وبيدوّر على زر المكبر بس.
 */
public class SpeakerAccessibilityService extends AccessibilityService {

    static volatile SpeakerAccessibilityService instance;
    /** آخر حاجة الخدمة شافتها (للتشخيص، بتظهر في الشاشة الرئيسية). */
    static volatile String lastInfo = "";

    private static volatile boolean active;
    private static volatile boolean want;
    private static int attempts;
    private static long lastClickMs;

    private static final Pattern SPEAKER = Pattern.compile(
            "speaker|loudspeaker|مكبر|مكبّر|haut-parleur|altavoz|lautsprecher|alto-falante|hoparl",
            Pattern.CASE_INSENSITIVE);
    private static final String[] CALL_UI_HINTS = {"incallui", "dialer", "telecom", "phone", "contacts", "call"};

    static boolean isConnected() {
        return instance != null;
    }

    static void beginCall(boolean wantSpeaker) {
        want = wantSpeaker;
        active = wantSpeaker;
        attempts = 0;
        lastClickMs = 0;
        lastInfo = wantSpeaker ? "بدأت مكالمة، بدوّر على زر المكبر..." : "";
    }

    static void endCall() {
        active = false;
    }

    static void nudge() {
        SpeakerAccessibilityService s = instance;
        if (s != null && active) s.scan();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        lastInfo = "الخدمة شغالة";
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
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
        if (!active) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence p = root.getPackageName();
        String pkg = p == null ? "" : p.toString();
        if (pkg.equals(getPackageName())) return;
        if (!looksLikeCallUi(pkg)) {
            lastInfo = "الشاشة الحالية: " + pkg + " (مش شاشة مكالمة)";
            return;
        }
        AccessibilityNodeInfo n = find(root, 0);
        if (n == null) {
            lastInfo = "شاشة المكالمة: " + pkg + " — مش لاقي زر المكبر";
            return;
        }
        Boolean state = stateOf(n);
        lastInfo = "شاشة المكالمة: " + pkg + " — زر المكبر: " + describe(n)
                + " — حالته: " + (state == null ? "مجهولة" : (state ? "شغال" : "مقفول"));
        if (state != null && state == want) {
            active = false;
            lastInfo += " ✔";
            return;
        }
        int max = state == null ? 1 : 3;
        if (attempts >= max) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastClickMs < 1200) return;
        AccessibilityNodeInfo target = clickableOf(n);
        if (target == null) return;
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        attempts++;
        lastClickMs = now;
        lastInfo += " — دست عليه";
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
        AccessibilityNodeInfo[] cands = c == null ? new AccessibilityNodeInfo[]{n} : new AccessibilityNodeInfo[]{n, c};
        for (AccessibilityNodeInfo x : cands) {
            if (x.isCheckable()) return x.isChecked();
            if (x.isSelected()) return true;
            if (Build.VERSION.SDK_INT >= 30) {
                CharSequence sd = x.getStateDescription();
                if (sd != null) {
                    String t = sd.toString().toLowerCase();
                    if (t.contains("off") || t.contains("مقفل") || t.contains("متوقف") || t.contains("not")) return false;
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
