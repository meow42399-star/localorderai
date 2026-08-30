package com.localorderai.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import com.localorderai.R;
import com.localorderai.data.AppConfig;
import com.localorderai.data.AppDatabase;
import com.localorderai.data.OrderRecord;
import com.localorderai.services.CampaignForegroundService;
import com.localorderai.services.OverlayBubbleService;
import com.localorderai.utils.UpdateManager;

import java.util.concurrent.Executors;

/**
 * DashboardFragment
 * -------------------
 * الشاشة الرئيسية: إعدادات الاتصال التلقائي، إضافة طلبات، بدء/إيقاف الحملة.
 * (كانت DashboardActivity قبل ما ننقلها جوه Bottom Navigation)
 */
public class DashboardFragment extends Fragment {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextInputEditText editCustomerName, editPhoneNumber, editOrderReference;
    private TextInputEditText editBulkOrders;
    private SeekBar seekMaxAttempts, seekDelaySeconds;
    private SwitchMaterial switchRecording, switchAutoDialing;
    private MaterialButtonToggleGroup toggleSimSlot;
    private TextView txtStatus, txtMaxAttemptsValue, txtDelayValue;
    private TextView txtUpdateStatus;

    private AppConfig config;
    private AppDatabase db;
    private UpdateManager updateManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        config = new AppConfig(requireContext());
        db = AppDatabase.getInstance(requireContext());
        updateManager = new UpdateManager(requireContext());

        bindViews(view);
        loadSettings();
        setupListeners();
        requestNecessaryPermissions();
    }

    private void bindViews(View root) {
        editCustomerName = root.findViewById(R.id.editCustomerName);
        editPhoneNumber = root.findViewById(R.id.editPhoneNumber);
        editOrderReference = root.findViewById(R.id.editOrderReference);
        editBulkOrders = root.findViewById(R.id.editBulkOrders);
        seekMaxAttempts = root.findViewById(R.id.seekMaxAttempts);
        seekDelaySeconds = root.findViewById(R.id.seekDelaySeconds);
        switchRecording = root.findViewById(R.id.switchRecording);
        switchAutoDialing = root.findViewById(R.id.switchAutoDialing);
        toggleSimSlot = root.findViewById(R.id.toggleSimSlot);
        txtStatus = root.findViewById(R.id.txtStatus);
        txtMaxAttemptsValue = root.findViewById(R.id.txtMaxAttemptsValue);
        txtDelayValue = root.findViewById(R.id.txtDelayValue);
        txtUpdateStatus = root.findViewById(R.id.txtUpdateStatus);
    }

    private void loadSettings() {
        seekMaxAttempts.setProgress(config.getMaxAttempts());
        seekDelaySeconds.setProgress(config.getDelaySeconds());
        switchRecording.setChecked(config.isRecordingEnabled());
        switchAutoDialing.setChecked(config.isAutoDialingEnabled());
        updateDelayLabel();
        updateAttemptsLabel();

        int simSlot = config.getSimSlot();
        if (simSlot == AppConfig.SIM_SLOT_1) {
            toggleSimSlot.check(R.id.btnSim1);
        } else if (simSlot == AppConfig.SIM_SLOT_2) {
            toggleSimSlot.check(R.id.btnSim2);
        } else {
            toggleSimSlot.check(R.id.btnSimAsk);
        }
    }

    private void setupListeners() {
        View root = requireView();
        MaterialButton btnSaveSettings = root.findViewById(R.id.btnSaveSettings);
        MaterialButton btnAddOrder = root.findViewById(R.id.btnAddOrder);
        MaterialButton btnStartCampaign = root.findViewById(R.id.btnStartCampaign);
        MaterialButton btnStopCampaign = root.findViewById(R.id.btnStopCampaign);
        MaterialButton btnViewLogs = root.findViewById(R.id.btnViewLogs);

        btnSaveSettings.setOnClickListener(v -> saveSettings());
        MaterialButton btnValidate = root.findViewById(R.id.btnValidatePhone);
        btnValidate.setOnClickListener(v -> validatePhone());
        btnAddOrder.setOnClickListener(v -> addOrder());
        btnStartCampaign.setOnClickListener(v -> startCampaign());
        btnStopCampaign.setOnClickListener(v -> stopCampaign());
        if (btnViewLogs != null) {
            // "عرض السجلات" دلوقتي بقى تاب في الـ bottom nav بدل ما يفتح
            // شاشة جديدة، فبنبدّل التاب مباشرة لو الزرار موجود لسه.
            btnViewLogs.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).selectTab(R.id.nav_logs);
                }
            });
        }

        MaterialButton btnBulkAdd = root.findViewById(R.id.btnBulkAdd);
        btnBulkAdd.setOnClickListener(v -> addBulkOrders());

        MaterialButton btnCheckUpdate = root.findViewById(R.id.btnCheckUpdate);
        if (btnCheckUpdate != null) {
            btnCheckUpdate.setOnClickListener(v -> checkForUpdate());
        }

        seekMaxAttempts.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateAttemptsLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekDelaySeconds.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateDelayLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void saveSettings() {
        config.setMaxAttempts(Math.max(seekMaxAttempts.getProgress(), 1));
        config.setDelaySeconds(Math.max(seekDelaySeconds.getProgress(), 1));
        config.setRecordingEnabled(switchRecording.isChecked());
        config.setAutoDialingEnabled(switchAutoDialing.isChecked());
        config.setSimSlot(selectedSimSlot());

        Toast.makeText(requireContext(), "تم حفظ الإعدادات بنجاح", Toast.LENGTH_SHORT).show();
    }

    private int selectedSimSlot() {
        int checkedId = toggleSimSlot.getCheckedButtonId();
        if (checkedId == R.id.btnSim1) return AppConfig.SIM_SLOT_1;
        if (checkedId == R.id.btnSim2) return AppConfig.SIM_SLOT_2;
        return AppConfig.SIM_SLOT_ASK_SYSTEM;
    }

    private void addOrder() {
        String name = safeText(editCustomerName);
        String phone = safeText(editPhoneNumber);
        String orderRef = safeText(editOrderReference);

        if (name.isEmpty() || phone.isEmpty()) {
            Toast.makeText(requireContext(), "من فضلك أدخل اسم العميل ورقم الهاتف", Toast.LENGTH_SHORT).show();
            return;
        }

        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 10 || digits.length() > 15) {
            Toast.makeText(requireContext(), "❌ رقم الهاتف غير صالح (10-15 رقم)", Toast.LENGTH_LONG).show();
            return;
        }

        OrderRecord record = new OrderRecord(name, phone, orderRef);

        Executors.newSingleThreadExecutor().execute(() -> {
            db.orderRecordDao().insert(record);
            requireActivity().runOnUiThread(() -> {
                Toast.makeText(requireContext(), "تمت إضافة الطلب بنجاح", Toast.LENGTH_SHORT).show();
                editCustomerName.setText("");
                editPhoneNumber.setText("");
                editOrderReference.setText("");
            });
        });
    }

    /**
     * إضافة عدة طلبات دفعة واحدة من مربع نص:
     * سطر اسم، يليه مباشرة سطر رقم هاتف، وهكذا بالتبادل.
     */
    private void addBulkOrders() {
        String raw = editBulkOrders.getText() != null ? editBulkOrders.getText().toString() : "";
        if (raw.trim().isEmpty()) {
            Toast.makeText(requireContext(), "من فضلك اكتب الأسماء والأرقام أولًا", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] rawLines = raw.split("\\r?\\n");
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String line : rawLines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }

        if (lines.isEmpty()) {
            Toast.makeText(requireContext(), "من فضلك اكتب الأسماء والأرقام أولًا", Toast.LENGTH_SHORT).show();
            return;
        }

        java.util.List<OrderRecord> toInsert = new java.util.ArrayList<>();
        java.util.List<String> skipped = new java.util.ArrayList<>();

        int i = 0;
        while (i < lines.size()) {
            String current = lines.get(i);
            boolean currentIsPhone = looksLikePhone(current);

            if (currentIsPhone) {
                skipped.add(current + " (رقم من غير اسم قبله)");
                i++;
                continue;
            }

            if (i + 1 >= lines.size()) {
                skipped.add(current + " (مفيش رقم بعده)");
                break;
            }

            String name = current;
            String phone = lines.get(i + 1);
            String digits = phone.replaceAll("[^0-9]", "");

            if (digits.length() < 10 || digits.length() > 15) {
                skipped.add(name + " -> " + phone + " (رقم غير صالح)");
                i += 2;
                continue;
            }

            toInsert.add(new OrderRecord(name, phone, ""));
            i += 2;
        }

        if (toInsert.isEmpty()) {
            Toast.makeText(requireContext(), "مفيش أي اسم ورقم اتقرا صح — تأكد إن كل اسم في سطر لوحده والرقم في السطر اللي بعده", Toast.LENGTH_LONG).show();
            return;
        }

        java.util.List<OrderRecord> finalToInsert = toInsert;
        java.util.List<String> finalSkipped = skipped;
        Executors.newSingleThreadExecutor().execute(() -> {
            for (OrderRecord record : finalToInsert) {
                db.orderRecordDao().insert(record);
            }
            requireActivity().runOnUiThread(() -> {
                String msg = "تمت إضافة " + finalToInsert.size() + " عميل بالترتيب";
                if (!finalSkipped.isEmpty()) {
                    msg += " - اتجاهل " + finalSkipped.size() + " سطر (" + finalSkipped.get(0) + ")";
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                editBulkOrders.setText("");
            });
        });
    }

    /** بيحدد هل السطر ده شكله رقم هاتف (أغلبه أرقام) ولا اسم */
    private boolean looksLikePhone(String line) {
        String digitsOnly = line.replaceAll("[^0-9]", "");
        return digitsOnly.length() >= (line.replaceAll("\\s", "").length() * 6 / 10);
    }

    private void startCampaign() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "يجب منح إذن الاتصال لإجراء Auto Dial.", Toast.LENGTH_LONG).show();
            requestNecessaryPermissions();
            return;
        }

        if (!hasOverlayPermission()) {
            Toast.makeText(requireContext(), "من فضلك فعّل إذن \"الظهور فوق التطبيقات الأخرى\" أولًا", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }

        // ملحوظة: مش لازم LocalOrderAI يبقى Default Phone App عشان
        // الاتصال المتكرر (redial) يشتغل. الحملة بتتابع حالة المكالمة
        // عن طريق TelephonyManager مباشرة (TelephonyCallStateListener)،
        // اللي بيحتاج بس READ_PHONE_STATE (ممنوح أصلًا)، ومبيحتاجش
        // دور Default Dialer خالص. لو حد فعّل Default Dialer برضه هيشتغل
        // عادي جنبها من غير تعارض.

        // بنبدأ الأوفر الأول قبل خدمة الحملة، عشان المستقبِل بتاعه
        // يبقى مسجل قبل ما أي broadcast يتبعت. وحتى لو حصل تأخير،
        // الأوفر بيقرا آخر حالة محفوظة من AppConfig فورًا برضو.
        try {
            Intent bubbleIntent = new Intent(requireContext(), OverlayBubbleService.class);
            requireContext().startService(bubbleIntent);
        } catch (Exception e) {
            e.printStackTrace();
        }

        Intent serviceIntent = new Intent(requireContext(), CampaignForegroundService.class);
        try {
            ContextCompat.startForegroundService(requireContext(), serviceIntent);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(requireContext(), "فشل بدء الخدمة: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        txtStatus.setText("الحملة شغالة الآن ✅");
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(requireContext());
    }

    private void requestOverlayPermission() {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopCampaign() {
        Intent serviceIntent = new Intent(requireContext(), CampaignForegroundService.class);
        serviceIntent.setAction("STOP");
        requireContext().startService(serviceIntent);

        Intent bubbleStop = new Intent(requireContext(), OverlayBubbleService.class);
        bubbleStop.setAction("STOP");
        requireContext().startService(bubbleStop);

        txtStatus.setText("تم إيقاف الحملة بنجاح ⏹️");
    }

    /**
     * فحص يدوي بس (مفيش شيك تلقائي في الخلفية ولا عند فتح التطبيق).
     * لو فيه نسخة أحدث، بينزّلها ويفتح شاشة التثبيت أوتوماتيكي
     * بعد ما التحميل يخلص — المستخدم برضو لازم يضغط "تثبيت" يدويًا،
     * ده مطلب نظام أندرويد مفيش طريقة نتخطاه.
     */
    private void checkForUpdate() {
        if (txtUpdateStatus != null) txtUpdateStatus.setText("جاري التحقق من التحديثات...");

        updateManager.checkForUpdate(new UpdateManager.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String versionName, String downloadUrl) {
                if (txtUpdateStatus != null) {
                    txtUpdateStatus.setText("فيه نسخة جديدة (" + versionName + ") - جاري التحميل...");
                }
                startDownload(versionName, downloadUrl);
            }

            @Override
            public void onUpToDate() {
                if (txtUpdateStatus != null) txtUpdateStatus.setText("التطبيق على آخر نسخة ✅");
                Toast.makeText(requireContext(), "التطبيق على آخر نسخة بالفعل", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (txtUpdateStatus != null) txtUpdateStatus.setText("تعذر التحقق من التحديثات");
                Toast.makeText(requireContext(), "فشل التحقق: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void startDownload(String versionName, String downloadUrl) {
        if (!ensureInstallPermission()) return;

        updateManager.downloadAndInstall(downloadUrl, versionName, new UpdateManager.DownloadCallback() {
            @Override
            public void onDownloadStarted() {
                Toast.makeText(requireContext(), "بدأ تحميل التحديث في الخلفية", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDownloadFailed(String message) {
                if (txtUpdateStatus != null) txtUpdateStatus.setText("فشل تحميل التحديث");
                Toast.makeText(requireContext(), "فشل التحميل: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * على أندرويد 8+ لازم المستخدم يفعّل "تثبيت من مصادر غير معروفة"
     * للتطبيق ده تحديدًا قبل ما شاشة التثبيت تفتح، وإلا هتتقفل.
     * لو مش مفعّل، بنوجهه لشاشة الإعدادات المخصصة للتطبيق.
     */
    private boolean ensureInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;

        boolean canInstall = requireContext().getPackageManager().canRequestPackageInstalls();
        if (canInstall) return true;

        Toast.makeText(requireContext(),
                "من فضلك فعّل \"تثبيت من مصادر غير معروفة\" لهذا التطبيق أولًا",
                Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        View root = getView();
        if (root == null) return;

        View dotCall = root.findViewById(R.id.dotCall);
        View dotRecord = root.findViewById(R.id.dotRecord);
        View dotNotif = root.findViewById(R.id.dotNotif);
        View dotDialer = root.findViewById(R.id.dotDialer);

        boolean callOk = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        boolean recOk = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notifOk = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifOk = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        boolean isDefaultDialer = isDefaultDialerApp();

        if (dotCall != null) dotCall.setBackgroundResource(callOk ? R.drawable.status_dot_green : R.drawable.status_dot_red);
        if (dotRecord != null) dotRecord.setBackgroundResource(recOk ? R.drawable.status_dot_green : R.drawable.status_dot_red);
        if (dotNotif != null) dotNotif.setBackgroundResource(notifOk ? R.drawable.status_dot_green : R.drawable.status_dot_red);
        if (dotDialer != null) dotDialer.setBackgroundResource(isDefaultDialer ? R.drawable.status_dot_green : R.drawable.status_dot_red);

        View tvDialer = root.findViewById(R.id.tvDialerPermission);
        if (tvDialer != null) {
            tvDialer.setOnClickListener(v -> {
                if (!isDefaultDialer) {
                    promptSetDefaultDialer();
                } else {
                    Toast.makeText(requireContext(), "التطبيق مضبوط كـ Default Phone App بالفعل ✅", Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (dotDialer != null && !isDefaultDialer) {
            dotDialer.setOnClickListener(v -> promptSetDefaultDialer());
        }
    }

    /**
     * إعادة الاتصال التلقائي (redial) بعد ما مكالمة تخلص بتعتمد على
     * OrderInCallService، واللي مش بيستقبل أي أحداث من نظام التليفون
     * إلا لو التطبيق متظبط كـ "Default Phone App".
     */
    private boolean isDefaultDialerApp() {
        try {
            android.telecom.TelecomManager telecomManager =
                    (android.telecom.TelecomManager) requireContext().getSystemService(android.content.Context.TELECOM_SERVICE);
            if (telecomManager == null) return false;
            return requireContext().getPackageName().equals(telecomManager.getDefaultDialerPackage());
        } catch (Exception e) {
            return false;
        }
    }

    private void promptSetDefaultDialer() {
        try {
            Intent intent = new Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            intent.putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, requireContext().getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "من فضلك فعّل التطبيق كـ Default Phone App يدويًا من إعدادات النظام حتى يعمل الاتصال المتكرر التلقائي", Toast.LENGTH_LONG).show();
        }
    }

    private void validatePhone() {
        String phone = safeText(editPhoneNumber);
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() >= 10 && digits.length() <= 15) {
            Toast.makeText(requireContext(), "✅ رقم الهاتف صالح", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(requireContext(), "❌ رقم الهاتف غير صالح (10-15 رقم)", Toast.LENGTH_LONG).show();
        }
    }

    private void updateAttemptsLabel() {
        int attempts = Math.max(1, Math.min(seekMaxAttempts.getProgress(), 100));
        txtMaxAttemptsValue.setText("القيمة الحالية: " + attempts + " محاولة");
    }

    private void updateDelayLabel() {
        int seconds = Math.max(1, Math.min(seekDelaySeconds.getProgress(), 999));
        txtDelayValue.setText("القيمة الحالية: " + formatDelay(seconds));
    }

    private String formatDelay(int seconds) {
        if (seconds < 60) {
            return seconds + " sec";
        }
        int minutes = seconds / 60;
        int remainingSeconds = seconds % 60;
        if (remainingSeconds == 0) {
            return minutes + " min";
        }
        return minutes + " min " + remainingSeconds + " sec";
    }

    private String safeText(TextInputEditText field) {
        return field.getText() != null ? field.getText().toString().trim() : "";
    }

    private void requestNecessaryPermissions() {
        String[] permissions = {
                Manifest.permission.CALL_PHONE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.POST_NOTIFICATIONS
        };

        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(requireContext(), p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(requireActivity(), permissions, PERMISSION_REQUEST_CODE);
        }
    }
}
