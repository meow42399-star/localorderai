package com.localorderai.services;

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
            Log.e(TAG, "Failed to add overlay bubble", e);
            stopSelf();
            return;
        }

        try {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                    progressReceiver, new IntentFilter(CampaignProgressDialog.ACTION_PROGRESS));
        } catch (Exception e) {
            Log.e(TAG, "Failed to register progress receiver", e);
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
                OrderInCallService.applyAudioSettingsLive();
            });
        }

        if (btnBubbleSpeaker != null) {
            btnBubbleSpeaker.setOnClickListener(v -> {
                boolean newVal = !config.isSpeakerEnabled();
                config.setSpeakerEnabled(newVal);
                applySpeakerButtonState();
                OrderInCallService.applyAudioSettingsLive();
            });
        }

        if (btnBubbleMore != null) {
            btnBubbleMore.setOnClickListener(v -> togglePanel());
        }

        try {
            windowManager.addView(bubbleView, bubbleParams);
        } catch (Exception e) {
            Log.e(TAG, "windowManager.addView failed - permission likely missing", e);
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
            Log.e(TAG, "Failed to show overlay panel", e);
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

        // ملحوظة: زرار المايك في البانل ده خاص بتفعيل *تسجيل* المكالمة
        // (بموافقة صوتية)، مش كتم المايك — ده متاح من الصندوق الرئيسي.
        if (btnToggleRecording != null) btnToggleRecording.setSelected(config.isRecordingEnabled());
        if (btnToggleSpeaker != null) btnToggleSpeaker.setSelected(config.isSpeakerEnabled());
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
                OrderInCallService.applyAudioSettingsLive();
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
    }

    private void changeDelay(int delta) {
        int newVal = Math.max(1, Math.min(config.getDelaySeconds() + delta, 999));
        config.setDelaySeconds(newVal);
        if (seekOverlayDelay != null) seekOverlayDelay.setProgress(newVal);
        updateDelayLabel(newVal);
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
        String current = intent.getStringExtra(CampaignProgressDialog.EXTRA_CURRENT_NAME);
        String currentPhone = intent.getStringExtra(CampaignProgressDialog.EXTRA_LAST_PHONE);
        int attempts = intent.getIntExtra(CampaignProgressDialog.EXTRA_AUTODIAL_ATTEMPTS, lastAutoDialAttempts);
        int maxAttempts = intent.getIntExtra(CampaignProgressDialog.EXTRA_AUTODIAL_MAX, lastAutoDialMax);

        // broadcastCurrentName() بيبعت اسم بس من غير أي إكسترا تانية،
        // وده بالظبط لحظة بداية عميل جديد في الطابور — فبنصفر عداد
        // المحاولات القديم في اللحظة دي تحديدًا بدل ما يفضل عارض آخر
        // رقم محاولة كان للعميل اللي فات.
        boolean isNewClientStart = current != null && currentPhone == null
                && !intent.hasExtra(CampaignProgressDialog.EXTRA_AUTODIAL_ATTEMPTS);

        if (current != null) lastCurrentName = current;
        if (currentPhone != null) lastCurrentPhone = currentPhone;

        if (isNewClientStart) {
            lastAutoDialAttempts = 0;
            lastAutoDialMax = 0;
            lastCurrentPhone = null;
        } else {
            lastAutoDialAttempts = attempts;
            lastAutoDialMax = maxAttempts;
        }

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
