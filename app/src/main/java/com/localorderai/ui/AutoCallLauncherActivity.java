package com.localorderai.ui;

import com.localorderai.utils.AppLogger;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.localorderai.data.AppConfig;

import java.util.List;

/**
 * AutoCallLauncherActivity
 * -------------------------
 * نشاط شفاف (بدون UI) هدفه الوحيد إطلاق مكالمة (ACTION_CALL) من
 * سياق Activity حقيقي بدل ما نحاول نطلقها مباشرة من Service.
 *
 * السبب: بدء أي Activity من الخلفية (زي مكالمة ACTION_CALL أو حتى
 * TelecomManager.placeCall في بعض أجهزة الـ OEM) بيتقيّد على
 * Android 10+ إلا لو التطبيق عنده استثناء صريح. إذن
 * SYSTEM_ALERT_WINDOW (اللي التطبيق أصلاً بيطلبه للفقاعة العائمة)
 * هو أحد الاستثناءات الرسمية المعترف بيها من Android لبدء الأنشطة
 * من الخلفية — فبنستغله هنا عشان الاتصال يشتغل من غير ما يبقى
 * التطبيق Default Dialer.
 *
 * كمان بيقرأ إعداد "شريحة الاتصال" (SIM 1 / SIM 2) من AppConfig
 * ويحدد PhoneAccountHandle المناسب قبل إطلاق المكالمة، عشان كل
 * مكالمة تطلع من الشريحة اللي المستخدم اختارها من الإعدادات.
 */
public class AutoCallLauncherActivity extends Activity {

    private static final String TAG = "AutoCallLauncher";
    public static final String EXTRA_PHONE_NUMBER = "phone_number";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String phoneNumber = getIntent().getStringExtra(EXTRA_PHONE_NUMBER);

        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            AppLogger.e(TAG, "No phone number provided");
            finish();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            AppLogger.e(TAG, "CALL_PHONE permission not granted");
            finish();
            return;
        }

        try {
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + phoneNumber));

            PhoneAccountHandle handle = resolveSelectedSimAccount();
            if (handle != null) {
                callIntent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);
            }

            startActivity(callIntent);
            Log.d(TAG, "ACTION_CALL dispatched for: " + phoneNumber
                    + (handle != null ? " via selected SIM" : " (default SIM)"));
        } catch (SecurityException e) {
            AppLogger.e(TAG, "SecurityException while placing call", e);
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to place call", e);
        } finally {
            finish();
        }
    }

    /**
     * بيرجع PhoneAccountHandle للشريحة اللي المستخدم اختارها من الإعدادات
     * (SIM 1 أو SIM 2)، أو null لو الإعداد "اسأل النظام"/جهاز شريحة واحدة
     * أو لو مفيش إذن READ_PHONE_STATE.
     */
    private PhoneAccountHandle resolveSelectedSimAccount() {
        int simSlot = new AppConfig(this).getSimSlot();
        if (simSlot != AppConfig.SIM_SLOT_1 && simSlot != AppConfig.SIM_SLOT_2) {
            return null; // المستخدم اختار "اسأل النظام"
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            AppLogger.w(TAG, "READ_PHONE_STATE not granted - cannot resolve SIM accounts, using default");
            return null;
        }

        try {
            TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (telecomManager == null) return null;

            List<PhoneAccountHandle> accounts = telecomManager.getCallCapablePhoneAccounts();
            if (accounts == null || accounts.isEmpty()) return null;

            if (simSlot < accounts.size()) {
                return accounts.get(simSlot);
            }

            AppLogger.w(TAG, "Requested SIM slot " + simSlot + " not available, only " + accounts.size() + " found");
            return null;
        } catch (SecurityException e) {
            AppLogger.e(TAG, "SecurityException reading phone accounts", e);
            return null;
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to resolve SIM account", e);
            return null;
        }
    }
}
