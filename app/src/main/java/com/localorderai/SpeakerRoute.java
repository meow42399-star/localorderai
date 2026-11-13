package com.localorderai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * بيجبر الصوت يطلع من السماعة الخارجية ويفضل عليها (الوضع البسيط، أندرويد 12+).
 *
 *  1) setMode(IN_COMMUNICATION): التطبيق يبقى "مالك وضع الصوت"، لأن النظام بيطبّق طلبات المسار من المالك بس.
 *  2) مسار صوت صامت بنوع VOICE_COMMUNICATION شغّال طول الوقت، عشان النظام يعتبرنا جلسة اتصال صوتي شغّالة
 *     ومايسحبش المسار مننا.
 *  3) setCommunicationDevice(السماعة الداخلية).
 *  4) مستمع لتغيّر الجهاز + فحص دوري احتياطي، بيعيدوا التفعيل لو النظام نقل الصوت.
 *
 * كله على الـ main thread. stop() بترجّع كل حاجة لأصلها.
 */
final class SpeakerRoute {

    private static final long BACKUP_INTERVAL_MS = 15000;
    private static final int RATE = 8000;

    private static SpeakerRoute instance;

    static synchronized SpeakerRoute get(Context c) {
        if (instance == null) instance = new SpeakerRoute(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AudioTrack keepAlive;
    private AudioManager.OnCommunicationDeviceChangedListener listener;
    /** هل المطلوب إن السماعة تفضل شغّالة؟ */
    private boolean wanted;
    /** هل بدأنا نغيّر في الصوت (يعني stop() عليها شغل ترجّعه)؟ */
    private boolean touched;
    /** وضع الصوت قبل ما ناخده. */
    private int savedMode = -1;

    private final Runnable backup = new Runnable() {
        @Override
        public void run() {
            if (!wanted) return;
            if (!isActive() || keepAliveStopped()) force();
            main.postDelayed(this, BACKUP_INTERVAL_MS);
        }
    };

    private SpeakerRoute(Context c) {
        app = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    /** شغّل السماعة وخليها شغّالة. بيرجّع هل السماعة اشتغلت فعلاً. */
    boolean start() {
        if (!touched) {
            touched = true;
            try {
                savedMode = am.getMode();
            } catch (Exception ignored) {
            }
        }
        wanted = true;
        force();
        startListening();
        main.removeCallbacks(backup);
        main.postDelayed(backup, BACKUP_INTERVAL_MS);
        return isActive();
    }

    /**
     * رجّع كل حاجة لأصلها.
     *
     * @param callEnded لو المكالمة خلصت بنرجّع MODE_NORMAL، وغير كده (قفل يدوي وسط مكالمة) بنرجّع الوضع القديم.
     */
    void stop(boolean callEnded) {
        if (!touched) return;
        touched = false;
        wanted = false;
        main.removeCallbacks(backup);
        stopListening();
        stopKeepAlive();
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
        } catch (Exception ignored) {
        }
        try {
            am.setSpeakerphoneOn(false);
        } catch (Exception ignored) {
        }
        try {
            am.setMode(callEnded || savedMode < 0 ? AudioManager.MODE_NORMAL : savedMode);
        } catch (Exception ignored) {
        }
        savedMode = -1;
        EventLog.add("route stop (callEnded=" + callEnded + ")");
    }

    /** هل الصوت فعلاً خارج من السماعة الخارجية دلوقتي؟ */
    @SuppressWarnings("deprecation")
    boolean isActive() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo d = am.getCommunicationDevice();
                return d != null && d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            }
            return am.isSpeakerphoneOn();
        } catch (Exception e) {
            return false;
        }
    }

    /** وصف قصير للتشخيص. */
    String describe() {
        return "wanted=" + wanted + " keepAlive=" + (keepAlive == null ? "none"
                : (keepAlive.getPlayState() == AudioTrack.PLAYSTATE_PLAYING ? "playing" : "stopped"));
    }

    // ------------------------------------------------------------------ داخلي

    @SuppressWarnings("deprecation")
    private void force() {
        StringBuilder log = new StringBuilder();
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Exception e) {
            log.append("setMode خطأ ").append(e.getClass().getSimpleName()).append(' ');
        }
        startKeepAlive();
        boolean ok = false;
        try {
            AudioDeviceInfo sp = builtInSpeaker();
            if (sp != null) ok = am.setCommunicationDevice(sp);
            else log.append("مفيش سماعة داخلية في القايمة ");
        } catch (Exception e) {
            log.append("setCommunicationDevice خطأ ").append(e.getClass().getSimpleName()).append(' ');
        }
        if (!ok) {
            try {
                am.setSpeakerphoneOn(true);
            } catch (Exception ignored) {
            }
        }
        EventLog.add("route force: " + log + "setCommDev=" + ok + " mode=" + am.getMode()
                + " " + describe() + " active=" + isActive());
    }

    private AudioDeviceInfo builtInSpeaker() {
        if (Build.VERSION.SDK_INT < 31) return null;
        for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return d;
        }
        return null;
    }

    private void startListening() {
        if (Build.VERSION.SDK_INT < 31 || listener != null) return;
        listener = new AudioManager.OnCommunicationDeviceChangedListener() {
            @Override
            public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                if (wanted && !isActive()) force();
            }
        };
        try {
            am.addOnCommunicationDeviceChangedListener(app.getMainExecutor(), listener);
        } catch (Exception e) {
            listener = null;
        }
    }

    private void stopListening() {
        if (Build.VERSION.SDK_INT < 31 || listener == null) return;
        try {
            am.removeOnCommunicationDeviceChangedListener(listener);
        } catch (Exception ignored) {
        }
        listener = null;
    }

    private boolean keepAliveStopped() {
        return keepAlive == null || keepAlive.getPlayState() != AudioTrack.PLAYSTATE_PLAYING;
    }

    /** بيشغّل ثانية صمت متكررة عشان النظام يسيب جلسة الصوت بتاعتنا شغّالة. */
    private void startKeepAlive() {
        if (!keepAliveStopped()) return;
        stopKeepAlive();
        try {
            int frames = RATE; // ثانية صمت
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(frames * 2)
                    .build();
            t.write(new byte[frames * 2], 0, frames * 2);
            t.setLoopPoints(0, frames, -1);
            AudioDeviceInfo sp = builtInSpeaker();
            if (sp != null) t.setPreferredDevice(sp);
            t.play();
            keepAlive = t;
        } catch (Exception e) {
            EventLog.add("route keepAlive فشل: " + e.getClass().getSimpleName());
        }
    }

    private void stopKeepAlive() {
        if (keepAlive == null) return;
        try {
            keepAlive.stop();
        } catch (Exception ignored) {
        }
        keepAlive.release();
        keepAlive = null;
    }
}
