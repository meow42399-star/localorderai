package com.localorderai;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    private static final int REQ_ROLE = 2;

    private EditText numbersEdit, ringEdit;
    private CheckBox speakerCheck;
    private TextView statusView, resultsView;
    private Button roleBtn, startBtn, stopBtn;
    private SharedPreferences prefs;

    private final CallQueue.Listener queueListener = new CallQueue.Listener() {
        @Override
        public void onQueueChanged() {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 100, 40, 40);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("الاتصال التلقائي");
        title.setTextSize(24);
        root.addView(title);

        numbersEdit = new EditText(this);
        numbersEdit.setHint("اكتب كل رقم في سطر");
        numbersEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        numbersEdit.setMinLines(6);
        numbersEdit.setGravity(Gravity.TOP | Gravity.START);
        numbersEdit.setText(prefs.getString("numbers", ""));
        root.addView(numbersEdit);

        speakerCheck = new CheckBox(this);
        speakerCheck.setText("شغّل المكبر تلقائياً أول ما المكالمة ترد");
        speakerCheck.setChecked(prefs.getBoolean("speaker", true));
        root.addView(speakerCheck);

        TextView ringLabel = new TextView(this);
        ringLabel.setText("أقصى مدة رنين (ثواني) قبل الانتقال للرقم التالي:");
        root.addView(ringLabel);
        ringEdit = new EditText(this);
        ringEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        ringEdit.setText(String.valueOf(prefs.getInt("ring", 45)));
        root.addView(ringEdit);

        roleBtn = new Button(this);
        roleBtn.setText("تفعيل كتطبيق الاتصال الافتراضي (اختياري)");
        root.addView(roleBtn);
        startBtn = new Button(this);
        startBtn.setText("ابدأ");
        root.addView(startBtn);
        stopBtn = new Button(this);
        stopBtn.setText("إيقاف");
        root.addView(stopBtn);

        statusView = new TextView(this);
        statusView.setPadding(0, 24, 0, 8);
        root.addView(statusView);
        resultsView = new TextView(this);
        resultsView.setTextSize(16);
        root.addView(resultsView);

        TextView version = new TextView(this);
        version.setText("الإصدار " + BuildConfig.VERSION_NAME);
        version.setPadding(0, 40, 0, 0);
        root.addView(version);

        setContentView(scroll);

        roleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestDialerRole();
            }
        });
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStartClicked();
            }
        });
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallQueue.get().stop();
            }
        });

        UpdateChecker.check(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallQueue.get().addListener(queueListener);
        render();
    }

    @Override
    protected void onStop() {
        CallQueue.get().removeListener(queueListener);
        super.onStop();
    }

    private boolean hasDialerRole() {
        RoleManager rm = getSystemService(RoleManager.class);
        return rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)
                && rm.isRoleHeld(RoleManager.ROLE_DIALER);
    }

    private void requestDialerRole() {
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            Toast.makeText(this, "الجهاز ده مش بيدعم دور الاتصال", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        render();
    }

    private void onStartClicked() {
        List<String> missing = new ArrayList<>();
        String[] needed = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS};
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
            return;
        }
        proceedStart();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            proceedStart();
        } else {
            Toast.makeText(this, "لازم تسمح بصلاحية الاتصال", Toast.LENGTH_LONG).show();
        }
    }

    private void proceedStart() {
        List<String> nums = parse(numbersEdit.getText().toString());
        if (nums.isEmpty()) {
            Toast.makeText(this, "اكتب رقم واحد على الأقل", Toast.LENGTH_SHORT).show();
            return;
        }
        int ring = 45;
        try {
            ring = Integer.parseInt(ringEdit.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        prefs.edit()
                .putString("numbers", numbersEdit.getText().toString())
                .putBoolean("speaker", speakerCheck.isChecked())
                .putInt("ring", ring)
                .apply();
        try {
            startForegroundService(new Intent(this, QueueService.class));
        } catch (Exception ignored) {
        }
        CallQueue.get().start(this, nums, ring, speakerCheck.isChecked(), !hasDialerRole());
    }

    /** بيحوّل الأرقام العربية لإنجليزي ويشيل المسافات والشرطات وأي حروف. */
    static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            StringBuilder sb = new StringBuilder();
            for (char c : line.toCharArray()) {
                if (c >= '\u0660' && c <= '\u0669') {
                    sb.append((char) ('0' + (c - '\u0660')));
                } else if (c >= '\u06F0' && c <= '\u06F9') {
                    sb.append((char) ('0' + (c - '\u06F0')));
                } else if ((c >= '0' && c <= '9') || c == '+' || c == '*' || c == '#') {
                    sb.append(c);
                }
            }
            if (sb.length() > 0) out.add(sb.toString());
        }
        return out;
    }

    private void render() {
        CallQueue q = CallQueue.get();
        StringBuilder st = new StringBuilder();
        st.append(hasDialerRole()
                ? "الوضع الكامل: التطبيق هو الاتصال الافتراضي (بيعرف مين قفل + مكبر مضمون)"
                : "الوضع البسيط: المكبر بيشتغل حسب نوع الموبايل ومش بيعرف مين قفل");
        if (q.isRunning()) {
            st.append("\nشغال: رقم ").append(q.getCurrentPosition()).append(" من ").append(q.getTotal());
        }
        statusView.setText(st.toString());
        StringBuilder rs = new StringBuilder();
        for (String r : q.getResults()) rs.append(r).append('\n');
        resultsView.setText(rs.toString());
        startBtn.setEnabled(!q.isRunning());
        stopBtn.setEnabled(q.isRunning());
        roleBtn.setVisibility(hasDialerRole() ? View.GONE : View.VISIBLE);
    }
}
