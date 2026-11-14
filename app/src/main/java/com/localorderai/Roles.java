package com.localorderai;

import android.app.role.RoleManager;
import android.content.Context;

/** هل التطبيق هو الـ Default Dialer دلوقتي؟ (الوضع الكامل) */
final class Roles {

    private Roles() {
    }

    static boolean hasDialer(Context c) {
        RoleManager rm = c.getSystemService(RoleManager.class);
        return rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && rm.isRoleHeld(RoleManager.ROLE_DIALER);
    }
}
