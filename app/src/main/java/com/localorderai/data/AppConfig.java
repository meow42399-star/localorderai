package com.localorderai.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * AppConfig
 * ---------
 * تخزين إعدادات المستخدم الخاصة بالاتصال التلقائي فقط
 * (حدود المحاولات، الفاصل الزمني، التسجيل، مكبر الصوت).
 */
public class AppConfig {

    private static final String PREFS_NAME = "localorderai_prefs";

    private static final String KEY_MAX_ATTEMPTS = "max_attempts";
    private static final String KEY_DELAY_SECONDS = "delay_seconds";
    private static final String KEY_RECORDING_ENABLED = "recording_enabled";
    private static final String KEY_AUTO_DIALING_ENABLED = "auto_dialing_enabled";
    private static final String KEY_SPEAKER_ENABLED = "speaker_enabled";
    private static final String KEY_MIC_MUTED = "mic_muted";
    private static final String KEY_SIM_SLOT = "sim_slot";
    private static final String KEY_REDIAL_MODE = "redial_mode";

    // حالة الحملة الحالية — بتتحدث مع كل تقدم، وبتتقرا فورًا لما
    // أي واجهة (الأوفر / شاشة الأرقام الحالية) تتفتح، بدل ما تستنى
    // broadcast ممكن يضيع لو حصل قبل ما المستقبِل يتسجل.
    private static final String KEY_STATE_TOTAL = "state_total";
    private static final String KEY_STATE_PROCESSED = "state_processed";
    private static final String KEY_STATE_CURRENT_NAME = "state_current_name";
    private static final String KEY_STATE_CURRENT_PHONE = "state_current_phone";
    private static final String KEY_STATE_ATTEMPT = "state_attempt";
    private static final String KEY_STATE_ATTEMPT_MAX = "state_attempt_max";
    private static final String KEY_STATE_IS_RUNNING = "state_is_running";

    // قيم SIM slot الممكنة
    public static final int SIM_SLOT_ASK_SYSTEM = -1; // خلي النظام يقرر / يسأل المستخدم
    public static final int SIM_SLOT_1 = 0;
    public static final int SIM_SLOT_2 = 1;

    /**
     * وضع إعادة الاتصال — بيحدد امتى يتوقف الاتصال المتكرر على رقم
     * معين وينتقل للرقم اللي بعده.
     *
     * MAX_ATTEMPTS: يوقف بعد عدد محاولات محدد (getMaxAttempts())، بغض
     * النظر هل حد رد أو لأ. ده السلوك الافتراضي القديم.
     *
     * UNTIL_ANSWERED: يفضل يعيد المحاولة من غير حد أقصى لحد ما حد يرد
     * فعليًا، وبعدها يوقف على الرقم ده وينتقل للي بعده.
     *
     * مصمّمة كـ String enum (مش boolean) عشان تسمح بإضافة أوضاع تانية
     * مستقبلاً (مثلاً: "حد أقصى للوقت الكلي" أو "حد أقصى مضاعف لو
     * الرقم مشغول") من غير ما نحتاج نعيد تصميم الإعداد من الأول.
     */
    public static final String REDIAL_MODE_MAX_ATTEMPTS = "max_attempts";
    public static final String REDIAL_MODE_UNTIL_ANSWERED = "until_answered";

    private final SharedPreferences prefs;

    public AppConfig(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // الحد الأقصى 100 محاولة حتى لو المستخدم اختار أكثر
    public int getMaxAttempts() {
        return Math.max(1, Math.min(prefs.getInt(KEY_MAX_ATTEMPTS, 5), 100));
    }

    public void setMaxAttempts(int value) {
        prefs.edit().putInt(KEY_MAX_ATTEMPTS, Math.max(1, Math.min(value, 100))).apply();
    }

    // الحد الأدنى ثانية واحدة والحد الأقصى 999 ثانية بين المحاولات
    public int getDelaySeconds() {
        return Math.max(1, Math.min(prefs.getInt(KEY_DELAY_SECONDS, 90), 999));
    }

    public void setDelaySeconds(int value) {
        prefs.edit().putInt(KEY_DELAY_SECONDS, Math.max(1, Math.min(value, 999))).apply();
    }

    /** الافتراضي: MAX_ATTEMPTS (نفس السلوك القديم قبل ما الإعداد ده يتضاف). */
    public String getRedialMode() {
        return prefs.getString(KEY_REDIAL_MODE, REDIAL_MODE_MAX_ATTEMPTS);
    }

    public void setRedialMode(String mode) {
        if (!REDIAL_MODE_MAX_ATTEMPTS.equals(mode) && !REDIAL_MODE_UNTIL_ANSWERED.equals(mode)) {
            mode = REDIAL_MODE_MAX_ATTEMPTS;
        }
        prefs.edit().putString(KEY_REDIAL_MODE, mode).apply();
    }

    public boolean isUntilAnsweredMode() {
        return REDIAL_MODE_UNTIL_ANSWERED.equals(getRedialMode());
    }

    public boolean isRecordingEnabled() {
        return prefs.getBoolean(KEY_RECORDING_ENABLED, false);
    }

    public void setRecordingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_RECORDING_ENABLED, enabled).apply();
    }

    public boolean isAutoDialingEnabled() {
        return prefs.getBoolean(KEY_AUTO_DIALING_ENABLED, true);
    }

    public void setAutoDialingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_DIALING_ENABLED, enabled).apply();
    }

    public boolean isSpeakerEnabled() {
        return prefs.getBoolean(KEY_SPEAKER_ENABLED, true);
    }

    public void setSpeakerEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SPEAKER_ENABLED, enabled).apply();
    }

    // كتم المايك أثناء المكالمة (مختلف عن تسجيل المكالمة)
    public boolean isMicMuted() {
        return prefs.getBoolean(KEY_MIC_MUTED, false);
    }

    public void setMicMuted(boolean muted) {
        prefs.edit().putBoolean(KEY_MIC_MUTED, muted).apply();
    }

    // شريحة الاتصال المستخدمة لكل مكالمة: SIM_SLOT_ASK_SYSTEM / SIM_SLOT_1 / SIM_SLOT_2
    public int getSimSlot() {
        return prefs.getInt(KEY_SIM_SLOT, SIM_SLOT_ASK_SYSTEM);
    }

    public void setSimSlot(int slot) {
        prefs.edit().putInt(KEY_SIM_SLOT, slot).apply();
    }

    // ---------- حالة الحملة الحالية (مصدر الحقيقة الثابت) ----------
    // بيتقرا فورًا من غير انتظار أي broadcast، وده اللي بيحل مشكلة
    // "الأوفر بيبين 0 و -" لو اتفتح بعد ما أول تحديث فات عليه.

    public void saveCampaignState(int total, int processed, String currentName,
                                   String currentPhone, int attempt, int attemptMax) {
        prefs.edit()
                .putInt(KEY_STATE_TOTAL, total)
                .putInt(KEY_STATE_PROCESSED, processed)
                .putString(KEY_STATE_CURRENT_NAME, currentName)
                .putString(KEY_STATE_CURRENT_PHONE, currentPhone)
                .putInt(KEY_STATE_ATTEMPT, attempt)
                .putInt(KEY_STATE_ATTEMPT_MAX, attemptMax)
                .apply();
    }

    public int getStateTotal() { return prefs.getInt(KEY_STATE_TOTAL, 0); }
    public int getStateProcessed() { return prefs.getInt(KEY_STATE_PROCESSED, 0); }
    public String getStateCurrentName() { return prefs.getString(KEY_STATE_CURRENT_NAME, null); }
    public String getStateCurrentPhone() { return prefs.getString(KEY_STATE_CURRENT_PHONE, null); }
    public int getStateAttempt() { return prefs.getInt(KEY_STATE_ATTEMPT, 0); }
    public int getStateAttemptMax() { return prefs.getInt(KEY_STATE_ATTEMPT_MAX, 0); }

    public void setCampaignRunning(boolean running) {
        prefs.edit().putBoolean(KEY_STATE_IS_RUNNING, running).apply();
        if (!running) {
            // امسح آخر حالة لما الحملة توقف عشان الشاشات الجديدة تبدأ فاضية
            prefs.edit()
                    .remove(KEY_STATE_TOTAL)
                    .remove(KEY_STATE_PROCESSED)
                    .remove(KEY_STATE_CURRENT_NAME)
                    .remove(KEY_STATE_CURRENT_PHONE)
                    .remove(KEY_STATE_ATTEMPT)
                    .remove(KEY_STATE_ATTEMPT_MAX)
                    .apply();
        }
    }

    public boolean isCampaignRunning() {
        return prefs.getBoolean(KEY_STATE_IS_RUNNING, false);
    }
}
