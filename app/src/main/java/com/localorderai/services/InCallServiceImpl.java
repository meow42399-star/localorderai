package com.localorderai.services;

import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

import com.localorderai.utils.AppLogger;

/**
 * InCallServiceImpl
 * ------------------
 * FIX: مطلوبة عشان التطبيق يبقى "مؤهل" يظهر كخيار Default Dialer عند
 * أندرويد أصلاً (شرط النظام: أي تطبيق يطلب دور RoleManager.ROLE_DIALER
 * لازم يكون عنده InCallService مسجلة، وإلا الطلب بيترفض من الأساس).
 *
 * راجعنا كود تطبيق auto-dialer تجاري حقيقي (decompiled) بيستخدم نفس
 * الطريقة بالظبط: InCallService عندهم فاضية تقريبًا (onCreate بس)،
 * لأن منطق متابعة حالة المكالمة الفعلي (رن/رد/انتهاء) بيتم عن طريق
 * TelephonyManager.listen() العادي في مكان تاني (TelephonyCallStateListener
 * عندنا) — مش عن طريق Call.Callback هنا. يعني الكلاس ده مش المصدر
 * الأساسي لمعلومات المكالمة، هو بس "تصريح" للنظام إن التطبيق مؤهل.
 *
 * أي استثناء هنا لازم يتمسك ويتسجل — الكلاس ده حساس لأنه لو وقع، ممكن
 * يفقد التطبيق دور Default Dialer بالكامل وقت التشغيل.
 */
public class InCallServiceImpl extends InCallService {

    private static final String TAG = "InCallServiceImpl";

    @Override
    public void onCreate() {
        try {
            super.onCreate();
            AppLogger.w(TAG, "InCallServiceImpl created — app registered as dialer-eligible");
        } catch (Exception e) {
            // مفيش fallback ممكن هنا غير التسجيل — الاستثناء لازم
            // يوصل للنظام في النهاية عشان مايفضلش الحالة متضاربة،
            // لكن على الأقل نعرف السبب من اللوج بدل صمت تام.
            AppLogger.e(TAG, "InCallServiceImpl.onCreate failed", e);
            throw e;
        }
    }

    /**
     * مش بنستخدم Call.Callback هنا للتفرقة بين رن/رد (زي ما التطبيق
     * التجاري بيعمل بالظبط) — بس بنسجل في اللوج لو مكالمة اتضافت،
     * يفيد في تشخيص أي مشكلة مستقبلية تتعلق بدور الـ Dialer نفسه.
     */
    @Override
    public void onCallAdded(Call call) {
        try {
            super.onCallAdded(call);
            AppLogger.w(TAG, "onCallAdded: state=" + call.getState());
        } catch (Exception e) {
            AppLogger.e(TAG, "onCallAdded failed", e);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        try {
            super.onCallRemoved(call);
        } catch (Exception e) {
            AppLogger.e(TAG, "onCallRemoved failed", e);
        }
    }
}
