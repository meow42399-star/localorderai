package com.localorderai.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.localorderai.R;
import com.localorderai.data.OrderRecord;

import java.util.ArrayList;
import java.util.List;

public class OrderRecordAdapter extends RecyclerView.Adapter<OrderRecordAdapter.ViewHolder> {

    private List<OrderRecord> records = new ArrayList<>();

    public void submitList(List<OrderRecord> newRecords) {
        this.records = newRecords != null ? newRecords : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_order_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        OrderRecord record = records.get(position);
        holder.txtName.setText(record.customerName);
        holder.txtPhone.setText(record.phoneNumber);
        holder.txtReply.setText("عدد المحاولات: " + record.attempts);
        holder.txtStatus.setText(statusLabel(record.status));
        holder.txtStatus.setTextColor(statusColor(record.status));
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    private String statusLabel(String status) {
        if (status == null) return "⏳ معلّق";
        switch (status) {
            case OrderRecord.STATUS_CALLED: return "✅ تم الاتصال";
            case OrderRecord.STATUS_FAILED: return "⚠️ فشل الاتصال";
            default: return "⏳ معلّق";
        }
    }

    private int statusColor(String status) {
        if (status == null) return Color.GRAY;
        switch (status) {
            case OrderRecord.STATUS_CALLED: return Color.parseColor("#2E7D32");
            case OrderRecord.STATUS_FAILED: return Color.parseColor("#EF6C00");
            default: return Color.GRAY;
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView txtName, txtPhone, txtReply, txtStatus;

        ViewHolder(View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtName);
            txtPhone = itemView.findViewById(R.id.txtPhone);
            txtReply = itemView.findViewById(R.id.txtReply);
            txtStatus = itemView.findViewById(R.id.txtStatus);
        }
    }
}
