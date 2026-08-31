package com.localorderai.services;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.localorderai.R;
import com.localorderai.data.*;
import com.localorderai.utils.AutoRedialManager;
import com.localorderai.utils.AppLogger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CampaignForegroundService
 * -------------------------
 * تطبيق اتصال تلقائي بسيط: بياخد قائمة الطلبات المعلّقة، ويتصل بكل رقم
 * تلقائيًا (Auto Dial) واحد ورا التاني بالترتيب، من غير أي تكامل خارجي
 * (لا واتساب ولا Gemini).
 *
 * FIX: قبل كده كانت processQueue بتلف على كل الطلبات في for loop عادي
 * وبتنادي startAutoDialing() وترجع فورًا من غير ما تستنى، فكانت كل
 * أرقام القائمة بتتبعت لـ AutoRedialManager في نفس اللحظة تقريبًا،
 * وبعد أول محاولة اتصال كان الكامبين "يوقف" فعليًا لأن الـ loop
 * خلص من زمان — وده اللي كان بيخلي المستخدم مضطر يدوس "بدء الحملة"
 * تاني لكل رقم.
 *
 * دلوقتي: بنعالج رقم واحد بس في كل مرة، ومش بننتقل للرقم اللي بعده
 * إلا لما AutoRedialManager يبلغ onFinished() (يعني الرقم ده خلص
 * تمامًا: اتوصل، أو خلص أقصى عدد محاولات، أو اتوقف يدويًا).
 */
public class CampaignForegroundService extends Service {

    private static final String TAG = "CampaignService";
    private static final String CHANNEL_ID = "campaign_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AppDatabase db;
    private AppConfig config;

    private volatile boolean isCampaignRunning = false;
    private List<OrderRecord> queue;
    private int queueIndex = 0;
    private int queueTotal = 0;
    private String lastKnownCurrentName = null;
    private String lastKnownCurrentPhone = null;

    private AutoRedialManager currentRedial;

    // FIX: التطبيق شال خاصية Default Dialer خالص من كل الكود.
    // TelephonyCallStateListener هو المصدر الوحيد لمتابعة حالة المكالمة
    // (رنّت/اترّدت/خلصت) وتشغيل الـ redial، وبيعتمد على TelephonyManager
    // بس — مش محتاج أي دور خاص من النظام.
    private TelephonyCallStateListener telephonyListener;
    private boolean telephonyWasAnswered = false;

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            stopCampaign();
        }
    };

    // لو الأوفر فشلت ترسم نفسها فعليًا (إذن اتلغى، أو قيد OEM)، النظام
    // هيرفض بدء المكالمات من الخلفية بصمت تام لأي رقم جاي. بدل ما
    // نسيب الحملة "شغالة" اسميًا من غير أي رنين خالص، بنوقفها فورًا
    // ونوضح السبب للمستخدم.
    private final BroadcastReceiver bubbleFailedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            AppLogger.e(TAG, "Overlay bubble failed to render — stopping campaign, background calls would be silently blocked");
            mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                    "تعذّر عرض الفقاعة العائمة، فتم إيقاف الحملة. تأكد من تفعيل إذن \"الظهور فوق التطبيقات الأخرى\" وحاول تاني.",
                    Toast.LENGTH_LONG).show());
            stopCampaign();
        }
    };

    // بيستقبل تغييرات أقصى عدد محاولات / التأخير من الـ overlay panel
    // وقت ما فيه رقم شغال دلوقتي، وبيحدّث الـ AutoRedialManager الحالي
    // فورًا بدل ما التغيير يأثر بس على الأرقام الجاية.
    private final BroadcastReceiver liveSettingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (currentRedial == null) return;
            if (intent.hasExtra(OverlayBubbleService.EXTRA_MAX_ATTEMPTS)) {
                currentRedial.updateMaxAttempts(intent.getIntExtra(OverlayBubbleService.EXTRA_MAX_ATTEMPTS, currentRedial.getMaxAttempts()));
            }
            if (intent.hasExtra(OverlayBubbleService.EXTRA_DELAY_SECONDS)) {
                currentRedial.updateDelaySeconds(intent.getIntExtra(OverlayBubbleService.EXTRA_DELAY_SECONDS, config.getDelaySeconds()));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        db = AppDatabase.getInstance(this);
        config = new AppConfig(this);

        createNotificationChannel();

        LocalBroadcastManager.getInstance(this).registerReceiver(
                stopReceiver, new IntentFilter(com.localorderai.ui.CampaignProgressDialog.ACTION_STOP)
        );
        LocalBroadcastManager.getInstance(this).registerReceiver(
                liveSettingsReceiver, new IntentFilter(OverlayBubbleService.ACTION_LIVE_SETTINGS_CHANGED)
        );
        LocalBroadcastManager.getInstance(this).registerReceiver(
                bubbleFailedReceiver, new IntentFilter(OverlayBubbleService.ACTION_BUBBLE_FAILED)
        );

        telephonyListener = new TelephonyCallStateListener(this);
        telephonyListener.setCallback(new TelephonyCallStateListener.Callback() {
            @Override
            public void onCallRinging() {
                // مفيش حاجة نعملها هنا دلوقتي — بس مفيدة لو حبينا نعرض
                // حالة "بيرن" في الـ overlay مستقبلاً.
            }

            @Override
            public void onCallAnswered() {
                telephonyWasAnswered = true;
            }

            @Override
            public void onCallEnded(boolean wasAnswered) {
                // wasAnswered هنا جاي من TelephonyManager مباشرة (CALL_STATE_OFFHOOK
                // حصل قبل الـ IDLE)، مش من InCallService. ده اللي بيخلي الحملة
                // تكمل وتعيد الاتصال من غير ما تحتاج Default Dialer.
                if (currentRedial != null) {
                    currentRedial.scheduleNextAttemptIfNeeded(wasAnswered || telephonyWasAnswered);
                }
                telephonyWasAnswered = false;
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification("جاري تشغيل الحملة..."));
        } catch (Exception e) {
            AppLogger.e(TAG, "startForeground failed, stopping service safely", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if (intent != null && "STOP".equals(intent.getAction())) {
                stopCampaign();
                return START_NOT_STICKY;
            }
            if (intent != null && "SKIP".equals(intent.getAction())) {
                skipCurrent();
                return START_STICKY;
            }
            startCampaign();
        } catch (Exception e) {
            AppLogger.e(TAG, "onStartCommand failed unexpectedly", e);
            isCampaignRunning = false;
        }

        return START_STICKY;
    }

    private void startCampaign() {
        if (isCampaignRunning) return;
        isCampaignRunning = true;
        config.setCampaignRunning(true);

        if (telephonyListener != null) {
            telephonyListener.start();
        }

        // FIX: كانت OverlayBubbleService بتتفتح من DashboardFragment
        // قبل ما نستدعي startForegroundService للحملة، لكن startService
        // مش متزامن (onCreate بتاعها بياخد وقت، وبتسجل progressReceiver
        // جواه). في الأجهزة البطيئة أو Samsung/One UI (اللي بيأخر بدء
        // الخدمات في الخلفية)، كان ممكن يحصل إن processNextInQueue()
        // تبعت أول broadcast قبل ما الأوفر يخلص تسجيل المستقبِل بتاعه،
        // فالأوفر تفضل فاضية لحد ما تحديث تاني يوصل (أو للأبد لو محدش
        // وصل). دلوقتي بنفتح الأوفر من هنا، جوه نفس الخدمة اللي هتعالج
        // الطابور، وبنأخر بدء المعالجة شوية صغير كافي إن الأوفر تتأسس.
        try {
            Intent bubbleIntent = new Intent(this, OverlayBubbleService.class);
            startService(bubbleIntent);
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to start OverlayBubbleService", e);
        }

        executor.execute(() -> {
            try {
                // تأخير بسيط عشان OverlayBubbleService تخلص onCreate()
                // وتسجل الـ receiver بتاعها قبل أول broadcast. لو الأوفر
                // اتأخرت أكتر من كده، loadPersistedState() بتاعتها
                // (AppConfig) بتفضل تضمن إنها تعرض آخر حالة صح لما تخلص.
                Thread.sleep(250);

                List<OrderRecord> pending = db.orderRecordDao().getPendingRecords();
                queue = pending != null ? pending : new java.util.ArrayList<>();
                queueIndex = 0;
                queueTotal = queue.size();

                Log.d(TAG, "Processing " + queueTotal + " pending orders");
                mainHandler.post(() -> updateNotification("عدد الطلبات المعلّقة: " + queueTotal));

                if (queue.isEmpty()) {
                    mainHandler.post(() -> {
                        updateNotification("لا توجد طلبات معلقة");
                        Toast.makeText(getApplicationContext(),
                                "لا توجد طلبات معلّقة (Pending) للاتصال بها. أضف طلب جديد أولًا.",
                                Toast.LENGTH_LONG).show();
                    });
                    isCampaignRunning = false;
                    config.setCampaignRunning(false);
                    if (telephonyListener != null) telephonyListener.stop();
                    // بنقفل الأوفر (لو فاتح) عشان مايفضلش يعرض بابل فاضية
                    // من غير أي حملة شغالة فعليًا.
                    stopService(new Intent(this, OverlayBubbleService.class));
                    stopForeground(true);
                    stopSelf();
                    return;
                }

                broadcastProgress(queueTotal, 0, null, null, null, 0, 0, 0);
                processNextInQueue();
            } catch (Exception e) {
                AppLogger.e(TAG, "startCampaign crashed", e);
                isCampaignRunning = false;
            }
        });
    }

    /**
     * بيعالج الطلب اللي في queueIndex، وبعدين يستنى إشارة onFinished
     * من AutoRedialManager قبل ما ينتقل للطلب اللي بعده. كده الكامبين
     * كله بيمشي رقم ورا رقم من غير ما يحتاج تدخل يدوي.
     */
    private void processNextInQueue() {
        if (!isCampaignRunning) return;

        if (queue == null || queueIndex >= queue.size()) {
            mainHandler.post(() -> updateNotification("انتهت الحملة"));
            isCampaignRunning = false;
            config.setCampaignRunning(false);
            if (telephonyListener != null) telephonyListener.stop();
            return;
        }

        OrderRecord record = queue.get(queueIndex);
        lastKnownCurrentName = record.customerName;
        lastKnownCurrentPhone = record.phoneNumber;

        // FIX: كنا بنستخدم broadcastCurrentName() اللي بتبعت الاسم بس
        // من غير رقم التليفون ومن غير عدد المحاولات، وده كان يخلي
        // البابل يعرض "-" في مكان الرقم، وكمان يصفّر lastAutoDialMax
        // في الأوفر لصفر، وده بيمنع عرض عداد المحاولات حتى بعد ما
        // onAttemptStarted تبعت تحديث صحيح لاحقًا (لأن أي broadcast
        // جديد للعميل الجديد بيصفّرها تاني). دلوقتي بنبعت بيانات
        // العميل الجديد كاملة (اسم + رقم) من أول broadcast.
        broadcastProgress(
                queueTotal, queueIndex,
                record.customerName,
                record.phoneNumber != null ? record.phoneNumber : "",
                OrderRecord.STATUS_PENDING,
                config.getMaxAttempts(),
                config.getDelaySeconds(),
                0
        );
        startAutoDialing(record);
    }

    public void stopCampaign() {
        isCampaignRunning = false;
        config.setCampaignRunning(false);
        if (currentRedial != null) {
            currentRedial.stop();
            currentRedial = null;
        }
        if (telephonyListener != null) telephonyListener.stop();
        updateNotification("تم إيقاف الحملة");
        stopForeground(true);
        stopSelf();
    }

    /**
     * بيتخطى الرقم الحالي فورًا (بيوقف محاولاته المتبقية) وينتقل
     * للرقم اللي بعده في القائمة، من غير ما يوقف الحملة كلها.
     * بينادى من تاب "الأرقام الحالية" لما المستخدم يدوس "تخطي الحالي".
     */
    public void skipCurrent() {
        if (!isCampaignRunning || currentRedial == null) return;
        Log.d(TAG, "Skipping current number by user request");
        // stop() بتنادي onFinished(false) تلقائيًا، واللي بيتكفل
        // بالانتقال للرقم اللي بعده عن طريق advanceQueue().
        currentRedial.stop();
    }

    private void startAutoDialing(OrderRecord record) {
        try {
            if (record == null || record.phoneNumber == null) {
                AppLogger.w(TAG, "Cannot start auto-dialing: null record or phone");
                advanceQueue();
                return;
            }

            mainHandler.post(() -> updateNotification("جاري الاتصال بـ: " + record.customerName));
            Log.d(TAG, "Starting auto-dialing for: " + record.customerName);

            AutoRedialManager redial = new AutoRedialManager(
                    getApplicationContext(),
                    record.phoneNumber,
                    config.getMaxAttempts(),
                    config.getDelaySeconds()
            );
            currentRedial = redial;

            redial.setListener(new AutoRedialManager.RedialListener() {
                // بيتسجل true لو الرقم اتوقف يدويًا (skip أو stop) قبل
                // ما يخلص محاولاته أو يتوصل، عشان onFinished() يعرف
                // يحدد الحالة النهائية صح بدل ما يسيبها PENDING للأبد.
                boolean wasManuallyStopped = false;

                @Override
                public void onAttemptStarted(int attemptNumber, int maxAttempts) {
                    Log.d(TAG, "Auto-dial attempt " + attemptNumber + "/" + maxAttempts);
                    mainHandler.post(() -> updateNotification(
                            "اتصال تلقائي: " + record.customerName + " - محاولة " + attemptNumber));
                    broadcastAutoDialProgress(attemptNumber, maxAttempts, config.getDelaySeconds());
                    record.attempts = attemptNumber;
                    safeUpdateRecord(record, OrderRecord.STATUS_PENDING);
                }

                @Override
                public void onMaxAttemptsReached() {
                    Log.d(TAG, "Max attempts reached for: " + record.customerName);
                    safeUpdateRecord(record, OrderRecord.STATUS_FAILED);
                }

                @Override
                public void onStopped() {
                    Log.d(TAG, "Auto-dialing stopped for: " + record.customerName);
                    // بيتنادى لما skipCurrent() أو stopCampaign() توقف الرقم
                    // ده يدويًا قبل ما يخلص محاولاته أو يتوصل. من غير العلم
                    // ده، onFinished(false) هيسيب الحالة PENDING زي ما هي،
                    // وده يخلي الرقم يتصل عليه تاني من الصفر في كل حملة
                    // جاية حتى لو المستخدم قصد يتخطاه.
                    wasManuallyStopped = true;
                }

                @Override
                public void onFinished(boolean wasAnswered) {
                    // الرقم ده خلص تمامًا (اتوصل أو خلص محاولاته أو
                    // اتوقف يدويًا) — نحدث حالته النهائية وننتقل للرقم
                    // اللي بعده.
                    String finalStatus;
                    if (wasAnswered) {
                        finalStatus = OrderRecord.STATUS_CALLED;
                    } else if (wasManuallyStopped) {
                        finalStatus = OrderRecord.STATUS_FAILED;
                    } else {
                        finalStatus = record.status;
                    }
                    safeUpdateRecord(record, finalStatus);

                    int processed = 0;
                    try {
                        processed = db.orderRecordDao().countProcessed();
                    } catch (Exception e) {
                        AppLogger.e(TAG, "countProcessed failed", e);
                    }

                    broadcastProgress(
                            queueTotal, processed,
                            record.customerName,
                            record.phoneNumber != null ? record.phoneNumber : "",
                            record.status != null ? record.status : OrderRecord.STATUS_PENDING,
                            config.getMaxAttempts(),
                            config.getDelaySeconds(),
                            record.attempts
                    );

                    advanceQueue();
                }
            });

            redial.start();

        } catch (Exception e) {
            AppLogger.e(TAG, "Error starting auto-dialing", e);
            safeUpdateRecord(record, OrderRecord.STATUS_FAILED);
            advanceQueue();
        }
    }

    private void advanceQueue() {
        currentRedial = null;
        queueIndex++;
        executor.execute(this::processNextInQueue);
    }

    // ---------- helpers ----------

    private void safeUpdateRecord(OrderRecord record, String status) {
        try {
            record.status = status;
            record.lastUpdatedAt = new java.util.Date();
            db.orderRecordDao().update(record);
        } catch (Exception e) {
            AppLogger.e(TAG, "safeUpdateRecord failed for status=" + status, e);
        }
    }

    private void broadcastProgress(int total, int processed, String currentName,
                                    String lastPhone, String lastStatus,
                                    int autoDialMax, int autoDialDelay, int autoDialAttempts) {
        config.saveCampaignState(total, processed, currentName, lastPhone, autoDialAttempts, autoDialMax);

        Intent b = new Intent(com.localorderai.ui.CampaignProgressDialog.ACTION_PROGRESS);
        b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_TOTAL, total);
        b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_PROCESSED, processed);
        if (currentName != null) b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_CURRENT_NAME, currentName);
        if (lastPhone != null) b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_LAST_PHONE, lastPhone);
        if (lastStatus != null) b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_LAST_STATUS, lastStatus);
        b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_AUTODIAL_MAX, autoDialMax);
        b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_AUTODIAL_DELAY, autoDialDelay);
        b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_AUTODIAL_ATTEMPTS, autoDialAttempts);
        b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_AUDIO_STATUS,
                "ميك: " + (config.isRecordingEnabled() ? "مفعّل" : "متوقّف") + " - مكبر: " + (config.isSpeakerEnabled() ? "مفعّل" : "متوقّف"));
        LocalBroadcastManager.getInstance(this).sendBroadcast(b);
    }

    private void broadcastAutoDialProgress(int attempts, int max, int delay) {
        config.saveCampaignState(queueTotal, queueIndex, lastKnownCurrentName, lastKnownCurrentPhone, attempts, max);

        Intent b = new Intent(com.localorderai.ui.CampaignProgressDialog.ACTION_PROGRESS);
        b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_AUTODIAL_ATTEMPTS, attempts);
        b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_AUTODIAL_MAX, max);
        b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_AUTODIAL_DELAY, delay);
        b.putExtra(com.localorderai.ui.CampaignProgressDialog.EXTRA_AUDIO_STATUS,
                "ميك: " + (config.isRecordingEnabled() ? "مفعّل" : "متوقّف") + " - مكبر: " + (config.isSpeakerEnabled() ? "مفعّل" : "متوقّف"));
        LocalBroadcastManager.getInstance(this).sendBroadcast(b);
    }

    // ---------- Notification ----------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "حملة الاتصال التلقائي",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("LocalOrderAI")
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(content));
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
        isCampaignRunning = false;
        currentRedial = null;
        if (telephonyListener != null) telephonyListener.stop();
        executor.shutdownNow();
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(stopReceiver); } catch (Exception ignored) {}
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(liveSettingsReceiver); } catch (Exception ignored) {}
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(bubbleFailedReceiver); } catch (Exception ignored) {}
    }
}
