package com.localorderai;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.DisconnectCause;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** طابور الاتصال التلقائي. كله بيشتغل على الـ main thread. */
public final class CallQueue {

    public interface Listener {
        void onQueueChanged();
    }

    private static final CallQueue INSTANCE = new CallQueue();

    public static CallQueue get() {
        return INSTANCE;
    }

    private static final long NO_CALL_TIMEOUT_MS = 15000;
    private static final long GAP_MS = 2500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> numbers = new ArrayList<>();
    private final List<String> results = new ArrayList<>();

    private Context app;
    private boolean running;
    private int index = -1;
    private boolean waitingForCall;
    private boolean answered;
    private boolean timedOut;
    private Call currentCall;
    private int ringTimeoutSec = 45;
    private boolean autoSpeaker = true;

    // الوضع البسيط (من غير Default Dialer)
    private boolean basicMode;
    private boolean inCall;
    private long callStartMs;
    private Object watcher;

    private final Runnable nextRunnable = new Runnable() {
        @Override
        public void run() {
            next();
        }
    };

    private final Runnable noCallRunnable = new Runnable() {
        @Override
        public void run() {
            if (!waitingForCall) return;
            waitingForCall = false;
            record("فشل: المكالمة ما بدأتش");
            scheduleNext();
        }
    };

    private final Runnable ringTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentCall != null && !answered) {
                timedOut = true;
                currentCall.disconnect();
            }
        }
    };

    private final Runnable speakerOnRunnable = new Runnable() {
        @Override
        public void run() {
            if (app != null) AudioControl.setSpeaker(app, true);
        }
    };

    private final Call.Callback callback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            if (call != currentCall) return;
            if (state == Call.STATE_ACTIVE) {
                onActive();
            } else if (state == Call.STATE_DISCONNECTED) {
                onDisconnected(call);
            }
        }
    };

    private CallQueue() {
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        for (Listener l : listeners) l.onQueueChanged();
    }

    public boolean isRunning() {
        return running;
    }

    public int getCurrentPosition() {
        return Math.max(0, index + 1);
    }

    public int getTotal() {
        return numbers.size();
    }

    public List<String> getResults() {
        return new ArrayList<>(results);
    }

    public void start(Context ctx, List<String> nums, int ringSec, boolean speaker, boolean basic) {
        if (running) return;
        app = ctx.getApplicationContext();
        numbers.clear();
        numbers.addAll(nums);
        results.clear();
        index = -1;
        ringTimeoutSec = Math.max(10, ringSec);
        autoSpeaker = speaker;
        basicMode = basic;
        inCall = false;
        running = true;
        if (basicMode) startWatcher();
        next();
    }

    public void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(nextRunnable);
        handler.removeCallbacks(noCallRunnable);
        if (basicMode) {
            if (inCall) {
                inCall = false;
                record("اتقفلت (إيقاف الطابور)");
            }
            endCallQuietly();
            finish();
        } else if (currentCall != null) {
            currentCall.disconnect();
        } else {
            DialerInCallService s = DialerInCallService.instance;
            if (s != null) {
                for (Call c : s.getCalls()) c.disconnect();
            }
            finish();
        }
        notifyChanged();
    }

    private void next() {
        index++;
        if (!running || index >= numbers.size()) {
            finish();
            return;
        }
        String n = numbers.get(index);
        currentCall = null;
        answered = false;
        timedOut = false;
        waitingForCall = true;
        notifyChanged();
        try {
            TelecomManager tm = (TelecomManager) app.getSystemService(Context.TELECOM_SERVICE);
            tm.placeCall(Uri.fromParts("tel", n, null), new Bundle());
        } catch (SecurityException e) {
            waitingForCall = false;
            record("فشل: مفيش صلاحية اتصال");
            scheduleNext();
            return;
        }
        handler.postDelayed(noCallRunnable, NO_CALL_TIMEOUT_MS);
    }

    /** بتتنادى من DialerInCallService لما أي مكالمة تضاف. */
    public void onCallAdded(Call call) {
        if (!running || !waitingForCall) return;
        if (call.getState() == Call.STATE_RINGING) return; // مكالمة واردة مش بتاعتنا
        waitingForCall = false;
        handler.removeCallbacks(noCallRunnable);
        currentCall = call;
        call.registerCallback(callback);
        int st = call.getState();
        if (st == Call.STATE_DISCONNECTED) {
            onDisconnected(call);
        } else if (st == Call.STATE_ACTIVE) {
            onActive();
        } else {
            handler.postDelayed(ringTimeoutRunnable, ringTimeoutSec * 1000L);
        }
    }

    private void onActive() {
        answered = true;
        handler.removeCallbacks(ringTimeoutRunnable);
        notifyChanged();
        if (autoSpeaker) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    DialerInCallService s = DialerInCallService.instance;
                    if (s != null) s.setAudioRoute(CallAudioState.ROUTE_SPEAKER);
                }
            }, 400);
        }
    }

    private void onDisconnected(Call call) {
        if (call != currentCall) return;
        DisconnectCause dc = call.getDetails().getDisconnectCause();
        record(describe(dc));
        call.unregisterCallback(callback);
        handler.removeCallbacks(ringTimeoutRunnable);
        currentCall = null;
        answered = false;
        timedOut = false;
        scheduleNext();
    }

    private String describe(DisconnectCause dc) {
        int code = dc == null ? DisconnectCause.UNKNOWN : dc.getCode();
        if (answered) {
            if (code == DisconnectCause.LOCAL) return "أنا قفلت";
            if (code == DisconnectCause.REMOTE) return "هو قفل";
            return "انتهت المكالمة";
        }
        if (timedOut) return "مفيش رد (انتهت مهلة الرنين)";
        switch (code) {
            case DisconnectCause.BUSY:
                return "مشغول";
            case DisconnectCause.REJECTED:
                return "رفض";
            case DisconnectCause.MISSED:
                return "مفيش رد";
            case DisconnectCause.REMOTE:
                return "هو قفل / مفيش رد";
            case DisconnectCause.LOCAL:
            case DisconnectCause.CANCELED:
                return "أنا قفلت قبل الرد";
            case DisconnectCause.ERROR:
                return "فشل الاتصال";
            default:
                return "انتهت بدون رد";
        }
    }

    private void record(String text) {
        if (index < 0 || index >= numbers.size()) return;
        results.add((index + 1) + ". " + numbers.get(index) + " — " + text);
        notifyChanged();
    }

    private void scheduleNext() {
        if (!running || index + 1 >= numbers.size()) {
            finish();
            return;
        }
        handler.postDelayed(nextRunnable, GAP_MS);
    }

    private void finish() {
        running = false;
        waitingForCall = false;
        handler.removeCallbacks(nextRunnable);
        handler.removeCallbacks(noCallRunnable);
        handler.removeCallbacks(ringTimeoutRunnable);
        handler.removeCallbacks(speakerOnRunnable);
        if (basicMode) {
            stopWatcher();
            inCall = false;
            if (app != null) AudioControl.reset(app);
        }
        if (app != null) {
            app.stopService(new Intent(app, QueueService.class));
        }
        notifyChanged();
    }

    // ---------------- الوضع البسيط ----------------

    private void startWatcher() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                watcher = StateCallback31.register(app, this);
            } else {
                TelephonyManager tm = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
                PhoneStateListener l = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String number) {
                        onPhoneState(state);
                    }
                };
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE);
                watcher = l;
            }
        } catch (SecurityException e) {
            watcher = null;
        }
    }

    private void stopWatcher() {
        if (watcher == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                StateCallback31.unregister(app, watcher);
            } else {
                TelephonyManager tm = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
                tm.listen((PhoneStateListener) watcher, PhoneStateListener.LISTEN_NONE);
            }
        } catch (Exception ignored) {
        }
        watcher = null;
    }

    void onPhoneState(int state) {
        if (!running || !basicMode) return;
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            if (waitingForCall) {
                waitingForCall = false;
                handler.removeCallbacks(noCallRunnable);
                inCall = true;
                callStartMs = SystemClock.elapsedRealtime();
                notifyChanged();
                if (autoSpeaker) {
                    // الصوت بيتظبط لما المكالمة تتوصّل، فبنحاول كذا مرة
                    handler.postDelayed(speakerOnRunnable, 800);
                    handler.postDelayed(speakerOnRunnable, 3000);
                    handler.postDelayed(speakerOnRunnable, 7000);
                }
            }
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            if (inCall) {
                inCall = false;
                long secs = (SystemClock.elapsedRealtime() - callStartMs) / 1000;
                handler.removeCallbacks(speakerOnRunnable);
                record("انتهت — المدة " + secs + " ثانية");
                if (app != null) AudioControl.reset(app);
                scheduleNext();
            }
        }
    }

    private void endCallQuietly() {
        try {
            TelecomManager tm = (TelecomManager) app.getSystemService(Context.TELECOM_SERVICE);
            tm.endCall();
        } catch (SecurityException ignored) {
        }
    }
}
