package com.localorderai.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;

import com.localorderai.data.OrderRecord;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * CsvExporter
 * -----------
 * بيصدّر قائمة OrderRecord لملف CSV، وبيفتح مشاركة الملف
 * عبر FileProvider (مش بيحفظ صوت ولا ترانسكريبت مكالمة —
 * بس بيانات نصية جاهزة اتخزنت أصلاً في القاعدة).
 */
public class CsvExporter {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public static File exportToCsv(Context context, List<OrderRecord> records, String fileName) throws IOException {
        File dir = new File(context.getFilesDir(), "reports");
        if (!dir.exists()) dir.mkdirs();

        File csvFile = new File(dir, fileName);

        try (FileWriter writer = new FileWriter(csvFile)) {
            // Header
            writer.append("ID,اسم العميل,رقم الهاتف,رقم الطلب,الحالة,عدد المحاولات,تاريخ الإنشاء,آخر تحديث\n");

            for (OrderRecord r : records) {
                writer.append(escape(String.valueOf(r.id))).append(",");
                writer.append(escape(r.customerName)).append(",");
                writer.append(escape(r.phoneNumber)).append(",");
                writer.append(escape(r.orderReference)).append(",");
                writer.append(escape(r.status)).append(",");
                writer.append(escape(String.valueOf(r.attempts))).append(",");
                writer.append(escape(DATE_FORMAT.format(r.createdAt))).append(",");
                writer.append(escape(DATE_FORMAT.format(r.lastUpdatedAt))).append("\n");
            }
        }

        return csvFile;
    }

    /**
     * بيفتح شاشة مشاركة/فتح الملف للمستخدم
     */
    public static void shareCsv(Context context, File csvFile) {
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                csvFile
        );

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/csv");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(shareIntent, "مشاركة تقرير الطلبات"));
    }

    private static String escape(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\"")) {
            escaped = "\"" + escaped + "\"";
        }
        return escaped;
    }
}
