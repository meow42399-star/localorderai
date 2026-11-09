package com.localorderai;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.CallAudioState;

/**
 * تحكم في المكبر والميوت.
 *
 * الاستراتيجية (مبنية على تحليل تطبيق auto-dial المرجعي):
 *  1) لو التطبيق هو الـ Default Dialer وفيه مكالمة حيّة: نستخدم InCallService (setAudioRoute / setMuted) — ده الأضمن.
 *  2) غير كده بنستخدم AudioManager:
 *     - أندرويد 12+: setCommunicationDevice(سماعة الموبايل الداخلية) ونتأكد من النتيجة.
 *     - لو ما نفعش (أو أندرويد أقدم): سلّم أوضاع صوت IN_CALL ← NORMAL ← IN_COMMUNICATION،
 *       وفي كل خطوة setSpeakerphoneOn(true) ونتحقق من isSpeakerphoneOn قبل ما نكمّل للخطوة اللي بعدها.
 *  3) الأوامر الأوتوماتيك (ensure*) بتتحقق من الحالة الأول ومش بتعمل أي حاجة لو الحالة صح،
 *     فالتكرار بالتوقيتات آمن ومش بيقلب المكبر يمين وشمال.
 *  4) الأوامر الأوتوماتيك بتحترم سماعة البلوتوث (مش بتسحب الصوت منها)، لكن ضغطة المستخدم اليدوية بتتنفذ دايماً.
 *  5) في الآخر reset() بيرجّع المكبر/الميوت وبيشيل أي وضع صوت التطبيق غيّره.
 */
final class AudioControl {

    /** نفس ترتيب سلّم المحاولات في التطبيق المرجعي. */
    private static final int[] SPEAKER_MODE_LADDER = {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_NORMAL,
            AudioManager.MODE_IN_COMMUNICATION
    };
    /** تأخير بسيط قبل الميوت بعد تغيير الوضع (200ms زي المرجع). */
    private static final long MUTE_DELAY_MS = 200;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();

    /** هل التطبيق غيّر وضع الصوت (setMode) ومحتاج يرجّعه؟ */
    private static boolean modeTouched;

    private AudioControl() {
    }

    private static AudioManager am(Context c) {
        return (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    /** الـ InCallService بس لو فيه مكالمة حيّة (من غير مكالمة setAudioRoute ملهاش معنى). */
    private static DialerInCallService liveIcs() {
        DialerInCallService s = DialerInCallService.instance;
        return (s != null && !s.getCalls().isEmpty()) ? s : null;
    }

    // ---------------------------------------------------------------- المكبر

    static boolean isSpeakerOn(Context c) {
        DialerInCallService s = liveIcs();
        if (s != null) {
            CallAudioState st = s.getCallAudioState();
            return st != null && st.getRoute() == CallAudioState.ROUTE_SPEAKER;
        }
        AudioManager a = am(c);
        if (Build.VERSION.SDK_INT >= 31) {
            AudioDeviceInfo d = a.getCommunicationDevice();
            if (d != null) return d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        }
        return a.isSpeakerphoneOn();
    }

    /** أمر مباشر (بيتنفذ دايماً، يدوي أو أوتوماتيك). */
    static void setSpeaker(Context c, boolean on) {
        DialerInCallService s = liveIcs();
        if (s != null) {
            s.setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_WIRED_OR_EARPIECE);
            return;
        }
        AudioManager a = am(c);
        if (on) {
            speakerOnViaAudioManager(a);
        } else {
            speakerOffViaAudioManager(a);
        }
    }

    /**
     * أوتوماتيك: يتحقق الأول، ولو الحالة مظبوطة ما يعملش حاجة.
     * بيرجّع true لو الحالة المطلوبة متحققة فعلاً (في وضع الـ InCallService التحديث غير متزامن،
     * فممكن يرجّع false لحظياً والمحاولة اللي بعدها بتتأكد).
     */
    @SuppressWarnings("deprecation")
    static boolean ensureSpeaker(Context c, boolean on) {
        if (isSpeakerOn(c) == on) return true;
        // مش بنسحب الصوت من سماعة بلوتوث شغالة (زي التطبيق المرجعي)
        if (on && liveIcs() == null && am(c).isBluetoothScoOn()) return false;
        setSpeaker(c, on);
        return isSpeakerOn(c) == on;
    }

    private static void speakerOnViaAudioManager(AudioManager a) {
        if (Build.VERSION.SDK_INT >= 31 && routeToBuiltInSpeaker(a) && a.isSpeakerphoneOn()) {
            return;
        }
        for (int mode : SPEAKER_MODE_LADDER) {
            setModeTracked(a, mode);
            try {
                a.setSpeakerphoneOn(true);
            } catch (Exception ignored) {
            }
            if (a.isSpeakerphoneOn()) return;
        }
    }

    private static void speakerOffViaAudioManager(AudioManager a) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                a.clearCommunicationDevice();
            } catch (Exception ignored) {
            }
        }
        try {
            a.setSpeakerphoneOn(false);
        } catch (Exception ignored) {
        }
    }

    /** أندرويد 12+: بيختار سماعة الموبايل الداخلية، ويرجّع نتيجة الطلب الحقيقية (مش بنتجاهلها). */
    private static boolean routeToBuiltInSpeaker(AudioManager a) {
        try {
            for (AudioDeviceInfo d : a.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    return a.setCommunicationDevice(d);
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ----------------------------------------------------------------- الميوت

    static boolean isMuted(Context c) {
        DialerInCallService s = liveIcs();
        if (s != null) {
            CallAudioState st = s.getCallAudioState();
            return st != null && st.isMuted();
        }
        return am(c).isMicrophoneMute();
    }

    static void setMuted(final Context c, final boolean muted) {
        DialerInCallService s = liveIcs();
        if (s != null) {
            s.setMuted(muted);
            return;
        }
        final AudioManager a = am(c);
        MAIN.removeCallbacksAndMessages(TOKEN); // نلغي أي ميوت مؤجل قديم
        if (muted) {
            // زي المرجع: نظبط وضع الصوت الأول وبعدين نكتم بعد تأخير بسيط عشان الكتم يثبت
            setModeTracked(a, AudioManager.MODE_IN_CALL);
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        a.setMicrophoneMute(true);
                    } catch (Exception ignored) {
                    }
                }
            }, TOKEN, MUTE_DELAY_MS);
        } else {
            try {
                a.setMicrophoneMute(false);
            } catch (Exception ignored) {
            }
        }
    }

    /** أوتوماتيك: يتحقق الأول (نفس فكرة ensureSpeaker). */
    static boolean ensureMuted(Context c, boolean muted) {
        if (isMuted(c) == muted) return true;
        setMuted(c, muted);
        return isMuted(c) == muted;
    }

    // ------------------------------------------------------------------ تنظيف

    /** آخر المكالمة: رجّع المكبر والميوت وشيل أي وضع صوت غيّره التطبيق. */
    static void reset(Context c) {
        MAIN.removeCallbacksAndMessages(TOKEN);
        setSpeaker(c, false);
        setMuted(c, false);
        if (modeTouched) {
            modeTouched = false;
            try {
                // بنرجّع NORMAL (مش الوضع القديم): الوضع القديم ممكن يكون IN_CALL لو اتاخد وسط مكالمة،
                // و setMode(NORMAL) بيشيل طلب التطبيق بس من غير ما يلمس وضع النظام.
                am(c).setMode(AudioManager.MODE_NORMAL);
            } catch (Exception ignored) {
            }
        }
    }

    private static void setModeTracked(AudioManager a, int mode) {
        try {
            a.setMode(mode);
            modeTouched = true;
        } catch (Exception ignored) {
        }
    }
}
