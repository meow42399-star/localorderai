package com.localorderai.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.localorderai.R;
import com.localorderai.data.AppConfig;
import com.localorderai.data.AppDatabase;
import com.localorderai.services.CampaignForegroundService;
import com.localorderai.services.OrderInCallService;
import com.localorderai.services.OverlayBubbleService;

/**
 * LiveCallsFragment
 * -------------------
 * تاب "الأرقام الحالية": بيعرض قائمة أرقام الحملة اللي لسه معلّقة
 * بنفس ترتيب الاتصال، وبيميّز الرقم اللي جاري الاتصال بيه دلوقتي.
 * فيه كمان تحكمات سريعة (إيقاف الحملة / تخطي الرقم الحالي / كتم
 * المايك / تشغيل-إيقاف السماعة) — أي تغيير هنا بيتحفظ في نفس
 * AppConfig اللي الأوفر بيقرا منه، فبيتزامن الاتنين لحظيًا.
 */
public class LiveCallsFragment extends Fragment {

    private AppConfig config;
    private AppDatabase db;
    private LiveCallAdapter adapter;

    private TextView tvSummary, tvStatusLabel, tvStatusBadge, tvEmpty;
    private MaterialButton btnToggleMic, btnToggleSpeaker;

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!CampaignProgressDialog.ACTION_PROGRESS.equals(intent.getAction())) return;
            refreshCurrentCallHighlight();
            refreshCampaignStatus();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_live_calls, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        config = new AppConfig(requireContext());
        db = AppDatabase.getInstance(requireContext());

        tvSummary = view.findViewById(R.id.tvLiveCallsSummary);
        tvStatusLabel = view.findViewById(R.id.tvCampaignStatusLabel);
        tvStatusBadge = view.findViewById(R.id.tvCampaignStatusBadge);
        tvEmpty = view.findViewById(R.id.tvLiveCallsEmpty);
        btnToggleMic = view.findViewById(R.id.btnLiveToggleMic);
        btnToggleSpeaker = view.findViewById(R.id.btnLiveToggleSpeaker);

        RecyclerView recycler = view.findViewById(R.id.recyclerLiveCalls);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new LiveCallAdapter();
        recycler.setAdapter(adapter);

        db.orderRecordDao().getPendingRecordsLive().observe(getViewLifecycleOwner(), records -> {
            adapter.submitList(records);
            tvEmpty.setVisibility(records == null || records.isEmpty() ? View.VISIBLE : View.GONE);
            updateSummary();
        });

        MaterialButton btnStop = view.findViewById(R.id.btnLiveStopCampaign);
        btnStop.setOnClickListener(v -> stopCampaign());

        MaterialButton btnSkip = view.findViewById(R.id.btnLiveSkipCurrent);
        btnSkip.setOnClickListener(v -> skipCurrent());

        btnToggleMic.setOnClickListener(v -> toggleMic());
        btnToggleSpeaker.setOnClickListener(v -> toggleSpeaker());

        refreshMicButtonState();
        refreshSpeakerButtonState();
        refreshCurrentCallHighlight();
        refreshCampaignStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                    progressReceiver, new IntentFilter(CampaignProgressDialog.ACTION_PROGRESS));
        } catch (Exception ignored) {}

        // تحديث فوري من آخر حالة محفوظة، من غير انتظار أي broadcast
        refreshCurrentCallHighlight();
        refreshCampaignStatus();
        refreshMicButtonState();
        refreshSpeakerButtonState();
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(progressReceiver);
        } catch (Exception ignored) {}
    }

    private void updateSummary() {
        int total = config.getStateTotal();
        int processed = config.getStateProcessed();
        if (tvSummary != null) {
            tvSummary.setText(processed + " / " + total + " تم الاتصال بهم");
        }
    }

    private void refreshCurrentCallHighlight() {
        if (adapter == null) return;
        adapter.updateCurrentCall(
                config.getStateCurrentName(),
                config.getStateCurrentPhone(),
                config.getStateAttempt(),
                config.getStateAttemptMax()
        );
        updateSummary();
    }

    private void refreshCampaignStatus() {
        boolean running = config.isCampaignRunning();
        if (tvStatusLabel != null) {
            tvStatusLabel.setText(running ? "حالة الحملة: شغالة" : "حالة الحملة: متوقفة");
        }
        if (tvStatusBadge != null) {
            tvStatusBadge.setText(running ? "● Live" : "● متوقفة");
            tvStatusBadge.setTextColor(running ? 0xFF16A34A : 0xFF64748B);
            tvStatusBadge.setBackgroundColor(running ? 0xFFDCFCE7 : 0xFFF1F5F9);
        }
    }

    private void stopCampaign() {
        Intent serviceIntent = new Intent(requireContext(), CampaignForegroundService.class);
        serviceIntent.setAction("STOP");
        requireContext().startService(serviceIntent);

        Intent bubbleStop = new Intent(requireContext(), OverlayBubbleService.class);
        bubbleStop.setAction("STOP");
        requireContext().startService(bubbleStop);

        Toast.makeText(requireContext(), "تم إيقاف الحملة", Toast.LENGTH_SHORT).show();
        refreshCampaignStatus();
    }

    private void skipCurrent() {
        if (!config.isCampaignRunning()) {
            Toast.makeText(requireContext(), "مفيش حملة شغالة دلوقتي", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent skipIntent = new Intent(requireContext(), CampaignForegroundService.class);
        skipIntent.setAction("SKIP");
        requireContext().startService(skipIntent);
        Toast.makeText(requireContext(), "تم تخطي الرقم الحالي", Toast.LENGTH_SHORT).show();
    }

    private void toggleMic() {
        boolean newMuted = !config.isMicMuted();
        config.setMicMuted(newMuted);
        refreshMicButtonState();
        OrderInCallService.applyAudioSettingsLive();
    }

    private void toggleSpeaker() {
        boolean newVal = !config.isSpeakerEnabled();
        config.setSpeakerEnabled(newVal);
        refreshSpeakerButtonState();
        OrderInCallService.applyAudioSettingsLive();
    }

    private void refreshMicButtonState() {
        if (btnToggleMic == null) return;
        boolean muted = config.isMicMuted();
        btnToggleMic.setText(muted ? "🚫 المايك مكتوم" : "🎤 المايك شغال");
    }

    private void refreshSpeakerButtonState() {
        if (btnToggleSpeaker == null) return;
        boolean speakerOn = config.isSpeakerEnabled();
        btnToggleSpeaker.setText(speakerOn ? "🔊 السماعة شغالة" : "🔈 السماعة متوقفة");
    }
}
