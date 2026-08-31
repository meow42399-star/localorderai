package com.localorderai.services;

import com.localorderai.utils.AppLogger;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.localorderai.R;
import com.localorderai.data.AppConfig;
import com.localorderai.utils.CallRecordingConsentManager;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * TelephonyCallStateListener
 * ---------------------------
 * FIX: التطبيق شال خاصية "Default Dialer" خالص من كل الكود — مش
 * محتاجها ومش بيطلبها. الاتصال بيتم بالكامل عن طريق دايلر النظام
 * العادي (ACTION_CALL من AutoCallLauncherActivity)، ومتابعة حالة
 * المكالمة (رنّت / اترّدت / خلصت) بتتم هنا عن طريق TelephonyManager
 * مباشرة — محتاج بس READ_PHONE_STATE (موجود أصلًا في المانيفست).
 *
 * الكلاس ده بقى المصدر الوحيد لكل حاجة كانت معتمدة قبل كده على
 * OrderInCallService (اللي كانت InCallService حقيقية ومحتاجة الدور
 * ده صراحة عشان تشتغل)، وده شامل:
 *   1. تشغيل redial بعد ما المكالمة تخلص.
 *   2. ضبط وضع الصوت (سماعة / كتم مايك) لما المكالمة تترد.
 *   3. تشغيل إعلان الموافقة الصوتي قبل أي تسجيل (لو مفعّل).
 *
 * OrderInCallService اتشالت بالكامل من المشروع.
 */
public class TelephonyCallStateListener {

    private static final String TAG = "TelephonyCallStateListener";
    private static final int SPEAKER_DELAY_MS = 300; // نفس تأخير Android 11+ اللي كان في OrderInCallService

    public interface Callback {
        void onCallRinging();
        void onCallAnswered();
        void onCallEnded(boolean wasAnswered);
    }

    // FIX: static reference عشان أي مكان في التطبيق (زي زرار المايك/السماعة
    // في الأوفر) يقدر يطبّق تغيير صوتي فوري وقت المكالمة، بنفس فكرة
    // OrderInCallService.applyAudioSettingsLive() القديمة.
    private static TelephonyCallStateListener activeInstance;

    private final Context context;
    private final TelephonyManager telephonyManager;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AppConfig config;
    private final CallRecordingConsentManager consentManager;

    private PhoneStateListener legacyListener; // Android < 31
    private Object modernCallback; // TelephonyCallback، Android >= 31

    private volatile boolean wasOffHook = false;
    private volatile boolean wasAnswered = false;
    private Callback callback;

    public TelephonyCallStateListener(Context context) {
        this.context = context.getApplicationContext();
        this.telephonyManager = (TelephonyManager) this.context.getSystemService(Context.TELEPHONY_SERVICE);
        this.config = new AppConfig(this.context);
        this.consentManager = new CallRecordingConsentManager(this.context);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /** بيتنادى من الأوفر لحظيًا لو المستخدم غيّر المايك/السماعة أثناء مكالمة شغالة. */
    public static void applyAudioSettingsLive() {
        if (activeInstance != null) {
            activeInstance.setAudioModeForCall();
        }
    }

    private boolean hasReadPhoneStatePermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void start() {
        if (telephonyManager == null) {
            AppLogger.w(TAG, "TelephonyManager not available, skipping call state listening.");
            return;
        }
        if (!hasReadPhoneStatePermission()) {
            AppLogger.w(TAG, "READ_PHONE_STATE permission missing, skipping call state listening.");
            return;
        }

        wasOffHook = false;
        wasAnswered = false;
        activeInstance = this;

        try {
            if (Build.VERSION.SDK_INT >= 31) {
                startModern();
            } else {
                startLegacy();
            }
        } catch (SecurityException e) {
            AppLogger.e(TAG, "SecurityException while registering call state listener", e);
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to register call state listener", e);
        }
    }

    public void stop() {
        if (activeInstance == this) activeInstance = null;
        mainHandler.removeCallbacksAndMessages(null);
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
            AppLogger.e(TAG, "Failed to unregister call state listener", e);
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
                    onCallAnsweredInternal();
                    if (callback != null) callback.onCallAnswered();
                }
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                // IDLE بتتنادى لما المكالمة تخلص (اترفضت / اتقفلت / خلصت)
                if (wasOffHook) {
                    wasOffHook = false;
                    boolean answered = wasAnswered;
                    wasAnswered = false;
                    mainHandler.removeCallbacksAndMessages(null); // إلغاء أي تأخير سماعة معلّق
                    if (callback != null) callback.onCallEnded(answered);
                }
                break;

            default:
                break;
        }
    }

    /**
     * منقولة من OrderInCallService.onCallAnswered(): ضبط وضع الصوت
     * (سماعة/كتم مايك) بتأخير بسيط بعد ما المكالمة تترد، وتشغيل
     * إعلان الموافقة الصوتي قبل أي تسجيل لو مفعّل.
     */
    private void onCallAnsweredInternal() {
        mainHandler.postDelayed(this::setAudioModeForCall, SPEAKER_DELAY_MS);

        if (config == null || !config.isRecordingEnabled()) {
            return;
        }

        consentManager.playConsentAnnouncement(R.raw.consent_notice_ar,
                new CallRecordingConsentManager.ConsentCallback() {
                    @Override
                    public void onConsentAnnouncementFinished() {
                        Log.d(TAG, "Recording consent announcement finished.");
                        // TODO: تفعيل التسجيل الفعلي (MediaRecorder)
                    }

                    @Override
                    public void onError(String message) {
                        AppLogger.e(TAG, "Consent announcement failed, recording aborted: " + message);
                    }
                });
    }

    private void setAudioModeForCall() {
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return;

            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            boolean speakerOn = config != null && config.isSpeakerEnabled();
            audioManager.setSpeakerphoneOn(speakerOn);

            boolean micMuted = config != null && config.isMicMuted();
            audioManager.setMicrophoneMute(micMuted);

            Log.d(TAG, "Audio mode set, speaker=" + speakerOn + " micMuted=" + micMuted);
        } catch (Exception e) {
            AppLogger.e(TAG, "setAudioModeForCall failed", e);
        }
    }
}
