package com.localorderai.utils;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;

/**
 * CallRecordingConsentManager
 * -----------------------------
 * قبل أي تسجيل، لازم يتشغّل إعلان صوتي واضح للعميل بيقوله
 * إن المكالمة دي بيتم تسجيلها لأغراض تأكيد الطلب/جودة الخدمة.
 * التسجيل ميبدأش إلا بعد ما الإعلان ده يخلص.
 *
 * ده مطلب قانوني في غالبية الدول (تسجيل مكالمات محتاج إشعار/موافقة)،
 * ومش تفصيلة اختيارية — أي مسار تسجيل في التطبيق لازم يعدي من هنا.
 */
public class CallRecordingConsentManager {

    private static final String TAG = "ConsentManager";

    public interface ConsentCallback {
        void onConsentAnnouncementFinished();
        void onError(String message);
    }

    private final Context context;
    private MediaPlayer mediaPlayer;

    public CallRecordingConsentManager(Context context) {
        this.context = context;
    }

    /**
     * بيشغّل ملف صوتي مسجّل مسبقًا (res/raw/consent_notice_ar.mp3) بنص واضح زي:
     * "هذه المكالمة قد يتم تسجيلها لأغراض تأكيد الطلب وجودة الخدمة."
     *
     * التسجيل الفعلي (لو مفعّل) يبدأ فقط بعد onConsentAnnouncementFinished().
     */
    public void playConsentAnnouncement(int rawResourceId, ConsentCallback callback) {
        try {
            mediaPlayer = MediaPlayer.create(context, rawResourceId);
            if (mediaPlayer == null) {
                callback.onError("تعذر تحميل ملف الإعلان الصوتي");
                return;
            }

            mediaPlayer.setOnCompletionListener(mp -> {
                releasePlayer();
                callback.onConsentAnnouncementFinished();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + "/" + extra);
                releasePlayer();
                callback.onError("خطأ أثناء تشغيل إعلان الموافقة");
                return true;
            });

            mediaPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Consent playback failed", e);
            callback.onError(e.getMessage());
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
