package com.localorderai.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.localorderai.ui.AutoCallLauncherActivity;

/**
 * AutoRedialManager
 * ------------------
 * بيدير محاولات الاتصال المتكررة برقم واحد. بعد ما المكالمة تخلص،
 * TelephonyCallStateListener (بيتابع الحالة عن طريق TelephonyManager،
 * من غير أي حاجة لدور Default Dialer) بينادي scheduleNextAttemptIfNeeded()
 * اللي إما:
 *   - يوقف لو المكالمة اتوصلت (حد رد فعلاً)
 *   - يوقف لو وصلنا للحد الأقصى للمحاولات
 *   - يجدول محاولة جديدة بعد delaySeconds
 *
 * في كل الحالات اللي بيوقف فيها، بينادي onFinished() عشان اللي
 * بيستخدم الـ manager (CampaignForegroundService) يعرف إنه يكمل
 * على الرقم اللي بعده في القائمة.
 */
public class AutoRedialManager {

    private static final String TAG = "AutoRedialManager";

    public interface RedialListener {
        void onAttemptStarted(int attemptNumber, int maxAttempts);
        void onMaxAttemptsReached();
        void onStopped();
        /**
         * بينادى مرة واحدة بس، لما الرقم ده يخلص تمامًا (اتوصل أو
         * خلص محاولاته أو اتوقف يدويًا) — دي الإشارة إن الكامبين
         * يقدر يكمل على الرقم اللي بعده.
         */
        default void onFinished(boolean wasAnswered) {}
    }

    private final Context context;
    private final String phoneNumber;
    private volatile int maxAttempts;
    private volatile int delaySeconds;
    private volatile boolean untilAnswered;
    private int currentAttempt = 0;
    private boolean isRunning = false;
    private boolean finishedNotified = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RedialListener listener;

    public AutoRedialManager(Context context, String phoneNumber, int maxAttempts, int delaySeconds) {
        this(context, phoneNumber, maxAttempts, delaySeconds, false);
    }

    /**
     * untilAnswered: لو true، بيتجاهل maxAttempts تمامًا ويفضل يعيد
     * المحاولة إلى ما لا نهاية لحد ما حد يرد فعليًا. الفاصل الزمني
     * (delaySeconds) لسه بيتطبق بين كل محاولة والتانية زي العادي —
     * الفرق الوحيد إن مفيش سقف لعدد المحاولات.
     */
    public AutoRedialManager(Context context, String phoneNumber, int maxAttempts, int delaySeconds, boolean untilAnswered) {
        this.context = context.getApplicationContext();
        this.phoneNumber = phoneNumber;
        // مفيش حد أقصى مصطنع هنا؛ AppConfig نفسه بيحدد سقف 100 محاولة
        this.maxAttempts = Math.max(1, maxAttempts);
        this.delaySeconds = delaySeconds;
        this.untilAnswered = untilAnswered;
    }

    public void setListener(RedialListener listener) {
        this.listener = listener;
    }

    /**
     * بيسمح بتحديث امتى الحد يتوقف وقت التشغيل (لو المستخدم غيّر
     * الإعداد وسط حملة شغالة). زي updateMaxAttempts()، بيأثر على
     * الرقم الحالي فورًا مش بس الأرقام الجاية.
     */
    public void updateUntilAnswered(boolean value) {
        this.untilAnswered = value;
    }

    /**
     * بيسمح بتحديث أقصى عدد محاولات وقت التشغيل (لما المستخدم يغيّر
     * السلايدر في الـ overlay panel وقت ما فيه رقم شغال دلوقتي)،
     * عشان التغيير يأثر على الرقم الحالي فورًا مش بس الأرقام الجاية.
     */
    public void updateMaxAttempts(int newMaxAttempts) {
        this.maxAttempts = Math.max(1, newMaxAttempts);
    }

    /** نفس الفكرة بالنسبة للتأخير بين المحاولات. */
    public void updateDelaySeconds(int newDelaySeconds) {
        this.delaySeconds = Math.max(0, newDelaySeconds);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        finishedNotified = false;
        currentAttempt = 0;
        placeCall();
    }

    public void stop() {
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
        if (listener != null) listener.onStopped();
        notifyFinished(false);
    }

    /**
     * بينادى من TelephonyCallStateListener بعد ما مكالمة تخلص.
     * wasAnswered: true لو المكالمة كانت اتردت فعلاً (حد رد).
     */
    public void scheduleNextAttemptIfNeeded(boolean wasAnswered) {
        if (wasAnswered) {
            isRunning = false;
            handler.removeCallbacksAndMessages(null);
            notifyFinished(true);
            return;
        }

        // في وضع untilAnswered، مفيش سقف لعدد المحاولات — بنستمر لحد
        // ما حد يرد فعليًا (الحالة دي اتعالجت فوق) أو الحملة تتوقف
        // يدويًا (isRunning بتبقى false في الحالة دي).
        boolean reachedLimit = !untilAnswered && currentAttempt >= maxAttempts;

        if (!isRunning || reachedLimit) {
            if (reachedLimit && listener != null) {
                listener.onMaxAttemptsReached();
            }
            isRunning = false;
            handler.removeCallbacksAndMessages(null);
            notifyFinished(false);
            return;
        }

        handler.postDelayed(this::placeCall, delaySeconds * 1000L);
    }

    private void notifyFinished(boolean wasAnswered) {
        if (finishedNotified) return;
        finishedNotified = true;
        if (listener != null) listener.onFinished(wasAnswered);
    }

    private void placeCall() {
        boolean reachedLimit = !untilAnswered && currentAttempt >= maxAttempts;
        if (!isRunning || reachedLimit) {
            if (reachedLimit && listener != null) {
                listener.onMaxAttemptsReached();
            }
            isRunning = false;
            notifyFinished(false);
            return;
        }

        // FIX: بعد ما التطبيق بقى Default Dialer، أندرويد بيدّي استثناء
        // رسمي لبدء الأنشطة من الخلفية للـ Default Dialer نفسه — مش
        // محتاجين نعتمد على وجود overlay view مرسومة فعليًا زي الأول.
        // بنسيب فحص SYSTEM_ALERT_WINDOW كتحذير احتياطي بس (مش حرج)،
        // لأنه لسه مطلوب لعرض الأوفر بابل نفسها (اللي بتوري تقدم الحملة)،
        // مش لبدء المكالمة.
        if (!hasOverlayPermission()) {
            AppLogger.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay bubble progress UI will not show, but call launch should still work via Default Dialer role");
        }

        currentAttempt++;
        if (listener != null) listener.onAttemptStarted(currentAttempt, maxAttempts);

        try {
            Intent launcherIntent = new Intent(context, AutoCallLauncherActivity.class);
            launcherIntent.putExtra(AutoCallLauncherActivity.EXTRA_PHONE_NUMBER, phoneNumber);
            launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launcherIntent);
            Log.d(TAG, "Launched AutoCallLauncherActivity for attempt " + currentAttempt);
        } catch (SecurityException e) {
            AppLogger.e(TAG, "SecurityException launching call activity", e);
            isRunning = false;
            notifyFinished(false);
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to launch call activity", e);
            isRunning = false;
            notifyFinished(false);
        }
    }

    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(context);
    }

    public int getCurrentAttempt() {
        return currentAttempt;
    }
}
