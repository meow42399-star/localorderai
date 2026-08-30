package com.localorderai.data;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class OrderRecordDao_Impl implements OrderRecordDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<OrderRecord> __insertionAdapterOfOrderRecord;

  private final EntityDeletionOrUpdateAdapter<OrderRecord> __updateAdapterOfOrderRecord;

  public OrderRecordDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfOrderRecord = new EntityInsertionAdapter<OrderRecord>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `order_records` (`id`,`customer_name`,`phone_number`,`order_reference`,`status`,`attempts`,`created_at`,`last_updated_at`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          final OrderRecord entity) {
        statement.bindLong(1, entity.id);
        if (entity.customerName == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.customerName);
        }
        if (entity.phoneNumber == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.phoneNumber);
        }
        if (entity.orderReference == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.orderReference);
        }
        if (entity.status == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.status);
        }
        statement.bindLong(6, entity.attempts);
        final Long _tmp = DateConverter.toTimestamp(entity.createdAt);
        if (_tmp == null) {
          statement.bindNull(7);
        } else {
          statement.bindLong(7, _tmp);
        }
        final Long _tmp_1 = DateConverter.toTimestamp(entity.lastUpdatedAt);
        if (_tmp_1 == null) {
          statement.bindNull(8);
        } else {
          statement.bindLong(8, _tmp_1);
        }
      }
    };
    this.__updateAdapterOfOrderRecord = new EntityDeletionOrUpdateAdapter<OrderRecord>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `order_records` SET `id` = ?,`customer_name` = ?,`phone_number` = ?,`order_reference` = ?,`status` = ?,`attempts` = ?,`created_at` = ?,`last_updated_at` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          final OrderRecord entity) {
        statement.bindLong(1, entity.id);
        if (entity.customerName == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.customerName);
        }
        if (entity.phoneNumber == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.phoneNumber);
        }
        if (entity.orderReference == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.orderReference);
        }
        if (entity.status == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.status);
        }
        statement.bindLong(6, entity.attempts);
        final Long _tmp = DateConverter.toTimestamp(entity.createdAt);
        if (_tmp == null) {
          statement.bindNull(7);
        } else {
          statement.bindLong(7, _tmp);
        }
        final Long _tmp_1 = DateConverter.toTimestamp(entity.lastUpdatedAt);
        if (_tmp_1 == null) {
          statement.bindNull(8);
        } else {
          statement.bindLong(8, _tmp_1);
        }
        statement.bindLong(9, entity.id);
      }
    };
  }

  @Override
  public long insert(final OrderRecord record) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      final long _result = __insertionAdapterOfOrderRecord.insertAndReturnId(record);
      __db.setTransactionSuccessful();
      return _result;
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void update(final OrderRecord record) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      __updateAdapterOfOrderRecord.handle(record);
      __db.setTransactionSuccessful();
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public List<OrderRecord> getPendingRecords() {
    final String _sql = "SELECT * FROM order_records WHERE status = 'PENDING' ORDER BY id ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfCustomerName = CursorUtil.getColumnIndexOrThrow(_cursor, "customer_name");
      final int _cursorIndexOfPhoneNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "phone_number");
      final int _cursorIndexOfOrderReference = CursorUtil.getColumnIndexOrThrow(_cursor, "order_reference");
      final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
      final int _cursorIndexOfAttempts = CursorUtil.getColumnIndexOrThrow(_cursor, "attempts");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfLastUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "last_updated_at");
      final List<OrderRecord> _result = new ArrayList<OrderRecord>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final OrderRecord _item;
        final String _tmpCustomerName;
        if (_cursor.isNull(_cursorIndexOfCustomerName)) {
          _tmpCustomerName = null;
        } else {
          _tmpCustomerName = _cursor.getString(_cursorIndexOfCustomerName);
        }
        final String _tmpPhoneNumber;
        if (_cursor.isNull(_cursorIndexOfPhoneNumber)) {
          _tmpPhoneNumber = null;
        } else {
          _tmpPhoneNumber = _cursor.getString(_cursorIndexOfPhoneNumber);
        }
        final String _tmpOrderReference;
        if (_cursor.isNull(_cursorIndexOfOrderReference)) {
          _tmpOrderReference = null;
        } else {
          _tmpOrderReference = _cursor.getString(_cursorIndexOfOrderReference);
        }
        _item = new OrderRecord(_tmpCustomerName,_tmpPhoneNumber,_tmpOrderReference);
        _item.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfStatus)) {
          _item.status = null;
        } else {
          _item.status = _cursor.getString(_cursorIndexOfStatus);
        }
        _item.attempts = _cursor.getInt(_cursorIndexOfAttempts);
        final Long _tmp;
        if (_cursor.isNull(_cursorIndexOfCreatedAt)) {
          _tmp = null;
        } else {
          _tmp = _cursor.getLong(_cursorIndexOfCreatedAt);
        }
        _item.createdAt = DateConverter.toDate(_tmp);
        final Long _tmp_1;
        if (_cursor.isNull(_cursorIndexOfLastUpdatedAt)) {
          _tmp_1 = null;
        } else {
          _tmp_1 = _cursor.getLong(_cursorIndexOfLastUpdatedAt);
        }
        _item.lastUpdatedAt = DateConverter.toDate(_tmp_1);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public LiveData<List<OrderRecord>> getPendingRecordsLive() {
    final String _sql = "SELECT * FROM order_records WHERE status = 'PENDING' ORDER BY id ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return __db.getInvalidationTracker().createLiveData(new String[] {"order_records"}, false, new Callable<List<OrderRecord>>() {
      @Override
      @Nullable
      public List<OrderRecord> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfCustomerName = CursorUtil.getColumnIndexOrThrow(_cursor, "customer_name");
          final int _cursorIndexOfPhoneNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "phone_number");
          final int _cursorIndexOfOrderReference = CursorUtil.getColumnIndexOrThrow(_cursor, "order_reference");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfAttempts = CursorUtil.getColumnIndexOrThrow(_cursor, "attempts");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
          final int _cursorIndexOfLastUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "last_updated_at");
          final List<OrderRecord> _result = new ArrayList<OrderRecord>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final OrderRecord _item;
            final String _tmpCustomerName;
            if (_cursor.isNull(_cursorIndexOfCustomerName)) {
              _tmpCustomerName = null;
            } else {
              _tmpCustomerName = _cursor.getString(_cursorIndexOfCustomerName);
            }
            final String _tmpPhoneNumber;
            if (_cursor.isNull(_cursorIndexOfPhoneNumber)) {
              _tmpPhoneNumber = null;
            } else {
              _tmpPhoneNumber = _cursor.getString(_cursorIndexOfPhoneNumber);
            }
            final String _tmpOrderReference;
            if (_cursor.isNull(_cursorIndexOfOrderReference)) {
              _tmpOrderReference = null;
            } else {
              _tmpOrderReference = _cursor.getString(_cursorIndexOfOrderReference);
            }
            _item = new OrderRecord(_tmpCustomerName,_tmpPhoneNumber,_tmpOrderReference);
            _item.id = _cursor.getLong(_cursorIndexOfId);
            if (_cursor.isNull(_cursorIndexOfStatus)) {
              _item.status = null;
            } else {
              _item.status = _cursor.getString(_cursorIndexOfStatus);
            }
            _item.attempts = _cursor.getInt(_cursorIndexOfAttempts);
            final Long _tmp;
            if (_cursor.isNull(_cursorIndexOfCreatedAt)) {
              _tmp = null;
            } else {
              _tmp = _cursor.getLong(_cursorIndexOfCreatedAt);
            }
            _item.createdAt = DateConverter.toDate(_tmp);
            final Long _tmp_1;
            if (_cursor.isNull(_cursorIndexOfLastUpdatedAt)) {
              _tmp_1 = null;
            } else {
              _tmp_1 = _cursor.getLong(_cursorIndexOfLastUpdatedAt);
            }
            _item.lastUpdatedAt = DateConverter.toDate(_tmp_1);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public LiveData<List<OrderRecord>> getAllRecordsLive() {
    final String _sql = "SELECT * FROM order_records ORDER BY id DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return __db.getInvalidationTracker().createLiveData(new String[] {"order_records"}, false, new Callable<List<OrderRecord>>() {
      @Override
      @Nullable
      public List<OrderRecord> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfCustomerName = CursorUtil.getColumnIndexOrThrow(_cursor, "customer_name");
          final int _cursorIndexOfPhoneNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "phone_number");
          final int _cursorIndexOfOrderReference = CursorUtil.getColumnIndexOrThrow(_cursor, "order_reference");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfAttempts = CursorUtil.getColumnIndexOrThrow(_cursor, "attempts");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
          final int _cursorIndexOfLastUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "last_updated_at");
          final List<OrderRecord> _result = new ArrayList<OrderRecord>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final OrderRecord _item;
            final String _tmpCustomerName;
            if (_cursor.isNull(_cursorIndexOfCustomerName)) {
              _tmpCustomerName = null;
            } else {
              _tmpCustomerName = _cursor.getString(_cursorIndexOfCustomerName);
            }
            final String _tmpPhoneNumber;
            if (_cursor.isNull(_cursorIndexOfPhoneNumber)) {
              _tmpPhoneNumber = null;
            } else {
              _tmpPhoneNumber = _cursor.getString(_cursorIndexOfPhoneNumber);
            }
            final String _tmpOrderReference;
            if (_cursor.isNull(_cursorIndexOfOrderReference)) {
              _tmpOrderReference = null;
            } else {
              _tmpOrderReference = _cursor.getString(_cursorIndexOfOrderReference);
            }
            _item = new OrderRecord(_tmpCustomerName,_tmpPhoneNumber,_tmpOrderReference);
            _item.id = _cursor.getLong(_cursorIndexOfId);
            if (_cursor.isNull(_cursorIndexOfStatus)) {
              _item.status = null;
            } else {
              _item.status = _cursor.getString(_cursorIndexOfStatus);
            }
            _item.attempts = _cursor.getInt(_cursorIndexOfAttempts);
            final Long _tmp;
            if (_cursor.isNull(_cursorIndexOfCreatedAt)) {
              _tmp = null;
            } else {
              _tmp = _cursor.getLong(_cursorIndexOfCreatedAt);
            }
            _item.createdAt = DateConverter.toDate(_tmp);
            final Long _tmp_1;
            if (_cursor.isNull(_cursorIndexOfLastUpdatedAt)) {
              _tmp_1 = null;
            } else {
              _tmp_1 = _cursor.getLong(_cursorIndexOfLastUpdatedAt);
            }
            _item.lastUpdatedAt = DateConverter.toDate(_tmp_1);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public List<OrderRecord> getAllRecordsSync() {
    final String _sql = "SELECT * FROM order_records";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfCustomerName = CursorUtil.getColumnIndexOrThrow(_cursor, "customer_name");
      final int _cursorIndexOfPhoneNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "phone_number");
      final int _cursorIndexOfOrderReference = CursorUtil.getColumnIndexOrThrow(_cursor, "order_reference");
      final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
      final int _cursorIndexOfAttempts = CursorUtil.getColumnIndexOrThrow(_cursor, "attempts");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfLastUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "last_updated_at");
      final List<OrderRecord> _result = new ArrayList<OrderRecord>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final OrderRecord _item;
        final String _tmpCustomerName;
        if (_cursor.isNull(_cursorIndexOfCustomerName)) {
          _tmpCustomerName = null;
        } else {
          _tmpCustomerName = _cursor.getString(_cursorIndexOfCustomerName);
        }
        final String _tmpPhoneNumber;
        if (_cursor.isNull(_cursorIndexOfPhoneNumber)) {
          _tmpPhoneNumber = null;
        } else {
          _tmpPhoneNumber = _cursor.getString(_cursorIndexOfPhoneNumber);
        }
        final String _tmpOrderReference;
        if (_cursor.isNull(_cursorIndexOfOrderReference)) {
          _tmpOrderReference = null;
        } else {
          _tmpOrderReference = _cursor.getString(_cursorIndexOfOrderReference);
        }
        _item = new OrderRecord(_tmpCustomerName,_tmpPhoneNumber,_tmpOrderReference);
        _item.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfStatus)) {
          _item.status = null;
        } else {
          _item.status = _cursor.getString(_cursorIndexOfStatus);
        }
        _item.attempts = _cursor.getInt(_cursorIndexOfAttempts);
        final Long _tmp;
        if (_cursor.isNull(_cursorIndexOfCreatedAt)) {
          _tmp = null;
        } else {
          _tmp = _cursor.getLong(_cursorIndexOfCreatedAt);
        }
        _item.createdAt = DateConverter.toDate(_tmp);
        final Long _tmp_1;
        if (_cursor.isNull(_cursorIndexOfLastUpdatedAt)) {
          _tmp_1 = null;
        } else {
          _tmp_1 = _cursor.getLong(_cursorIndexOfLastUpdatedAt);
        }
        _item.lastUpdatedAt = DateConverter.toDate(_tmp_1);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public OrderRecord getById(final long id) {
    final String _sql = "SELECT * FROM order_records WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfCustomerName = CursorUtil.getColumnIndexOrThrow(_cursor, "customer_name");
      final int _cursorIndexOfPhoneNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "phone_number");
      final int _cursorIndexOfOrderReference = CursorUtil.getColumnIndexOrThrow(_cursor, "order_reference");
      final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
      final int _cursorIndexOfAttempts = CursorUtil.getColumnIndexOrThrow(_cursor, "attempts");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfLastUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "last_updated_at");
      final OrderRecord _result;
      if (_cursor.moveToFirst()) {
        final String _tmpCustomerName;
        if (_cursor.isNull(_cursorIndexOfCustomerName)) {
          _tmpCustomerName = null;
        } else {
          _tmpCustomerName = _cursor.getString(_cursorIndexOfCustomerName);
        }
        final String _tmpPhoneNumber;
        if (_cursor.isNull(_cursorIndexOfPhoneNumber)) {
          _tmpPhoneNumber = null;
        } else {
          _tmpPhoneNumber = _cursor.getString(_cursorIndexOfPhoneNumber);
        }
        final String _tmpOrderReference;
        if (_cursor.isNull(_cursorIndexOfOrderReference)) {
          _tmpOrderReference = null;
        } else {
          _tmpOrderReference = _cursor.getString(_cursorIndexOfOrderReference);
        }
        _result = new OrderRecord(_tmpCustomerName,_tmpPhoneNumber,_tmpOrderReference);
        _result.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfStatus)) {
          _result.status = null;
        } else {
          _result.status = _cursor.getString(_cursorIndexOfStatus);
        }
        _result.attempts = _cursor.getInt(_cursorIndexOfAttempts);
        final Long _tmp;
        if (_cursor.isNull(_cursorIndexOfCreatedAt)) {
          _tmp = null;
        } else {
          _tmp = _cursor.getLong(_cursorIndexOfCreatedAt);
        }
        _result.createdAt = DateConverter.toDate(_tmp);
        final Long _tmp_1;
        if (_cursor.isNull(_cursorIndexOfLastUpdatedAt)) {
          _tmp_1 = null;
        } else {
          _tmp_1 = _cursor.getLong(_cursorIndexOfLastUpdatedAt);
        }
        _result.lastUpdatedAt = DateConverter.toDate(_tmp_1);
      } else {
        _result = null;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public int countProcessed() {
    final String _sql = "SELECT COUNT(*) FROM order_records WHERE status != 'PENDING'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _result;
      if (_cursor.moveToFirst()) {
        _result = _cursor.getInt(0);
      } else {
        _result = 0;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
