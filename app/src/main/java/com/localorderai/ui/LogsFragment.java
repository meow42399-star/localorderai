package com.localorderai.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.localorderai.R;
import com.localorderai.data.AppDatabase;
import com.localorderai.utils.CsvExporter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * LogsFragment
 * -------------
 * تاب "السجلات والتقارير": عرض كل الطلبات وحالتها، وتصدير CSV.
 * (كانت LogsActivity قبل ما ننقلها جوه Bottom Navigation)
 */
public class LogsFragment extends Fragment {

    private OrderRecordAdapter adapter;
    private AppDatabase db;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AppDatabase.getInstance(requireContext());

        RecyclerView recyclerView = view.findViewById(R.id.recyclerLogs);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new OrderRecordAdapter();
        recyclerView.setAdapter(adapter);

        db.orderRecordDao().getAllRecordsLive().observe(getViewLifecycleOwner(), records -> {
            adapter.submitList(records);
        });

        MaterialButton btnExport = view.findViewById(R.id.btnExportCsv);
        btnExport.setOnClickListener(v -> exportCsv());
    }

    private void exportCsv() {
        // بنستخدم Application context جوه الـ background thread عشان
        // نتفادى requireContext()/requireActivity() لو المستخدم قفل
        // الشاشة أو غيّر التاب وقت ما التصدير لسه شغال (بيرمي
        // IllegalStateException ويكسر التطبيق). التحديث النهائي للـ UI
        // (المشاركة / رسالة الخطأ) بيتأكد إن الـ Fragment لسه متصل قبل
        // ما يلمس أي View.
        final android.content.Context appContext = requireContext().getApplicationContext();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String fileName = "orders_report_" +
                        new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                .format(new java.util.Date()) + ".csv";

                File file = CsvExporter.exportToCsv(appContext, db.orderRecordDao().getAllRecordsSync(), fileName);

                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (getActivity() == null) return;
                    CsvExporter.shareCsv(requireContext(), file);
                });
            } catch (Exception e) {
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (getActivity() == null) return;
                    Toast.makeText(requireContext(), "فشل التصدير: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
