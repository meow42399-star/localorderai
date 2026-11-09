package com.localorderai;

import android.content.Context;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

/** متابعة حالة الاتصال على أندرويد 12+ (الوضع البسيط). */
final class StateCallback31 extends TelephonyCallback implements TelephonyCallback.CallStateListener {

    private final CallQueue queue;

    private StateCallback31(CallQueue queue) {
        this.queue = queue;
    }

    @Override
    public void onCallStateChanged(int state) {
        queue.onPhoneState(state);
    }

    static Object register(Context c, CallQueue q) {
        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        StateCallback31 cb = new StateCallback31(q);
        tm.registerTelephonyCallback(c.getMainExecutor(), cb);
        return cb;
    }

    static void unregister(Context c, Object cb) {
        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        tm.unregisterTelephonyCallback((StateCallback31) cb);
    }
}
