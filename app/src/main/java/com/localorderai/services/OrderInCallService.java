package com.localorderai.services;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

import com.localorderai.R;
import com.localorderai.data.AppConfig;
import com.localorderai.utils.AutoRedialManager;
import com.localorderai.utils.CallRecordingConsentManager;

/**
 * OrderInCallService
 * -------------------
 * FIX 1: كانت setAudioModeForCall() بتتنادى فورًا وقت STATE_ACTIVE —
 *         setSpeakerphoneOn() على Android 11+ محتاج تأخير صغير بعد ما
 *         الـ audio session تتأسس فعلاً (300ms كافية في الغالب).
 *
 * FIX 2: redialManager مكانش بيتحقن من حتة — تم إضافة static instance
 *         عشان CampaignForegroundService يقدر يوصّله.
 *
 * FIX 3: wasCallAnswered كانت بتتـ reset بعد onCallEnded بس — دلوقتي
 *         بتتـ reset صح في أول onCallAdded عشان كل مكالمة تبدأ نظيفة.
 */
public class OrderInCallService extends InCallService {

    private static final String TAG = "OrderInCallService";
    private static final int SPEAKER_DELAY_MS = 300; // FIX: تأخير السماعة على Android 11+

    // FIX: static reference عشان CampaignForegroundService يقدر يحقن الـ manager
    private static OrderInCallService instance;
    private static AutoRedialManager pendingRedialManager;

    private AutoRedialManager redialManager;
    private CallRecordingConsentManager consentManager;
    private AppConfig config;
    private boolean wasCallAnswered = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        consentManager = new CallRecordingConsentManager(this);
        config = new AppConfig(this);
        instance = this;

        // لو في manager كان استنى قبل ما الـ service تتأسس
        if (pendingRedialManager != null) {
            redialManager = pendingRedialManager;
            pendingRedialManager = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * FIX: الطريقة الجديدة للحقن — بتشتغل سواء الـ service اتأسست قبل أو بعد.
     * CampaignForegroundService بيناديها بدل setRedialManager() القديمة.
     */
    public static void injectRedialManager(AutoRedialManager manager) {
        if (instance != null) {
            instance.redialManager = manager;
        } else {
            // الـ service لسه مش اتأسست، هنحتفظ بيه
            pendingRedialManager = manager;
        }
    }

    /** للتوافق مع الكود القديم */
    public void setRedialManager(AutoRedialManager manager) {
        this.redialManager = manager;
    }

    /** بيتنادى من الأوفر لحظيًا لو المستخدم غيّر المايك/السماعة أثناء مكالمة شغالة */
    public static void applyAudioSettingsLive() {
        if (instance != null) {
            instance.setAudioModeForCall();
        }
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        wasCallAnswered = false; // FIX: reset لكل مكالمة جديدة

        call.registerCallback(new Call.Callback() {
            @Override
            public void onStateChanged(Call call, int state) {
                handleStateChange(call, state);
            }
        });
    }

    private void handleStateChange(Call call, int state) {
        switch (state) {
            case Call.STATE_ACTIVE:
                onCallAnswered(call);
                break;
            case Call.STATE_DISCONNECTED:
                onCallEnded(call);
                break;
            default:
                break;
        }
    }

    private void onCallAnswered(Call call) {
        wasCallAnswered = true;

        // FIX: تأخير تفعيل السماعة 300ms عشان الـ audio session تتأسس أول
        handler.postDelayed(this::setAudioModeForCall, SPEAKER_DELAY_MS);

        if (config == null || !config.isRecordingEnabled()) {
            return;
        }

        consentManager.playConsentAnnouncement(R.raw.consent_notice_ar,
                new CallRecordingConsentManager.ConsentCallback() {
                    @Override
                    public void onConsentAnnouncementFinished() {
                        startActualRecording(call);
                    }

                    @Override
                    public void onError(String message) {
                        Log.e(TAG, "Consent announcement failed, recording aborted: " + message);
                    }
                });
    }

    private void startActualRecording(Call call) {
        Log.d(TAG, "Recording started after consent notice.");
        // TODO: تفعيل التسجيل الفعلي (MediaRecorder)
    }

    private void onCallEnded(Call call) {
        handler.removeCallbacksAndMessages(null); // إلغاء أي تأخيرات معلّقة

        // TODO: stopRecording();

        if (redialManager != null) {
            redialManager.scheduleNextAttemptIfNeeded(wasCallAnswered);
        }
        wasCallAnswered = false;
    }

    private void setAudioModeForCall() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager == null) return;

            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            boolean speakerOn = config != null && config.isSpeakerEnabled();
            audioManager.setSpeakerphoneOn(speakerOn);

            boolean micMuted = config != null && config.isMicMuted();
            audioManager.setMicrophoneMute(micMuted);

            Log.d(TAG, "Audio mode set, speaker=" + speakerOn + " micMuted=" + micMuted);
        } catch (Exception e) {
            Log.e(TAG, "setAudioModeForCall failed", e);
        }
    }
}
