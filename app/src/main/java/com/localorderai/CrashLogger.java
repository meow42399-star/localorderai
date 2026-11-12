package com.localorderai;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/** بيحفظ آخر crash في ملف، عشان يتضاف للتقرير في المرة الجاية. */
final class CrashLogger {

    private static final String FILE = "last_crash.txt";
    private static final int MAX_CHARS = 3500;

    private CrashLogger() {
    }

    static void install(final Context c) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String s = "thread=" + t.getName() + "\n" + sw;
                    if (s.length() > MAX_CHARS) s = s.substring(0, MAX_CHARS) + "\n...";
                    FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE));
                    out.write(s.getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    /** آخر crash أو null لو مفيش. */
    static String read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            in.close();
            return new String(b, 0, Math.max(n, 0), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    static void clear(Context c) {
        new File(c.getFilesDir(), FILE).delete();
    }
}
