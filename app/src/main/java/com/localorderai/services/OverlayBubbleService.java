package com.localorderai.services;

// ACTION_LIVE_SETTINGS_CHANGED: بيتبعت لما المستخدم يغيّر أقصى عدد
// محاولات أو التأخير من الـ overlay panel وقت ما الحملة شغالة، عشان
// CampaignForegroundService يقدر يحدّث الـ AutoRedialManager الشغال
// حاليًا فورًا، مش بس الأرقام الجاية.

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.button.MaterialButton;
import com.localorderai.R;
import com.localorderai.data.AppConfig;
import com.localorderai.ui.CampaignProgressDialog;
import com.localorderai.utils.AppLogger;

/**
 * OverlayBubbleService
 * ----------------------
 * صندوق معلومات صغير قابل للسحب يفضل ظاهر فوق كل التطبيقات طول ما
 * الحملة شغالة. بيعرض اسم العميل الحالي، رقم الهاتف، رقم المحاولة،
 * وعداد التقدم — كل ده بخط أكبر وواضح. وفيه زرارين سريعين: كتم
 * المايك والسماعة، وزرار "المزيد" لفتح بانل تحكم كامل فيه إعدادات
 * المحاولات والفاصل الزمني وإيقاف الحملة.
 *
 * محتاجة إذن SYSTEM_ALERT_WINDOW اللي المستخدم لازم يفعّله يدويًا
 * من إعدادات النظام (Display over other apps).
 */
public class OverlayBubbleService extends Service {

    private static final String TAG = "OverlayBubbleService";

    public static final String ACTION_LIVE_SETTINGS_CHANGED = "com.localorderai.LIVE_SETTINGS_CHANGED";
    public static final String EXTRA_MAX_ATTEMPTS = "extra_max_attempts";
    public static final String EXTRA_DELAY_SECONDS = "extra_delay_seconds";
    public static final String EXTRA_UNTIL_ANSWERED = "extra_until_answered";

    /**
     * بتتبعت لما الأوفر تفشل ترسم نفسها فعليًا (إذن ملغي، أو قيد خاص
     * بجهاز Samsung/OEM معين). CampaignForegroundService بيسمعها
     * ويوقف الحملة فورًا بدل ما يكمل ويحاول يفتح مكالمات هيترفضها
     * النظام بصمت — لأن استثناء بدء الأنشطة من الخلفية بيعتمد على
     * وجود overlay view فعلي على الشاشة، مش بس على الإذن الممنوح.
     */
    public static final String ACTION_BUBBLE_FAILED = "com.localorderai.BUBBLE_FAILED";

    private void broadcastLiveSettingsChanged(Integer maxAttempts, Integer delaySeconds) {
        broadcastLiveSettingsChanged(maxAttempts, delaySeconds, null);
    }

    private void broadcastLiveSettingsChanged(Integer maxAttempts, Integer delaySeconds, Boolean untilAnswered) {
        Intent b = new Intent(ACTION_LIVE_SETTINGS_CHANGED);
        if (maxAttempts != null) b.putExtra(EXTRA_MAX_ATTEMPTS, (int) maxAttempts);
        if (delaySeconds != null) b.putExtra(EXTRA_DELAY_SECONDS, (int) delaySeconds);
        if (untilAnswered != null) b.putExtra(EXTRA_UNTIL_ANSWERED, (boolean) untilAnswered);
        LocalBroadcastManager.getInstance(this).sendBroadcast(b);
    }

    private WindowManager windowManager;
    private AppConfig config;

    private View bubbleView;
    private WindowManager.LayoutParams bubbleParams;

    private View panelView;
    private WindowManager.LayoutParams panelParams;
    private boolean isPanelShowing = false;

    private TextView tvBubbleCounter;
    private TextView tvBubbleName, tvBubblePhone, tvBubbleAttempt;
    private ImageView btnBubbleMic, btnBubbleSpeaker;
    private TextView btnBubbleMore;

    private TextView tvOverlayCounter, tvOverlayCurrent;
    private TextView tvOverlayAttemptsLabel, tvOverlayDelayLabel;
    private SeekBar seekOverlayAttempts, seekOverlayDelay;
    private androidx.appcompat.widget.SwitchCompat switchUntilAnswered;
    private TextView btnToggleRecording, btnToggleSpeaker;

    private int lastTotal = 0, lastProcessed = 0;
    private String lastCurrentName = null, lastCurrentPhone = null;
    private int lastAutoDialAttempts = 0, lastAutoDialMax = 0;

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!CampaignProgressDialog.ACTION_PROGRESS.equals(intent.getAction())) return;
            updateFromIntent(intent);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        config = new AppConfig(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        try {
            addBubble();
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to add overlay bubble", e);
            try {
                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_BUBBLE_FAILED));
            } catch (Exception ignored) {}
            stopSelf();
            return;
        }

        try {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                    progressReceiver, new IntentFilter(CampaignProgressDialog.ACTION_PROGRESS));
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to register progress receiver", e);
        }

        // نقرا آخر حالة معروفة للحملة فورًا من AppConfig بدل ما ننتظر
        // broadcast — ده بيحل مشكلة ظهور الأوفر فاضي (0 و -) لو حصل
        // إن الحملة بدأت وبعتت أول تحديث قبل ما الأوفر يخلص تسجيل
        // المستقبِل بتاعه.
        loadPersistedState();
    }

    private void loadPersistedState() {
        lastTotal = config.getStateTotal();
        lastProcessed = config.getStateProcessed();
        lastCurrentName = config.getStateCurrentName();
        lastCurrentPhone = config.getStateCurrentPhone();
        lastAutoDialAttempts = config.getStateAttempt();
        lastAutoDialMax = config.getStateAttemptMax();
        refreshBubbleViews();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    // ---------- Bubble ----------

    private void addBubble() {
        // فحص صريح للإذن قبل أي محاولة رسم — الفحص في DashboardFragment
        // بيحصل وقت الضغط على "بدء الحملة"، لكن ممكن يمر وقت (تأخير
        // النظام، أو المستخدم يلغي الإذن بعد الضغط مباشرة) قبل ما
        // onCreate() هنا يتنفذ فعليًا. من غير الفحص المباشر ده،
        // windowManager.addView() كان بيرمي SecurityException بصمت
        // وتقفل الخدمة كلها من غير أي رسالة توصل للمستخدم.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            throw new SecurityException("SYSTEM_ALERT_WINDOW permission not granted or revoked");
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null);
        tvBubbleCounter = bubbleView.findViewById(R.id.tvBubbleCounter);
        tvBubbleName = bubbleView.findViewById(R.id.tvBubbleName);
        tvBubblePhone = bubbleView.findViewById(R.id.tvBubblePhone);
        tvBubbleAttempt = bubbleView.findViewById(R.id.tvBubbleAttempt);
        btnBubbleMic = bubbleView.findViewById(R.id.btnBubbleMic);
        btnBubbleSpeaker = bubbleView.findViewById(R.id.btnBubbleSpeaker);
        btnBubbleMore = bubbleView.findViewById(R.id.btnBubbleMore);

        // بنقرا آخر حالة محفوظة للمايك والسماعة زي ما هي (آخر فعل
        // عمله المستخدم)، من غير ما نجبرها على قيمة افتراضية.
        applyMicButtonState();
        applySpeakerButtonState();

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 0;
        bubbleParams.y = 300;

        // السحب بيشتغل على الصندوق كله، بس بنسيب الأزرار (مايك/سماعة/المزيد)
        // تاخد الضغطة بتاعتها هي لوحدها من غير ما تتعارض مع السحب.
        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private static final int CLICK_THRESHOLD = 8;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = bubbleParams.x;
                        initialY = bubbleParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return false; // سيب الأزرار الداخلية تاخد الحدث لو الضغطة عليها

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > CLICK_THRESHOLD || Math.abs(dy) > CLICK_THRESHOLD) {
                            bubbleParams.x = initialX + dx;
                            bubbleParams.y = initialY + dy;
                            try {
                                windowManager.updateViewLayout(bubbleView, bubbleParams);
                            } catch (Exception ignored) {}
                            return true;
                        }
                        return false;
                }
                return false;
            }
        });

        if (btnBubbleMic != null) {
            btnBubbleMic.setOnClickListener(v -> {
                boolean newMuted = !config.isMicMuted();
                config.setMicMuted(newMuted);
                applyMicButtonState();
                TelephonyCallStateListener.applyAudioSettingsLive();
            });
        }

        if (btnBubbleSpeaker != null) {
            btnBubbleSpeaker.setOnClickListener(v -> {
                boolean newVal = !config.isSpeakerEnabled();
                config.setSpeakerEnabled(newVal);
                applySpeakerButtonState();
                TelephonyCallStateListener.applyAudioSettingsLive();
            });
        }

        if (btnBubbleMore != null) {
            btnBubbleMore.setOnClickListener(v -> togglePanel());
        }

        try {
            windowManager.addView(bubbleView, bubbleParams);
        } catch (Exception e) {
            AppLogger.e(TAG, "windowManager.addView failed - permission likely missing", e);
            throw e;
        }
    }

    private void applyMicButtonState() {
        if (btnBubbleMic == null) return;
        boolean muted = config.isMicMuted();
        btnBubbleMic.setSelected(muted);
        btnBubbleMic.setImageResource(muted ? R.drawable.ic_mic_muted : R.drawable.ic_mic);
    }

    private void applySpeakerButtonState() {
        if (btnBubbleSpeaker == null) return;
        boolean speakerOn = config.isSpeakerEnabled();
        btnBubbleSpeaker.setSelected(speakerOn);
        btnBubbleSpeaker.setImageResource(speakerOn ? R.drawable.ic_speaker : R.drawable.ic_speaker_off);
    }

    // ---------- Panel ----------

    private void togglePanel() {
        if (isPanelShowing) {
            removePanel();
        } else {
            showPanel();
        }
    }

    private void showPanel() {
        if (panelView != null) return;

        try {
            LayoutInflater inflater = LayoutInflater.from(this);
            panelView = inflater.inflate(R.layout.overlay_panel, null);

            bindPanelViews();
            applyCurrentConfigToPanel();
            wireUpPanelListeners();

            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            panelParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            panelParams.gravity = Gravity.TOP | Gravity.START;
            panelParams.x = Math.max(0, bubbleParams.x - 40);
            panelParams.y = bubbleParams.y + 180;

            windowManager.addView(panelView, panelParams);
            isPanelShowing = true;
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to show overlay panel", e);
            panelView = null;
            isPanelShowing = false;
        }
    }

    private void removePanel() {
        if (panelView != null) {
            try {
                windowManager.removeView(panelView);
            } catch (Exception ignored) {}
            panelView = null;
        }
        isPanelShowing = false;
    }

    private void bindPanelViews() {
        tvOverlayCounter = panelView.findViewById(R.id.tvOverlayCounter);
        tvOverlayCurrent = panelView.findViewById(R.id.tvOverlayCurrent);
        tvOverlayAttemptsLabel = panelView.findViewById(R.id.tvOverlayAttemptsLabel);
        tvOverlayDelayLabel = panelView.findViewById(R.id.tvOverlayDelayLabel);
        seekOverlayAttempts = panelView.findViewById(R.id.seekOverlayAttempts);
        seekOverlayDelay = panelView.findViewById(R.id.seekOverlayDelay);
        switchUntilAnswered = panelView.findViewById(R.id.switchUntilAnswered);
        btnToggleRecording = panelView.findViewById(R.id.btnToggleMic);
        btnToggleSpeaker = panelView.findViewById(R.id.btnToggleSpeaker);
    }

    private void applyCurrentConfigToPanel() {
        if (tvOverlayCounter != null) tvOverlayCounter.setText(lastProcessed + " / " + lastTotal);

        int attempts = config.getMaxAttempts();
        int delay = config.getDelaySeconds();

        if (seekOverlayAttempts != null) seekOverlayAttempts.setProgress(attempts);
        if (seekOverlayDelay != null) seekOverlayDelay.setProgress(delay);
        updateAttemptsLabel(attempts);
        updateDelayLabel(delay);

        if (switchUntilAnswered != null) {
            switchUntilAnswered.setChecked(config.isUntilAnsweredMode());
        }
        applyAttemptsControlsEnabledState();

        // ملحوظة: زرار المايك في البانل ده خاص بتفعيل *تسجيل* المكالمة
        // (بموافقة صوتية)، مش كتم المايك — ده متاح من الصندوق الرئيسي.
        if (btnToggleRecording != null) btnToggleRecording.setSelected(config.isRecordingEnabled());
        if (btnToggleSpeaker != null) btnToggleSpeaker.setSelected(config.isSpeakerEnabled());
    }

    /**
     * لما وضع "لحد ما يرد" مفعّل، سلايدر أقصى عدد المحاولات بيتعطل
     * بصريًا (مش بيتشال) عشان يوضح للمستخدم إن القيمة دي مش بتتطبق
     * حاليًا، من غير ما نضطر نخفي الـ view ونغيّر شكل البانل.
     */
    private void applyAttemptsControlsEnabledState() {
        boolean untilAnswered = config.isUntilAnsweredMode();
        if (seekOverlayAttempts != null) seekOverlayAttempts.setEnabled(!untilAnswered);
        if (tvOverlayAttemptsLabel != null) {
            tvOverlayAttemptsLabel.setAlpha(untilAnswered ? 0.4f : 1f);
        }
    }

    private void wireUpPanelListeners() {
        View btnClose = panelView.findViewById(R.id.btnPanelClose);
        if (btnClose != null) btnClose.setOnClickListener(v -> removePanel());

        if (btnToggleRecording != null) {
            btnToggleRecording.setOnClickListener(v -> {
                boolean newVal = !config.isRecordingEnabled();
                config.setRecordingEnabled(newVal);
                btnToggleRecording.setSelected(newVal);
            });
        }

        if (btnToggleSpeaker != null) {
            btnToggleSpeaker.setOnClickListener(v -> {
                boolean newVal = !config.isSpeakerEnabled();
                config.setSpeakerEnabled(newVal);
                btnToggleSpeaker.setSelected(newVal);
                applySpeakerButtonState();
                TelephonyCallStateListener.applyAudioSettingsLive();
            });
        }

        if (switchUntilAnswered != null) {
            switchUntilAnswered.setOnCheckedChangeListener((buttonView, isChecked) -> {
                config.setRedialMode(isChecked
                        ? AppConfig.REDIAL_MODE_UNTIL_ANSWERED
                        : AppConfig.REDIAL_MODE_MAX_ATTEMPTS);
                applyAttemptsControlsEnabledState();
                broadcastLiveSettingsChanged(null, null, isChecked);
            });
        }

        View btnAttemptsMinus = panelView.findViewById(R.id.btnAttemptsMinus);
        View btnAttemptsPlus = panelView.findViewById(R.id.btnAttemptsPlus);
        if (btnAttemptsMinus != null) {
            btnAttemptsMinus.setOnClickListener(v -> changeAttempts(-1));
        }
        if (btnAttemptsPlus != null) {
            btnAttemptsPlus.setOnClickListener(v -> changeAttempts(1));
        }
        if (seekOverlayAttempts != null) {
            seekOverlayAttempts.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    int clamped = Math.max(1, Math.min(progress, 100));
                    config.setMaxAttempts(clamped);
                    updateAttemptsLabel(clamped);
                    broadcastLiveSettingsChanged(clamped, null);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        View btnDelayMinus = panelView.findViewById(R.id.btnDelayMinus);
        View btnDelayPlus = panelView.findViewById(R.id.btnDelayPlus);
        if (btnDelayMinus != null) {
            btnDelayMinus.setOnClickListener(v -> changeDelay(-10));
        }
        if (btnDelayPlus != null) {
            btnDelayPlus.setOnClickListener(v -> changeDelay(10));
        }
        if (seekOverlayDelay != null) {
            seekOverlayDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    int clamped = Math.max(1, Math.min(progress, 999));
                    config.setDelaySeconds(clamped);
                    updateDelayLabel(clamped);
                    broadcastLiveSettingsChanged(null, clamped);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        MaterialButton btnStop = panelView.findViewById(R.id.btnOverlayStopCampaign);
        if (btnStop != null) {
            btnStop.setOnClickListener(v -> {
                Intent stopService = new Intent(this, CampaignForegroundService.class);
                stopService.setAction("STOP");
                startService(stopService);
                removePanel();
                stopSelf();
            });
        }
    }

    private void changeAttempts(int delta) {
        int newVal = Math.max(1, Math.min(config.getMaxAttempts() + delta, 100));
        config.setMaxAttempts(newVal);
        if (seekOverlayAttempts != null) seekOverlayAttempts.setProgress(newVal);
        updateAttemptsLabel(newVal);
        broadcastLiveSettingsChanged(newVal, null);
    }

    private void changeDelay(int delta) {
        int newVal = Math.max(1, Math.min(config.getDelaySeconds() + delta, 999));
        config.setDelaySeconds(newVal);
        if (seekOverlayDelay != null) seekOverlayDelay.setProgress(newVal);
        updateDelayLabel(newVal);
        broadcastLiveSettingsChanged(null, newVal);
    }

    private void updateAttemptsLabel(int attempts) {
        if (tvOverlayAttemptsLabel != null) {
            tvOverlayAttemptsLabel.setText("أقصى عدد محاولات: " + attempts);
        }
    }

    private void updateDelayLabel(int delay) {
        if (tvOverlayDelayLabel != null) {
            tvOverlayDelayLabel.setText("الفاصل بين المحاولات: " + delay + " sec");
        }
    }

    // ---------- Broadcast updates ----------

    private void updateFromIntent(Intent intent) {
        lastTotal = intent.getIntExtra(CampaignProgressDialog.EXTRA_TOTAL, lastTotal);
        lastProcessed = intent.getIntExtra(CampaignProgressDialog.EXTRA_PROCESSED, lastProcessed);

        // FIX: كل broadcast دلوقتي بيحمل بيانات العميل الحالي كاملة
        // (اسم + رقم + محاولات + أقصى محاولات) من CampaignForegroundService،
        // فمبقاش محتاجين نخمّن "هل ده عميل جديد؟" ونصفّر حقول يدويًا —
        // ده بالظبط اللي كان بيسبب ظهور "-" في مكان الرقم وعداد
        // المحاولات مش بيتحدث صح. دلوقتي بس بناخد القيم زي ما هي.
        String current = intent.getStringExtra(CampaignProgressDialog.EXTRA_CURRENT_NAME);
        String currentPhone = intent.getStringExtra(CampaignProgressDialog.EXTRA_LAST_PHONE);

        if (current != null) lastCurrentName = current;
        if (currentPhone != null) lastCurrentPhone = currentPhone;

        lastAutoDialAttempts = intent.getIntExtra(CampaignProgressDialog.EXTRA_AUTODIAL_ATTEMPTS, lastAutoDialAttempts);
        lastAutoDialMax = intent.getIntExtra(CampaignProgressDialog.EXTRA_AUTODIAL_MAX, lastAutoDialMax);

        refreshBubbleViews();
    }

    /** بيحدث نصوص الصندوق والبانل بالقيم الحالية المخزنة في الحقول أعلاه. */
    private void refreshBubbleViews() {
        if (tvBubbleCounter != null) {
            tvBubbleCounter.setText(lastProcessed + " / " + lastTotal);
        }
        if (tvBubbleName != null) {
            tvBubbleName.setText(lastCurrentName != null ? lastCurrentName : "-");
        }
        if (tvBubblePhone != null) {
            tvBubblePhone.setText(lastCurrentPhone != null ? lastCurrentPhone : "-");
        }
        if (tvBubbleAttempt != null) {
            tvBubbleAttempt.setText("محاولة: "
                    + (lastAutoDialMax > 0 ? String.valueOf(lastAutoDialAttempts) : "-")
                    + " / " + (lastAutoDialMax > 0 ? String.valueOf(lastAutoDialMax) : "-"));
        }

        if (isPanelShowing) {
            if (tvOverlayCounter != null) tvOverlayCounter.setText(lastProcessed + " / " + lastTotal);
            if (tvOverlayCurrent != null && lastCurrentName != null) {
                tvOverlayCurrent.setText("العميل: " + lastCurrentName);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver);
        } catch (Exception ignored) {}

        removePanel();

        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Exception ignored) {}
            bubbleView = null;
        }
    }
}
