package com.localorderai.data;

import androidx.lifecycle.LiveData;
import androidx.room.*;

import java.util.List;

@Dao
public interface OrderRecordDao {

    @Insert
    long insert(OrderRecord record);

    @Update
    void update(OrderRecord record);

    @Query("SELECT * FROM order_records WHERE status = 'PENDING' ORDER BY id ASC")
    List<OrderRecord> getPendingRecords();

    @Query("SELECT * FROM order_records WHERE status = 'PENDING' ORDER BY id ASC")
    LiveData<List<OrderRecord>> getPendingRecordsLive();

    @Query("SELECT * FROM order_records ORDER BY id DESC")
    LiveData<List<OrderRecord>> getAllRecordsLive();

    @Query("SELECT * FROM order_records")
    List<OrderRecord> getAllRecordsSync();

    @Query("SELECT * FROM order_records WHERE id = :id")
    OrderRecord getById(long id);

    @Query("SELECT COUNT(*) FROM order_records WHERE status != 'PENDING'")
    int countProcessed();
}
