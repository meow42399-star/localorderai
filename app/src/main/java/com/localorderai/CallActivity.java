package com.localorderai;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * شاشة المكالمة (Default Dialer) بتصميم قريب من One UI، مكتوبة من الصفر وكلها Views بالكود
 * عشان سهل التعديل. الأيقونات حالياً من android.R.drawable (مؤقتة) وتتبدل بـ vector لاحقاً.
 *
 * - خلفية متدرجة، "Calling..." فوق والرقم كبير.
 * - جارية: كارت شفاف فيه ٦ أزرار + دايرة إنهاء حمرا.
 * - واردة: دايرة رد خضرا ودايرة رفض حمرا بحلقة شفافة.
 * - زرار إيقاف الطابور بيظهر كـ pill برتقالي فوق الكارت لما الطابور شغال.
 *
 * TODO: إضافة مكالمة / فيديو / كيباد (DTMF) / Send message لسه مش متوصلين (الأيقونات مؤقتة).
 */
public class CallActivity extends Activity {

    private static final int INK = 0xFF3C3C43;
    private static final int RED = 0xFFE5393B;
    private static final int GREEN = 0xFF1FA35A;

    private TextView statusView, numberView, queueView, queuePill;
    private GridItem addBtn, videoBtn, btBtn, speakerBtn, muteBtn, keypadBtn;
    private View activeCard, incomingBox, hangupBtn, answerBtn, rejectBtn;
    private Call call;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ticker.postDelayed(this, 1000);
        }
    };

    private final Runnable uiListener = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    private final CallQueue.Listener queueListener = new CallQueue.Listener() {
        @Override
        public void onQueueChanged() {
            refresh();
        }
    };

    private final Call.Callback callCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call c, int state) {
            refresh();
        }
    };

    /** زرار في الكارت: أيقونة + عنوان. enabled=false بيبان باهت زي "Add call" في One UI. */
    private final class GridItem {
        final LinearLayout box;
        final FrameLayout holder;
        final ImageView icon;

        GridItem(int iconRes, String label) {
            box = new LinearLayout(CallActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);

            holder = new FrameLayout(CallActivity.this);
            holder.setClickable(true);
            icon = new ImageView(CallActivity.this);
            icon.setImageResource(iconRes);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER);
            holder.addView(icon, ip);
            box.addView(holder, new LinearLayout.LayoutParams(dp(52), dp(52)));

            TextView t = new TextView(CallActivity.this);
            t.setText(label);
            t.setTextColor(INK);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(4), 0, 0);
            box.addView(t);
            set(true, false);
        }

        void set(boolean enabled, boolean on) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(on ? 0xFF2B2B33 : Color.TRANSPARENT);
            holder.setBackground(d);
            icon.setColorFilter(on ? Color.WHITE : INK, PorterDuff.Mode.SRC_IN);
            box.setAlpha(enabled ? 1f : 0.4f);
        }

        void onClick(View.OnClickListener l) {
            holder.setOnClickListener(l);
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private View circle(int iconRes, int color, int sizeDp, float rotation, boolean ring) {
        FrameLayout f = new FrameLayout(this);
        f.setClickable(true);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        if (ring) d.setStroke(dp(3), 0x66FFFFFF);
        f.setBackground(d);
        ImageView i = new ImageView(this);
        i.setImageResource(iconRes);
        i.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        i.setRotation(rotation);
        f.addView(i, new FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return f;
    }

    private TextView label(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULL_SCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0B0A12, 0xFF1A1633, 0xFF37397A, 0xFF8C88B8});
        root.setBackground(bg);
        root.setPadding(dp(20), dp(56), dp(20), dp(28));

        statusView = label(14, 0xFFEDEDF2);
        statusView.setGravity(Gravity.CENTER);
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        numberView = label(32, Color.WHITE);
        numberView.setTypeface(Typeface.DEFAULT_BOLD);
        numberView.setMaxLines(2);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(36);
        root.addView(numberView, np);

        queueView = label(14, 0xFFC9CBDA);
        queueView.setPadding(0, dp(6), 0, 0);
        root.addView(queueView);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // pill إيقاف الطابور
        queuePill = label(14, Color.WHITE);
        queuePill.setText("إيقاف الطابور");
        queuePill.setGravity(Gravity.CENTER);
        queuePill.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable pd = new GradientDrawable();
        pd.setCornerRadius(dp(20));
        pd.setColor(0xFFEF6C00);
        queuePill.setBackground(pd);
        queuePill.setClickable(true);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.bottomMargin = dp(16);
        root.addView(queuePill, pp);

        // كارت المكالمة الجارية
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(20), dp(16), dp(20));
        GradientDrawable cd = new GradientDrawable();
        cd.setCornerRadius(dp(28));
        cd.setColor(0xE6DCDCE2);
        card.setBackground(cd);

        addBtn = new GridItem(android.R.drawable.ic_input_add, "إضافة مكالمة");
        videoBtn = new GridItem(android.R.drawable.ic_menu_camera, "فيديو");
        btBtn = new GridItem(android.R.drawable.stat_sys_data_bluetooth, "بلوتوث");
        speakerBtn = new GridItem(android.R.drawable.ic_lock_silent_mode_off, "المكبر");
        muteBtn = new GridItem(android.R.drawable.ic_btn_speak_now, "ميوت");
        keypadBtn = new GridItem(android.R.drawable.ic_dialog_dialer, "الكيباد");
        card.addView(row(addBtn, videoBtn, btBtn));
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2.topMargin = dp(16);
        card.addView(row(speakerBtn, muteBtn, keypadBtn), r2);

        hangupBtn = circle(android.R.drawable.ic_menu_call, RED, 60, 135f, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(60), dp(60));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(20);
        card.addView(hangupBtn, hp);
        activeCard = card;
        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // المكالمة الواردة
        LinearLayout inc = new LinearLayout(this);
        inc.setOrientation(LinearLayout.VERTICAL);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(dp(8), 0, dp(8), 0);
        answerBtn = circle(android.R.drawable.ic_menu_call, GREEN, 72, 0f, true);
        rejectBtn = circle(android.R.drawable.ic_menu_call, RED, 72, 135f, true);
        View gap = new View(this);
        btns.addView(answerBtn);
        btns.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        btns.addView(rejectBtn);
        inc.addView(btns);
        View line = new View(this);
        line.setBackgroundColor(0x99FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(110), dp(3));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(28);
        inc.addView(line, lp);
        TextView sm = label(13, 0xFFEDEDF2);
        sm.setText("Send message");
        sm.setGravity(Gravity.CENTER);
        sm.setPadding(0, dp(6), 0, 0);
        inc.addView(sm);
        incomingBox = inc;
        root.addView(inc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        answerBtn.setOnClickListener(v -> {
            if (call != null) call.answer(VideoProfile.STATE_AUDIO_ONLY);
        });
        rejectBtn.setOnClickListener(v -> {
            if (call != null) call.reject(false, null);
        });
        hangupBtn.setOnClickListener(v -> {
            if (call != null) call.disconnect();
        });
        queuePill.setOnClickListener(v -> CallQueue.get().stop());
        speakerBtn.onClick(v -> {
            DialerInCallService s = DialerInCallService.instance;
            if (s == null) return;
            CallAudioState as = s.getCallAudioState();
            boolean on = as != null && as.getRoute() == CallAudioState.ROUTE_SPEAKER;
            s.setAudioRoute(on ? CallAudioState.ROUTE_WIRED_OR_EARPIECE : CallAudioState.ROUTE_SPEAKER);
        });
        btBtn.onClick(v -> {
            DialerInCallService s = DialerInCallService.instance;
            if (s == null) return;
            CallAudioState as = s.getCallAudioState();
            if (as == null || (as.getSupportedRouteMask() & CallAudioState.ROUTE_BLUETOOTH) == 0) return;
            boolean on = as.getRoute() == CallAudioState.ROUTE_BLUETOOTH;
            s.setAudioRoute(on ? CallAudioState.ROUTE_WIRED_OR_EARPIECE : CallAudioState.ROUTE_BLUETOOTH);
        });
        muteBtn.onClick(v -> {
            DialerInCallService s = DialerInCallService.instance;
            if (s == null) return;
            CallAudioState as = s.getCallAudioState();
            s.setMuted(as == null || !as.isMuted());
        });
        // مؤقتاً: باهتين وبدون وظيفة
        addBtn.set(false, false);
        videoBtn.set(false, false);
        keypadBtn.set(false, false);
    }

    private LinearLayout row(GridItem a, GridItem b, GridItem c) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (GridItem g : new GridItem[]{a, b, c}) {
            r.addView(g.box, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }

    @Override
    protected void onStart() {
        super.onStart();
        DialerInCallService.UI_LISTENERS.add(uiListener);
        CallQueue.get().addListener(queueListener);
        refresh();
        ticker.postDelayed(tick, 1000);
    }

    @Override
    protected void onStop() {
        ticker.removeCallbacks(tick);
        DialerInCallService.UI_LISTENERS.remove(uiListener);
        CallQueue.get().removeListener(queueListener);
        if (call != null) {
            call.unregisterCallback(callCallback);
            call = null;
        }
        super.onStop();
    }

    private void refresh() {
        DialerInCallService s = DialerInCallService.instance;
        if (s == null) {
            finish();
            return;
        }
        List<Call> calls = s.getCalls();
        Call best = null;
        for (Call c : calls) {
            if (c.getState() == Call.STATE_RINGING) {
                best = c;
                break;
            }
            if (best == null) best = c;
        }
        if (best == null) {
            finish();
            return;
        }
        if (best != call) {
            if (call != null) call.unregisterCallback(callCallback);
            call = best;
            call.registerCallback(callCallback);
        }

        int st = call.getState();
        boolean ringing = st == Call.STATE_RINGING;
        String number = DialerInCallService.numberOf(call);
        CharSequence display = call.getDetails().getCallerDisplayName();
        numberView.setText(display != null && display.length() > 0 ? display : number);
        numberView.setGravity(ringing ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        statusView.setText(stateText(call, st));

        CallQueue q = CallQueue.get();
        queueView.setText(q.isRunning()
                ? "الطابور: رقم " + q.getCurrentPosition() + " من " + q.getTotal() : "");
        queueView.setGravity(ringing ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        queuePill.setVisibility(q.isRunning() ? View.VISIBLE : View.GONE);

        activeCard.setVisibility(ringing ? View.GONE : View.VISIBLE);
        incomingBox.setVisibility(ringing ? View.VISIBLE : View.GONE);

        CallAudioState as = s.getCallAudioState();
        speakerBtn.set(true, as != null && as.getRoute() == CallAudioState.ROUTE_SPEAKER);
        muteBtn.set(true, as != null && as.isMuted());
        boolean btAvail = as != null && (as.getSupportedRouteMask() & CallAudioState.ROUTE_BLUETOOTH) != 0;
        btBtn.set(btAvail, as != null && as.getRoute() == CallAudioState.ROUTE_BLUETOOTH);
    }

    /** المؤقت لو المكالمة شغالة، وغير كده اسم الحالة. */
    private String stateText(Call c, int st) {
        if (st == Call.STATE_ACTIVE) {
            long start = c.getDetails().getConnectTimeMillis();
            if (start > 0) {
                long sec = Math.max(0, (System.currentTimeMillis() - start) / 1000);
                long h = sec / 3600;
                long m = (sec % 3600) / 60;
                long r = sec % 60;
                return h > 0
                        ? String.format(Locale.US, "%d:%02d:%02d", h, m, r)
                        : String.format(Locale.US, "%02d:%02d", m, r);
            }
            return "المكالمة شغالة";
        }
        switch (st) {
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
                return "جاري الاتصال...";
            case Call.STATE_RINGING:
                return "مكالمة واردة";
            case Call.STATE_HOLDING:
                return "معلّقة";
            case Call.STATE_DISCONNECTING:
            case Call.STATE_DISCONNECTED:
                return "انتهت المكالمة";
            default:
                return "...";
        }
    }
}
