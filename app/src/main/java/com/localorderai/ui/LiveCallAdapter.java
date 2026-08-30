package com.localorderai.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.localorderai.R;
import com.localorderai.data.OrderRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * LiveCallAdapter
 * ----------------
 * بيعرض قائمة أرقام الحملة اللي لسه في الانتظار (PENDING) بنفس ترتيب
 * الاتصال. الرقم اللي بيتصل عليه دلوقتي بيتحدد بمقارنة الاسم والرقم
 * مع آخر حالة محفوظة في AppConfig (currentName/currentPhone)،
 * وبيتلون بشكل مختلف عشان يبان واضح إنه "جاري الاتصال" دلوقتي.
 */
public class LiveCallAdapter extends RecyclerView.Adapter<LiveCallAdapter.ViewHolder> {

    private List<OrderRecord> records = new ArrayList<>();
    private String currentName;
    private String currentPhone;
    private int currentAttempt;
    private int currentAttemptMax;

    public void submitList(List<OrderRecord> newRecords) {
        this.records = newRecords != null ? newRecords : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void updateCurrentCall(String name, String phone, int attempt, int attemptMax) {
        this.currentName = name;
        this.currentPhone = phone;
        this.currentAttempt = attempt;
        this.currentAttemptMax = attemptMax;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_live_call, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        OrderRecord record = records.get(position);
        holder.tvName.setText(record.customerName);
        holder.tvPhone.setText(record.phoneNumber);

        boolean isCurrent = currentPhone != null
                && Objects.equals(record.phoneNumber, currentPhone)
                && Objects.equals(record.customerName, currentName);

        if (isCurrent) {
            holder.card.setStrokeColor(0xFF2563EB);
            holder.card.setCardBackgroundColor(0xFFEFF6FF);
            holder.tvStatus.setText("جاري الاتصال");
            holder.tvStatus.setTextColor(0xFFFFFFFF);
            holder.tvStatus.setBackgroundColor(0xFF2563EB);
            holder.tvAttempt.setVisibility(View.VISIBLE);
            holder.tvAttempt.setText(currentAttemptMax > 0
                    ? "محاولة " + currentAttempt + "/" + currentAttemptMax
                    : "");
        } else {
            holder.card.setStrokeColor(0xFFF4F7FB);
            holder.card.setCardBackgroundColor(0xFFFFFFFF);
            holder.tvStatus.setText("في الانتظار");
            holder.tvStatus.setTextColor(0xFF64748B);
            holder.tvStatus.setBackgroundColor(0xFFF1F5F9);
            holder.tvAttempt.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView card;
        TextView tvName, tvPhone, tvStatus, tvAttempt;

        ViewHolder(View itemView) {
            super(itemView);
            card = (MaterialCardView) itemView;
            tvName = itemView.findViewById(R.id.tvLiveCallName);
            tvPhone = itemView.findViewById(R.id.tvLiveCallPhone);
            tvStatus = itemView.findViewById(R.id.tvLiveCallStatus);
            tvAttempt = itemView.findViewById(R.id.tvLiveCallAttempt);
        }
    }
}
