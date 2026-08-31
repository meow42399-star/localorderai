package com.localorderai.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * AppLogger
 * ---------
 * سجل أخطاء محلي بسيط. بيتصرف كطبقة إضافية فوق android.util.Log:
 * أي استدعاء لـ AppLogger.e() أو AppLogger.w() بيطبع في Logcat زي
 * العادي، وكمان بيتحفظ في ملف نصي على الجهاز (crash_log.txt) —
 * عشان لو حصلت مشكلة، تقدر تصدّر الملف ده وتبعته مباشرة بدل ما
 * تحاول توصف اللي حصل بالكلام أو تدور في Android Studio.
 *
 * بيحتفظ بآخر MAX_LINES سطر بس (مش ملف بيكبر للأبد على الجهاز).
 * الكتابة بتتم على executor واحد تسلسلي عشان لو أكتر من thread
 * سجلوا في نفس اللحظة (زي ما بيحصل فعليًا بين CampaignForegroundService
 * وOverlayBubbleService)، السطور متتكتبش فوق بعض أو تتلخبط.
 */
public class AppLogger {

    private static final String TAG_PREFIX = "LocalOrderAI/";
    private static final String LOG_FILE_NAME = "crash_log.txt";
    private static final int MAX_LINES = 500;

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static final java.util.concurrent.ExecutorService writerExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private AppLogger() {}

    public static void e(String tag, String message) {
        e(tag, message, null);
    }

    public static void e(String tag, String message, Throwable t) {
        Log.e(TAG_PREFIX + tag, message, t);
        writeLine("ERROR", tag, message, t, null);
    }

    public static void w(String tag, String message) {
        w(tag, message, null);
    }

    public static void w(String tag, String message, Throwable t) {
        Log.w(TAG_PREFIX + tag, message, t);
        writeLine("WARN", tag, message, t, null);
    }

    /** بيتنادى مرة واحدة بس في onCreate بتاع LocalOrderAiApp عشان يمسك أي crash مش متوقع. */
    public static void init(Context context) {
        final Context appContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // كتابة متزامنة (مش عن طريق الـ executor) هنا عمدًا — التطبيق
            // على وشك يقفل فورًا بعد الـ crash، فمفيش وقت نستنى الـ
            // executor thread يتنفذ. لازم يكون آخر حاجة تتعمل قبل التسليم
            // للـ handler الأصلي.
            writeLineSync(appContext, "FATAL", "UncaughtException", throwable.getMessage(), throwable);
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private static void writeLine(String level, String tag, String message, Throwable t, Void unused) {
        writerExecutor.execute(() -> {
            // appContext بيتحدد وقت init()؛ لو حد نادى e()/w() قبل init()
            // (نادرًا)، بنستخدم آخر context معروف لو موجود.
            Context ctx = sAppContext;
            if (ctx == null) return;
            writeLineSync(ctx, level, tag, message, t);
        });
    }

    private static volatile Context sAppContext;

    public static void attachContext(Context context) {
        sAppContext = context.getApplicationContext();
    }

    private static synchronized void writeLineSync(Context context, String level, String tag, String message, Throwable t) {
        try {
            File logFile = new File(context.getFilesDir(), LOG_FILE_NAME);

            StringBuilder entry = new StringBuilder();
            entry.append(TIME_FORMAT.format(new Date()))
                    .append(" [").append(level).append("] ")
                    .append(tag).append(": ").append(message);
            if (t != null) {
                entry.append(" | ").append(t.getClass().getSimpleName());
                if (t.getMessage() != null) entry.append(": ").append(t.getMessage());
                StackTraceElement[] trace = t.getStackTrace();
                if (trace.length > 0) {
                    entry.append(" @ ").append(trace[0].toString());
                }
            }

            appendAndTrim(logFile, entry.toString());
        } catch (Exception e) {
            // متعمد: أي فشل في الـ logger نفسه لازم يبقى صامت تمامًا —
            // مينفعش نظام تسجيل الأخطاء يسبب هو نفسه crash إضافي.
            Log.e(TAG_PREFIX + "AppLogger", "Failed to write log entry", e);
        }
    }

    private static void appendAndTrim(File logFile, String newLine) throws IOException {
        ArrayDeque<String> lines = new ArrayDeque<>();
        if (logFile.exists()) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(logFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.addLast(line);
                }
            }
        }
        lines.addLast(newLine);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }

        try (FileWriter writer = new FileWriter(logFile, false)) {
            for (String line : lines) {
                writer.write(line);
                writer.write("\n");
            }
        }
    }

    /** بيرجع الملف نفسه عشان LogsFragment يقدر يشاركه عن طريق FileProvider. */
    public static File getLogFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), LOG_FILE_NAME);
    }

    /** بيمسح اللوج بالكامل — مفيد لو المستخدم عايز يبدأ تسجيل نظيف قبل ما يجرب حاجة معينة. */
    public static void clear(Context context) {
        File logFile = getLogFile(context);
        if (logFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logFile.delete();
        }
    }
}
