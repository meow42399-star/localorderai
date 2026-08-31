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
    private int currentAttempt = 0;
    private boolean isRunning = false;
    private boolean finishedNotified = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RedialListener listener;

    public AutoRedialManager(Context context, String phoneNumber, int maxAttempts, int delaySeconds) {
        this.context = context.getApplicationContext();
        this.phoneNumber = phoneNumber;
        // مفيش حد أقصى مصطنع هنا؛ AppConfig نفسه بيحدد سقف 100 محاولة
        this.maxAttempts = Math.max(1, maxAttempts);
        this.delaySeconds = delaySeconds;
    }

    public void setListener(RedialListener listener) {
        this.listener = listener;
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

        if (!isRunning || currentAttempt >= maxAttempts) {
            if (currentAttempt >= maxAttempts && listener != null) {
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
        if (!isRunning || currentAttempt >= maxAttempts) {
            if (currentAttempt >= maxAttempts && listener != null) {
                listener.onMaxAttemptsReached();
            }
            isRunning = false;
            notifyFinished(false);
            return;
        }

        // تحذير مسبق لو إذن الفقاعة العائمة (اللي بيسمح ببدء الأنشطة من الخلفية) مش ممنوح.
        // ملحوظة: الإذن الممنوح لوحده مش كافي — أندرويد بيدّي استثناء بدء
        // الأنشطة من الخلفية بس لو فيه overlay view مرسومة فعليًا على
        // الشاشة دلوقتي، مش لمجرد إن الإذن مفعّل. لو الأوفر اتقفلت أو
        // فشلت (زي ما بيحصل على بعض أجهزة Samsung)، startActivity() جوّه
        // هترفض بصمت تام من النظام من غير أي استثناء. حماية إضافية موجودة
        // في CampaignForegroundService (بيوقف الحملة فورًا لو الأوفر فشلت
        // ترسم نفسها من الأساس)، لكن الفحص هنا بيغطي حالة إن الأوفر
        // اتقفلت لاحقًا (مثلاً المستخدم لغى الإذن يدويًا وسط الحملة).
        if (!hasOverlayPermission()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — call launch from background will likely be silently blocked");
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
            Log.e(TAG, "SecurityException launching call activity", e);
            isRunning = false;
            notifyFinished(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch call activity", e);
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
