package com.localorderai;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import java.util.List;

/** بيجهّز تقرير المشكلة، ويفتحه كـ Issue جاهزة على GitHub أو ينسخه للحافظة. */
final class ReportBuilder {

    private static final String ISSUE_URL = "https://github.com/meow42399-star/localorderai/issues/new";
    private static final int MAX_ENCODED = 6500;

    private ReportBuilder() {
    }

    static String build(Context c) {
        StringBuilder b = new StringBuilder();
        b.append("## تقرير من LocalOrderAI\n");
        b.append("- الإصدار: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        b.append("- الجهاز: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("- Default Dialer: ").append(hasRole(c) ? "نعم (الوضع الكامل)" : "لا (الوضع البسيط)").append('\n');
        b.append("- الصلاحيات: ")
                .append(perm(c, Manifest.permission.CALL_PHONE, "CALL_PHONE")).append(' ')
                .append(perm(c, Manifest.permission.READ_PHONE_STATE, "READ_PHONE_STATE")).append(' ')
                .append(perm(c, Manifest.permission.ANSWER_PHONE_CALLS, "ANSWER_PHONE_CALLS")).append('\n');

        b.append("\n### الصوت (المكبر/الميوت)\n```\n").append(EventLog.mask(AudioControl.diagnose(c))).append("\n```\n");

        String ev = EventLog.dump();
        if (!ev.isEmpty()) b.append("\n### آخر الأحداث\n```\n").append(ev).append("```\n");

        List<String> res = CallQueue.get().getResults();
        if (!res.isEmpty()) {
            b.append("\n### نتائج آخر مكالمات\n```\n");
            for (String r : res) b.append(EventLog.mask(r)).append('\n');
            b.append("```\n");
        }

        String crash = CrashLogger.read(c);
        if (crash != null) b.append("\n### آخر crash\n```\n").append(crash).append("\n```\n");

        b.append("\n### وصف المشكلة (اكتب هنا إيه اللي حصل)\n\n");
        return b.toString();
    }

    static void openIssue(Activity a) {
        String title = "تقرير: " + Build.MODEL + " / Android " + Build.VERSION.RELEASE
                + " / v" + BuildConfig.VERSION_NAME;
        String body = build(a);
        boolean truncated = false;
        // الرابط له حد أقصى للطول، فنقص التقرير لو لزم (النسخة الكاملة متاحة بزرار "انسخ التقرير")
        while (Uri.encode(body).length() > MAX_ENCODED && body.length() > 500) {
            body = body.substring(0, body.length() * 9 / 10);
            truncated = true;
        }
        if (truncated) body += "\n...(اتقص عشان طول الرابط، استخدم «انسخ التقرير» للنسخة الكاملة)\n";
        String url = ISSUE_URL + "?title=" + Uri.encode(title) + "&body=" + Uri.encode(body);
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            CrashLogger.clear(a);
        } catch (Exception e) {
            Toast.makeText(a, "مفيش متصفح لفتح الرابط، استخدم «انسخ التقرير»", Toast.LENGTH_LONG).show();
        }
    }

    static void copy(Activity a) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("LocalOrderAI report", build(a)));
        Toast.makeText(a, "اتنسخ التقرير", Toast.LENGTH_SHORT).show();
    }

    private static String perm(Context c, String p, String name) {
        return name + (c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED ? "✔" : "✘");
    }

    private static boolean hasRole(Context c) {
        RoleManager rm = c.getSystemService(RoleManager.class);
        return rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && rm.isRoleHeld(RoleManager.ROLE_DIALER);
    }
}
