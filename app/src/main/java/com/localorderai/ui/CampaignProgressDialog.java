package com.localorderai.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.os.Handler;
import android.os.Looper;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.localorderai.R;
import com.localorderai.ui.CampaignLogAdapter.LogEntry;

public class CampaignProgressDialog {

    public static final String ACTION_PROGRESS = "LOCALORDERAI_CAMPAIGN_PROGRESS";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_PROCESSED = "processed";
    public static final String EXTRA_CURRENT_NAME = "current_name";
    public static final String EXTRA_LAST_PHONE = "last_phone";
    public static final String EXTRA_LAST_STATUS = "last_status";
    public static final String EXTRA_AUTODIAL_ATTEMPTS = "autodial_attempts";
    public static final String EXTRA_AUTODIAL_MAX = "autodial_max";
    public static final String EXTRA_AUTODIAL_DELAY = "autodial_delay";
    public static final String EXTRA_AUDIO_STATUS = "audio_status";
    public static final String ACTION_STOP = "LOCALORDERAI_CAMPAIGN_STOP";

    private final BottomSheetDialog dialog;
    private final CampaignLogAdapter adapter = new CampaignLogAdapter();
    private final TextView tvCounter, tvCurrent, tvAutoDialSummary, tvAutoDialDetails, tvAudioStatus;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context appContext;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_PROGRESS.equals(intent.getAction())) return;
            int total = intent.getIntExtra(EXTRA_TOTAL, 0);
            int processed = intent.getIntExtra(EXTRA_PROCESSED, 0);
            String current = intent.getStringExtra(EXTRA_CURRENT_NAME);
            String lastPhone = intent.getStringExtra(EXTRA_LAST_PHONE);
            String lastStatus = intent.getStringExtra(EXTRA_LAST_STATUS);
            int autoDialAttempts = intent.getIntExtra(EXTRA_AUTODIAL_ATTEMPTS, 0);
            int autoDialMax = intent.getIntExtra(EXTRA_AUTODIAL_MAX, 0);
            int autoDialDelay = intent.getIntExtra(EXTRA_AUTODIAL_DELAY, 0);
            String audioStatus = intent.getStringExtra(EXTRA_AUDIO_STATUS);

            // post to main thread to avoid UI deadlock
            mainHandler.post(() -> {
                if (tvCounter != null) tvCounter.setText(processed + " / " + total);
                if (tvCurrent != null) tvCurrent.setText(current != null ? current : "-");
                if (tvAutoDialSummary != null) {
                    if (autoDialMax > 0) {
                        tvAutoDialSummary.setText("Auto Dial: مفعل");
                        tvAutoDialDetails.setText("محاولات: " + autoDialAttempts + " / " + autoDialMax + " - الفاصل: " + autoDialDelay + " sec");
                    } else {
                        tvAutoDialSummary.setText("Auto Dial: غير مفعل");
                        tvAutoDialDetails.setText("محاولات: 0 / 0 - الفاصل: 0 sec");
                    }
                }
                if (tvAudioStatus != null) {
                    tvAudioStatus.setText(audioStatus != null ? audioStatus : "ميك: غير معروف - مكبر: غير معروف");
                }
                if (lastPhone != null && adapter != null) {
                    adapter.addEntry(new LogEntry(current, lastPhone, lastStatus));
                }
            });
        }
    };

    public CampaignProgressDialog(@NonNull Context ctx) {
        this.appContext = ctx.getApplicationContext();
        dialog = new BottomSheetDialog(ctx);
        View v = View.inflate(ctx, R.layout.dialog_campaign_progress, null);
        dialog.setContentView(v);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        tvCounter = v.findViewById(R.id.tvCampaignCounter);
        tvCurrent = v.findViewById(R.id.tvCurrentClient);
        tvAutoDialSummary = v.findViewById(R.id.tvAutoDialSummary);
        tvAutoDialDetails = v.findViewById(R.id.tvAutoDialDetails);
        tvAudioStatus = v.findViewById(R.id.tvAudioStatus);
        RecyclerView rv = v.findViewById(R.id.rvCampaignLogs);
        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(ctx));
            rv.setAdapter(adapter);
        }

        MaterialButton btnStop = v.findViewById(R.id.btnStopCampaignFromDialog);
        if (btnStop != null) {
            btnStop.setOnClickListener(view -> {
                Intent stop = new Intent(ACTION_STOP);
                LocalBroadcastManager.getInstance(appContext).sendBroadcast(stop);
            });
        }

        try {
            LocalBroadcastManager.getInstance(appContext).registerReceiver(receiver,
                    new IntentFilter(ACTION_PROGRESS));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void show() {
        if (!dialog.isShowing()) dialog.show();
    }

    public void dismiss() {
        try {
            LocalBroadcastManager.getInstance(appContext).unregisterReceiver(receiver);
        } catch (Exception ignored) {}
        try {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
