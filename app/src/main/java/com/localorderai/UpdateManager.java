package com.localorderai;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * بيتحقق من آخر Release على GitHub، ولو فيه نسخة أحدث بينزّلها تلقائياً
 * وبيفتح شاشة التثبيت (ضغطة "تثبيت" مطلوبة من النظام ومفيش طريقة نتخطاها).
 */
final class UpdateManager {

    interface Status {
        void set(String text);
    }

    private static final String API =
            "https://api.github.com/repos/meow42399-star/localorderai/releases/latest";

    private final Activity act;
    private final Status status;
    private long downloadId = -1;
    private BroadcastReceiver receiver;
    private File apk;

    UpdateManager(Activity act, Status status) {
        this.act = act;
        this.status = status;
    }

    void checkAndUpdate() {
        status.set("بيتحقق من التحديثات...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONObject o = new JSONObject(sb.toString());
                    final String tag = o.getString("tag_name");
                    final String ver = tag.startsWith("v") ? tag.substring(1) : tag;
                    JSONArray assets = o.getJSONArray("assets");
                    String found = null;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.getString("name").toLowerCase().endsWith(".apk")) {
                            found = a.getString("browser_download_url");
                            break;
                        }
                    }
                    final String url = found;
                    if (url == null) {
                        post("مفيش ملف APK في آخر إصدار");
                    } else if (!isNewer(ver, BuildConfig.VERSION_NAME)) {
                        post("أنت على آخر إصدار (" + BuildConfig.VERSION_NAME + ")");
                    } else {
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                startDownload(ver, url);
                            }
                        });
                    }
                } catch (Exception e) {
                    post("تعذر التحقق: " + e.getMessage());
                }
            }
        }).start();
    }

    private void post(final String text) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.set(text);
            }
        });
    }

    private void startDownload(String ver, String url) {
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            status.set("فيه نسخة جديدة " + ver + ". اسمح للتطبيق بالتثبيت من الشاشة اللي هتفتح، وارجع اضغط تحقق تاني.");
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName())));
            return;
        }
        apk = new File(act.getExternalFilesDir(null), "update-" + ver + ".apk");
        if (apk.exists()) apk.delete();

        final DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                .setTitle("تحديث LocalOrderAI")
                .setDescription("جاري تحميل النسخة " + ver)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(act, null, apk.getName());
        downloadId = dm.enqueue(req);

        cancel();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                boolean ok = false;
                Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id));
                if (cur != null) {
                    if (cur.moveToFirst()) {
                        int st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        ok = st == DownloadManager.STATUS_SUCCESSFUL;
                    }
                    cur.close();
                }
                cancel();
                if (ok) {
                    status.set("تم التحميل. اضغط تثبيت.");
                    install();
                } else {
                    status.set("فشل التحميل. جرّب تاني.");
                }
            }
        };
        act.registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED);
        status.set("فيه نسخة جديدة " + ver + " — بينزّلها دلوقتي...");
    }

    private void install() {
        try {
            Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            status.set("التحميل خلص. افتحه من إشعار التحميل عشان تثبّته.");
        }
    }

    /** بيفك الـ receiver (يتنادى من onDestroy). */
    void cancel() {
        if (receiver != null) {
            try {
                act.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }
    }

    static boolean isNewer(String remote, String local) {
        int[] r = parse(remote);
        int[] l = parse(local);
        for (int i = 0; i < 3; i++) {
            if (r[i] != l[i]) return r[i] > l[i];
        }
        return false;
    }

    private static int[] parse(String v) {
        int[] out = new int[3];
        String s = v.replaceAll("^[^0-9]*", "");
        String[] p = s.split("[^0-9]+");
        for (int i = 0; i < 3 && i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
