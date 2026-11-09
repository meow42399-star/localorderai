package com.localorderai;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.telecom.CallAudioState;

/** تحكم في المكبر والميوت: عن طريق InCallService لو متاح، وإلا عن طريق AudioManager (مش مضمون على كل الموبايلات). */
final class AudioControl {

    private AudioControl() {
    }

    private static AudioManager am(Context c) {
        return (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    static boolean isSpeakerOn(Context c) {
        DialerInCallService s = DialerInCallService.instance;
        if (s != null) {
            CallAudioState st = s.getCallAudioState();
            return st != null && st.getRoute() == CallAudioState.ROUTE_SPEAKER;
        }
        return am(c).isSpeakerphoneOn();
    }

    static void setSpeaker(Context c, boolean on) {
        DialerInCallService s = DialerInCallService.instance;
        if (s != null) {
            s.setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_WIRED_OR_EARPIECE);
            return;
        }
        AudioManager a = am(c);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    for (AudioDeviceInfo d : a.getAvailableCommunicationDevices()) {
                        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            a.setCommunicationDevice(d);
                            break;
                        }
                    }
                } else {
                    a.clearCommunicationDevice();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            a.setSpeakerphoneOn(on);
        } catch (Exception ignored) {
        }
    }

    static boolean isMuted(Context c) {
        DialerInCallService s = DialerInCallService.instance;
        if (s != null) {
            CallAudioState st = s.getCallAudioState();
            return st != null && st.isMuted();
        }
        return am(c).isMicrophoneMute();
    }

    static void setMuted(Context c, boolean muted) {
        DialerInCallService s = DialerInCallService.instance;
        if (s != null) {
            s.setMuted(muted);
            return;
        }
        try {
            am(c).setMicrophoneMute(muted);
        } catch (Exception ignored) {
        }
    }

    static void reset(Context c) {
        setSpeaker(c, false);
        setMuted(c, false);
    }
}
