package com.localorderai.services;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * TelephonyCallStateListener
 * ---------------------------
 * السبب: كنا معتمدين بالكامل على OrderInCallService (InCallService) عشان
 * نعرف إمتى المكالمة اتردت وإمتى خلصت، وده مش بيشتغل إلا لو التطبيق
 * معيّن فعليًا كـ "Default Phone App" من المستخدم — وده خطوة إضافية
 * مربكة وغير ضرورية.
 *
 * التطبيقات التجارية المشابهة (زي auto-dialer المنافس) بتستخدم
 * TelephonyManager مباشرة بدل الاعتماد الحصري على InCallService، وده
 * محتاج بس permission واحد (READ_PHONE_STATE) وموجود أصلًا عندنا في
 * المانيفست — ومبيحتاجش Default Dialer خالص.
 *
 * دلوقتي بقى هو المصدر الأساسي لمعرفة حالة المكالمة (رنّت / اترّدت /
 * خلصت) واللي بيشغّل الـ redial، و OrderInCallService بقى إضافي بس
 * (لو حد فعّل Default Dialer هيشتغل الاتنين مع بعض من غير تعارض،
 * لأن AutoRedialManager نفسه بيتعامل مع نداء onFinished مرتين بأمان).
 */
public class TelephonyCallStateListener {

    private static final String TAG = "TelephonyCallStateListener";

    public interface Callback {
        void onCallRinging();
        void onCallAnswered();
        void onCallEnded(boolean wasAnswered);
    }

    private final Context context;
    private final TelephonyManager telephonyManager;
    private final Executor executor = Executors.newSingleThreadExecutor();

    private PhoneStateListener legacyListener; // Android < 31
    private Object modernCallback; // TelephonyCallback، Android >= 31

    private volatile boolean wasOffHook = false;
    private volatile boolean wasAnswered = false;
    private Callback callback;

    public TelephonyCallStateListener(Context context) {
        this.context = context.getApplicationContext();
        this.telephonyManager = (TelephonyManager) this.context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    private boolean hasReadPhoneStatePermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void start() {
        if (telephonyManager == null) {
            Log.w(TAG, "TelephonyManager not available, skipping call state listening.");
            return;
        }
        if (!hasReadPhoneStatePermission()) {
            Log.w(TAG, "READ_PHONE_STATE permission missing, skipping call state listening.");
            return;
        }

        wasOffHook = false;
        wasAnswered = false;

        try {
            if (Build.VERSION.SDK_INT >= 31) {
                startModern();
            } else {
                startLegacy();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while registering call state listener", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register call state listener", e);
        }
    }

    public void stop() {
        if (telephonyManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && modernCallback != null) {
                telephonyManager.unregisterTelephonyCallback((TelephonyCallback) modernCallback);
                modernCallback = null;
            } else if (legacyListener != null) {
                telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_NONE);
                legacyListener = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to unregister call state listener", e);
        }
    }

    @RequiresApi(31)
    private void startModern() {
        TelephonyCallback.CallStateListener stateListener = this::handleCallState;
        TelephonyCallback cb = new ModernCallback(stateListener);
        modernCallback = cb;
        telephonyManager.registerTelephonyCallback(executor, cb);
    }

    @RequiresApi(31)
    private static class ModernCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        private final CallStateListener delegate;

        ModernCallback(CallStateListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onCallStateChanged(int state) {
            delegate.onCallStateChanged(state);
        }
    }

    private void startLegacy() {
        legacyListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                handleCallState(state);
            }
        };
        telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    private void handleCallState(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                if (callback != null) callback.onCallRinging();
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                // OFFHOOK بتتنادى لما المكالمة تترد أو تبقى شغالة فعليًا
                if (!wasOffHook) {
                    wasOffHook = true;
                    wasAnswered = true;
                    if (callback != null) callback.onCallAnswered();
                }
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                // IDLE بتتنادى لما المكالمة تخلص (اترفضت / اتقفلت / خلصت)
                if (wasOffHook) {
                    wasOffHook = false;
                    boolean answered = wasAnswered;
                    wasAnswered = false;
                    if (callback != null) callback.onCallEnded(answered);
                }
                break;

            default:
                break;
        }
    }
}
