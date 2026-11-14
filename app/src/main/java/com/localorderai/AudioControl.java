package com.localorderai;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.CallAudioState;
import android.telecom.TelecomManager;

/**
 * تحكم في المكبر والميوت.
 *
 *  - الوضع الكامل (التطبيق هو الـ Default Dialer ومعاه مكالمة حيّة): InCallService (CallEndpoint / setAudioRoute).
 *  - الوضع البسيط، المكبر: بيتحدد وقت طلب الاتصال نفسه (EXTRA_START_CALL_WITH_SPEAKERPHONE في CallQueue)
 *      ونظام الاتصال (Telecom) هو اللي يشغّله، زي التطبيق المرجعي. تغيير المسار وسط المكالمة من تطبيق
 *      عادي بيرفضه النظام على أندرويد 12+، فمش بنحاول. زرار المكبر هنا بيبدّل الإعداد للمكالمة الجاية.
 *  - الميوت (الوضعين): setMode(IN_CALL) ثم setMicrophoneMute بعد تأخير بسيط.
 *  - ensure* بتتحقق من الحالة الأول ومش بتعمل حاجة لو الحالة مظبوطة، فالتكرار بالتوقيتات آمن.
 *  - reset() آخر المكالمة بيرجّع المكبر والميوت ووضع الصوت.
 */
final class AudioControl {

    /** تأخير 1100ms قبل setSpeakerphoneOn على أندرويد < 31، زي التطبيق المرجعي. */
    /** تأخير بسيط قبل الميوت بعد تغيير الوضع (200ms زي المرجع). */
    private static final long MUTE_DELAY_MS = 200;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();

    /** هل التطبيق غيّر وضع الصوت (setMode) ومحتاج يرجّعه؟ */
    private static boolean modeTouched;

    /** المستخدم قفل المكبر بإيده: الأوامر الأوتوماتيك ما تفتحوش تاني لحد آخر المكالمة. */
    private static boolean userSpeakerOff;

    /** مستمع تغيّر سماعة الاتصال (أندرويد 12+). */
    private static AudioManager.OnCommunicationDeviceChangedListener routeListener;

    /** آخر عملية صوت نفّذناها (للتشخيص). */
    private static volatile String lastOp = "";

    /** سطر تشخيص بيبيّن الحالة الحقيقية للصوت. */
    @SuppressWarnings("deprecation")
    static String diagnose(Context c) {
        AudioManager a = am(c);
        StringBuilder b = new StringBuilder("SDK ").append(Build.VERSION.SDK_INT);
        b.append(" | mode ").append(a.getMode());
        b.append(" | spk ").append(a.isSpeakerphoneOn());
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                AudioDeviceInfo d = a.getCommunicationDevice();
                b.append(" | dev ").append(d == null ? "none" : String.valueOf(d.getType()));
            } catch (Exception ignored) {
            }
        }
        DialerInCallService ics = DialerInCallService.instance;
        b.append(" | ics ").append(ics == null ? "no" : ("yes " + ics.describeEndpoints()));
        b.append(" | ").append(lastOp);
        return b.toString();
    }

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
            Boolean ep = s.speakerFromEndpoint(); // أندرويد 14+: المصدر الرسمي
            if (ep != null) return ep;
            CallAudioState st = s.getCallAudioState();
            return st != null && st.getRoute() == CallAudioState.ROUTE_SPEAKER;
        }
        return speakerActive(am(c));
    }

    /** أمر يدوي من المستخدم (بيتنفذ دايماً). قفل المكبر يدوياً بيوقف إعادة التفعيل التلقائي. */
    static void setSpeaker(Context c, boolean on) {
        userSpeakerOff = !on;
        applySpeaker(c, on);
    }

    /**
     * زرار المكبر اليدوي، وبيرجّع رسالة قصيرة للمستخدم.
     *  - الوضع الكامل ومعاه مكالمة حيّة: بيبدّل المكبر فوراً.
     *  - الوضع البسيط ووسط مكالمة: بيدوس على زرار المكبر في إشعار المكالمة (CallNotificationService) لو موجود.
     *  - غير كده: بيبدّل الإعداد المحفوظ، وبيتطبق من المكالمة الجاية (CallQueue بيقراه قبل كل اتصال).
     */
    static String toggleSpeaker(Context c) {
        String m;
        if (liveIcs() != null) {
            boolean on = !isSpeakerOn(c);
            setSpeaker(c, on);
            m = on ? "طلبت تشغيل المكبر" : "قفلت المكبر";
        } else if (inCall(c)) {
            m = CallNotificationService.pressSpeaker();
        } else {
            SharedPreferences sp = c.getSharedPreferences("prefs", Context.MODE_PRIVATE);
            boolean on = !sp.getBoolean("speaker", true);
            sp.edit().putBoolean("speaker", on).apply();
            m = on ? "المكبر هيشتغل من المكالمة الجاية" : "المكبر هيتقفل من المكالمة الجاية";
        }
        EventLog.add("speaker toggle: " + m);
        return m;
    }

    /** هل فيه مكالمة شغّالة دلوقتي (أي مكالمة، مش بس من الطابور)؟ */
    private static boolean inCall(Context c) {
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            return tm != null && tm.isInCall();
        } catch (SecurityException e) {
            return false;
        }
    }

    private static void applySpeaker(Context c, boolean on) {
        DialerInCallService s = liveIcs();
        if (s != null) {
            // أندرويد 14+: CallEndpoint أولاً. لو مش متاح أو اترفض، setAudioRoute القديمة كاحتياطي.
            if (s.requestSpeaker(on)) {
                lastOp = "ICS requestCallEndpointChange " + (on ? "SPEAKER" : "EARPIECE");
                return;
            }
            s.setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_WIRED_OR_EARPIECE);
            lastOp = "ICS setAudioRoute " + (on ? "SPEAKER" : "EARPIECE");
            EventLog.add("audio " + lastOp);
            return;
        }
        // الوضع البسيط: المكبر بيتحدد وقت طلب الاتصال ومش بيتغيّر وسط المكالمة.
        lastOp = "basic: بيتحدد وقت الاتصال";
    }

    /**
     * أوتوماتيك: يتحقق الأول، ولو الحالة مظبوطة ما يعملش حاجة.
     * بيرجّع true لو الحالة المطلوبة متحققة فعلاً (في وضع الـ InCallService التحديث غير متزامن،
     * فممكن يرجّع false لحظياً والمحاولة اللي بعدها بتتأكد).
     */
    @SuppressWarnings("deprecation")
    static boolean ensureSpeaker(Context c, boolean on) {
        if (on && userSpeakerOff) return false; // المستخدم قفله بإيده
        if (isSpeakerOn(c) == on) return true;
        // مش بنسحب الصوت من سماعة بلوتوث شغالة (زي التطبيق المرجعي)
        if (on && liveIcs() == null && am(c).isBluetoothScoOn()) return false;
        applySpeaker(c, on);
        return isSpeakerOn(c) == on;
    }

    /**
     * هل المكبر شغال فعلاً؟ بنعتبره شغال لو أي مؤشر من الاتنين قال كده (setSpeakerphoneOn أو سماعة الاتصال).
     * قبل كده كنا بنصدّق مؤشر واحد بس، فلو قال "مقفول" غلط، الضغطة اللي المفروض تقفل كانت بتفتح تاني.
     */
    @SuppressWarnings("deprecation")
    private static boolean speakerActive(AudioManager a) {
        boolean sp = false;
        try {
            sp = a.isSpeakerphoneOn();
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                AudioDeviceInfo d = a.getCommunicationDevice();
                if (d != null && d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return true;
            } catch (Exception ignored) {
            }
        }
        return sp;
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

    // ------------------------------------------- مراقبة تغيّر المسار (أندرويد 12+)

    /**
     * لو النظام غيّر سماعة الاتصال وسط المكالمة (مثلاً رجّعها للسماعة العادية لما الطرف التاني رد)،
     * بننادي onChange على الـ main thread. الـ onChange هو اللي بيقرر يعيد التفعيل أو لأ.
     * على أندرويد أقدم من 12 مفيش مستمع، فالتكرار بالتوقيتات هو الأساس.
     */
    static void startRouteWatch(Context c, final Runnable onChange) {
        if (Build.VERSION.SDK_INT < 31) return;
        stopRouteWatch(c);
        routeListener = new AudioManager.OnCommunicationDeviceChangedListener() {
            @Override
            public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                MAIN.post(onChange);
            }
        };
        try {
            am(c).addOnCommunicationDeviceChangedListener(c.getMainExecutor(), routeListener);
        } catch (Exception e) {
            routeListener = null;
        }
    }

    /** بداية مكالمة جديدة: قفل المستخدم اليدوي من المكالمة اللي فاتت ما يمتدش للجديدة. */
    static void newCall() {
        userSpeakerOff = false;
    }

    static void stopRouteWatch(Context c) {
        if (Build.VERSION.SDK_INT < 31 || routeListener == null) return;
        try {
            am(c).removeOnCommunicationDeviceChangedListener(routeListener);
        } catch (Exception ignored) {
        }
        routeListener = null;
    }

    // ------------------------------------------------------------------ تنظيف

    /** آخر المكالمة: رجّع المكبر والميوت وشيل أي وضع صوت غيّره التطبيق. */
    static void reset(Context c) {
        EventLog.add("audio reset (last op: " + lastOp + ")");
        MAIN.removeCallbacksAndMessages(TOKEN);
        stopRouteWatch(c);
        userSpeakerOff = false;
        applySpeaker(c, false);
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
