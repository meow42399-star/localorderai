package com.localorderai.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.localorderai.R;

import java.util.ArrayList;
import java.util.List;

public class CampaignLogAdapter extends RecyclerView.Adapter<CampaignLogAdapter.Holder> {

    public static class LogEntry {
        public String name;
        public String phone;
        public String status;

        public LogEntry(String name, String phone, String status) {
            this.name = name;
            this.phone = phone;
            this.status = status;
        }
    }

    private final List<LogEntry> items = new ArrayList<>();

    public void addEntry(LogEntry e) {
        items.add(0, e);
        notifyItemInserted(0);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_campaign_log, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LogEntry e = items.get(position);
        holder.tvName.setText(e.name != null ? e.name : "-");
        holder.tvPhone.setText(e.phone != null ? e.phone : "-");
        holder.tvStatus.setText(e.status != null ? e.status : "-");
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView tvName, tvPhone, tvStatus;

        Holder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvLogName);
            tvPhone = itemView.findViewById(R.id.tvLogPhone);
            tvStatus = itemView.findViewById(R.id.tvLogStatus);
        }
    }
}
