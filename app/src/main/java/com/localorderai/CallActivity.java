package com.localorderai;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class CallActivity extends Activity {

    private TextView numberView, stateView, queueView;
    private Button speakerBtn, muteBtn, hangupBtn, answerBtn, rejectBtn, stopQueueBtn;
    private Call call;

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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 120, 48, 48);
        root.setBackgroundColor(Color.WHITE);

        queueView = text(16, Color.GRAY);
        numberView = text(32, Color.BLACK);
        stateView = text(18, Color.DKGRAY);
        root.addView(queueView);
        root.addView(numberView);
        root.addView(stateView);

        answerBtn = button("رد", 0xFF2E7D32, Color.WHITE);
        rejectBtn = button("رفض", 0xFFC62828, Color.WHITE);
        speakerBtn = button("المكبر", 0xFFDDDDDD, Color.BLACK);
        muteBtn = button("ميوت", 0xFFDDDDDD, Color.BLACK);
        hangupBtn = button("إنهاء المكالمة", 0xFFC62828, Color.WHITE);
        stopQueueBtn = button("إيقاف الطابور", 0xFFEF6C00, Color.WHITE);
        root.addView(answerBtn);
        root.addView(rejectBtn);
        root.addView(speakerBtn);
        root.addView(muteBtn);
        root.addView(hangupBtn);
        root.addView(stopQueueBtn);
        setContentView(root);

        answerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (call != null) call.answer(VideoProfile.STATE_AUDIO_ONLY);
            }
        });
        rejectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (call != null) call.reject(false, null);
            }
        });
        hangupBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (call != null) call.disconnect();
            }
        });
        speakerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DialerInCallService s = DialerInCallService.instance;
                if (s == null) return;
                CallAudioState as = s.getCallAudioState();
                boolean on = as != null && as.getRoute() == CallAudioState.ROUTE_SPEAKER;
                s.setAudioRoute(on ? CallAudioState.ROUTE_WIRED_OR_EARPIECE : CallAudioState.ROUTE_SPEAKER);
            }
        });
        muteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DialerInCallService s = DialerInCallService.instance;
                if (s == null) return;
                CallAudioState as = s.getCallAudioState();
                s.setMuted(as == null || !as.isMuted());
            }
        });
        stopQueueBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallQueue.get().stop();
            }
        });
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 12, 0, 12);
        return t;
    }

    private Button button(String label, int bg, int fg) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setAllCaps(false);
        bt.setBackgroundColor(bg);
        bt.setTextColor(fg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 160);
        lp.setMargins(0, 16, 0, 16);
        bt.setLayoutParams(lp);
        return bt;
    }

    @Override
    protected void onStart() {
        super.onStart();
        DialerInCallService.UI_LISTENERS.add(uiListener);
        CallQueue.get().addListener(queueListener);
        refresh();
    }

    @Override
    protected void onStop() {
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
        numberView.setText(DialerInCallService.numberOf(call));
        stateView.setText(stateLabel(st));

        CallQueue q = CallQueue.get();
        if (q.isRunning()) {
            queueView.setText("الطابور: رقم " + q.getCurrentPosition() + " من " + q.getTotal());
        } else {
            queueView.setText("");
        }
        stopQueueBtn.setVisibility(q.isRunning() ? View.VISIBLE : View.GONE);

        answerBtn.setVisibility(ringing ? View.VISIBLE : View.GONE);
        rejectBtn.setVisibility(ringing ? View.VISIBLE : View.GONE);
        speakerBtn.setVisibility(ringing ? View.GONE : View.VISIBLE);
        muteBtn.setVisibility(ringing ? View.GONE : View.VISIBLE);
        hangupBtn.setVisibility(ringing ? View.GONE : View.VISIBLE);

        CallAudioState as = s.getCallAudioState();
        boolean speakerOn = as != null && as.getRoute() == CallAudioState.ROUTE_SPEAKER;
        boolean muted = as != null && as.isMuted();
        style(speakerBtn, speakerOn, "المكبر: شغال", "المكبر: مقفول");
        style(muteBtn, muted, "ميوت: شغال", "ميوت: مقفول");
    }

    private void style(Button b, boolean on, String onText, String offText) {
        b.setText(on ? onText : offText);
        b.setBackgroundColor(on ? 0xFF2E7D32 : 0xFFDDDDDD);
        b.setTextColor(on ? Color.WHITE : Color.BLACK);
    }

    private String stateLabel(int st) {
        switch (st) {
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
                return "جاري الاتصال...";
            case Call.STATE_RINGING:
                return "مكالمة واردة";
            case Call.STATE_ACTIVE:
                return "المكالمة شغالة";
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
