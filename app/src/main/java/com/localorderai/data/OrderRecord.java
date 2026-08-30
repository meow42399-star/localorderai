package com.localorderai.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.util.Date;

/**
 * OrderRecord
 * -----------
 * سجل واحد لطلب عميل — بيتخزن محليًا (Room Database).
 * التطبيق دلوقتي بيعمل اتصال تلقائي بالأرقام بس، من غير أي تكامل خارجي
 * (لا واتساب ولا Gemini). status بيعبّر عن حالة المكالمة نفسها فقط.
 */
@Entity(tableName = "order_records")
public class OrderRecord {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CALLED = "CALLED";
    public static final String STATUS_FAILED = "FAILED";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "customer_name")
    public String customerName;

    @ColumnInfo(name = "phone_number")
    public String phoneNumber;

    @ColumnInfo(name = "order_reference")
    public String orderReference;

    // حالة المكالمة: PENDING / CALLED / FAILED
    @ColumnInfo(name = "status")
    public String status;

    @ColumnInfo(name = "attempts")
    public int attempts;

    @ColumnInfo(name = "created_at")
    public Date createdAt;

    @ColumnInfo(name = "last_updated_at")
    public Date lastUpdatedAt;

    public OrderRecord(String customerName, String phoneNumber, String orderReference) {
        this.customerName = customerName;
        this.phoneNumber = phoneNumber;
        this.orderReference = orderReference;
        this.status = STATUS_PENDING;
        this.attempts = 0;
        this.createdAt = new Date();
        this.lastUpdatedAt = new Date();
    }
}
