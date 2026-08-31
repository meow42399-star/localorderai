package com.localorderai.utils;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UpdateManager
 * -------------
 * تحديث يدوي بسيط للتطبيق: يتحقق من آخر GitHub Release للريبو،
 * يقارنه بالنسخة الحالية المثبتة، ولو فيه أحدث ينزّل الـ APK
 * ويفتح شاشة تثبيت أندرويد تلقائيًا (المستخدم لازم يضغط "تثبيت"
 * يدويًا — ده مطلب نظام إجباري ومفيش طريقة نتخطاه).
 *
 * التحديث بيتم فقط لما المستخدم يدوس الزرار (مفيش فحص تلقائي في
 * الخلفية ولا عند فتح التطبيق).
 */
public class UpdateManager {

    private static final String TAG = "UpdateManager";

    // غيّر القيمتين دول لاسم المستخدم/الريبو بتاعك على GitHub
    private static final String GITHUB_OWNER = "meow42399-star";
    private static final String GITHUB_REPO = "localorderai";

    private static final String RELEASES_API_URL =
            "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    public interface UpdateCallback {
        /** فيه نسخة أحدث متاحة */
        void onUpdateAvailable(String versionName, String downloadUrl);
        /** التطبيق فعلاً على آخر نسخة */
        void onUpToDate();
        /** فشل الفحص (نت، أو GitHub API، أو JSON غير متوقع) */
        void onError(String message);
    }

    public interface DownloadCallback {
        void onDownloadStarted();
        void onDownloadFailed(String message);
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver downloadCompleteReceiver;

    public UpdateManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * بيتحقق من GitHub Releases API. بيقارن اسم الـ tag (زي "v1.2.0")
     * برقم النسخة الحالية المثبتة عن طريق مقارنة نصية بسيطة للأجزاء
     * الرقمية (Major.Minor.Patch)، مش versionCode، عشان الـ tag على
     * GitHub هو المتاح فقط من غير ما نضطر نحافظ على ملف manifest إضافي.
     */
    public void checkForUpdate(UpdateCallback callback) {
        executor.execute(() -> {
            try {
                String response = httpGet(RELEASES_API_URL);
                JSONObject json = new JSONObject(response);

                String tagName = json.optString("tag_name", "");
                String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;

                String downloadUrl = extractApkUrl(json);
                if (latestVersion.isEmpty() || downloadUrl == null) {
                    postError(callback, "استجابة GitHub غير متوقعة (لا يوجد إصدار أو ملف APK مرفق)");
                    return;
                }

                String currentVersion = getCurrentVersionName();

                if (isNewerVersion(latestVersion, currentVersion)) {
                    mainHandler.post(() -> callback.onUpdateAvailable(latestVersion, downloadUrl));
                } else {
                    mainHandler.post(callback::onUpToDate);
                }
            } catch (Exception e) {
                Log.e(TAG, "checkForUpdate failed", e);
                postError(callback, "تعذر الاتصال بـ GitHub: " + e.getMessage());
            }
        });
    }

    private void postError(UpdateCallback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    @androidx.annotation.Nullable
    private String extractApkUrl(JSONObject releaseJson) throws JSONException {
        org.json.JSONArray assets = releaseJson.optJSONArray("assets");
        if (assets == null) return null;

        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "");
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    private String getCurrentVersionName() {
        try {
            PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "0";
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** مقارنة نصية بسيطة لأرقام النسخ بصيغة Major.Minor.Patch */
    private boolean isNewerVersion(String remote, String local) {
        String[] r = remote.split("\\.");
        String[] l = local.split("\\.");
        int len = Math.max(r.length, l.length);

        for (int i = 0; i < len; i++) {
            int rv = i < r.length ? safeParseInt(r[i]) : 0;
            int lv = i < l.length ? safeParseInt(l[i]) : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private int safeParseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private String httpGet(String urlString) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            int code = connection.getResponseCode();
            InputStream stream = (code >= 200 && code < 300)
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            if (stream == null) {
                throw new IOException("HTTP " + code + " من غير محتوى استجابة");
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + sb);
            }

            return sb.toString();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * بينزّل الـ APK باستخدام DownloadManager (تحميل نظام، فيه progress
     * notification جاهزة من أندرويد). لما التحميل يخلص، بيفتح شاشة
     * التثبيت تلقائيًا عن طريق FileProvider — المستخدم لازم يضغط
     * "تثبيت" بنفسه، ده مطلب نظام مفيش طريقة نتخطاه.
     */
    public void downloadAndInstall(String downloadUrl, String versionName, DownloadCallback callback) {
        try {
            String fileName = "update-" + versionName + ".apk";
            File apkFile = new File(appContext.getExternalFilesDir(null), fileName);
            if (apkFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                apkFile.delete();
            }

            DownloadManager downloadManager =
                    (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager == null) {
                callback.onDownloadFailed("خدمة التحميل غير متاحة على الجهاز");
                return;
            }

            Uri sourceUri = Uri.parse(downloadUrl);
            DownloadManager.Request request = new DownloadManager.Request(sourceUri)
                    .setTitle("تحديث LocalOrderAI")
                    .setDescription("جاري تحميل النسخة " + versionName)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    // setDestinationInExternalFilesDir بدل setDestinationUri(Uri.fromFile(...)):
                    // دي الطريقة الرسمية اللي DownloadManager (خدمة نظام منفصلة عن
                    // عملية التطبيق) عنده صلاحية كتابة مضمونة فيها على أندرويد 10+
                    // (scoped storage). استخدام Uri.fromFile() مباشرة على مسار
                    // getExternalFilesDir كان بيفشل بصمت من غير أي استثناء أو
                    // إشعار على بعض الأجهزة (خصوصًا Samsung/One UI).
                    .setDestinationInExternalFilesDir(appContext, null, fileName)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true);

            long downloadId = downloadManager.enqueue(request);
            Log.d(TAG, "Download enqueued, id=" + downloadId + " dest=" + apkFile);
            registerDownloadReceiver(downloadId, apkFile, callback);
            callback.onDownloadStarted();

        } catch (Exception e) {
            Log.e(TAG, "downloadAndInstall failed", e);
            callback.onDownloadFailed(e.getMessage());
        }
    }

    private void registerDownloadReceiver(long expectedDownloadId, File apkFile, DownloadCallback callback) {
        if (downloadCompleteReceiver != null) {
            try {
                appContext.unregisterReceiver(downloadCompleteReceiver);
            } catch (Exception ignored) {}
        }

        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long finishedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (finishedId != expectedDownloadId) return;

                try {
                    context.unregisterReceiver(this);
                } catch (Exception ignored) {}
                downloadCompleteReceiver = null;

                // بنسأل DownloadManager نفسه عن حالة التحميل الفعلية بدل ما
                // نفترض النجاح لمجرد وصول الـ broadcast — التحميل ممكن يخلص
                // بحالة FAILED (مساحة ناقصة، خطأ شبكة، إلخ) والـ broadcast
                // برضو بيتبعت في الحالتين.
                DownloadManager downloadManager =
                        (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (downloadManager != null) {
                    android.database.Cursor cursor = downloadManager.query(
                            new DownloadManager.Query().setFilterById(expectedDownloadId));
                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                        int status = statusIdx >= 0 ? cursor.getInt(statusIdx) : -1;
                        int reason = reasonIdx >= 0 ? cursor.getInt(reasonIdx) : -1;
                        cursor.close();

                        if (status != DownloadManager.STATUS_SUCCESSFUL) {
                            Log.e(TAG, "Download failed, status=" + status + " reason=" + reason);
                            callback.onDownloadFailed("فشل التحميل (status=" + status + ", reason=" + reason + ")");
                            return;
                        }
                    }
                }

                if (!apkFile.exists()) {
                    Log.e(TAG, "Download reported complete but file not found: " + apkFile);
                    callback.onDownloadFailed("التحميل خلص لكن الملف مش موجود");
                    return;
                }

                promptInstall(context, apkFile);
            }
        };

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? Context.RECEIVER_NOT_EXPORTED
                : 0;
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(downloadCompleteReceiver, filter);
        }
    }

    /**
     * بيفتح شاشة تثبيت أندرويد الرسمية عبر FileProvider. لازم يكون
     * إذن REQUEST_INSTALL_PACKAGES متفعّل للتطبيق ده، وإلا أندرويد
     * هيوجّه المستخدم لشاشة الإعدادات يفعّله بنفسه.
     */
    private void promptInstall(Context context, File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", apkFile);

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            context.startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "promptInstall failed", e);
            // مكناش بنعمل حاجة هنا قبل كده، فلو فتح شاشة التثبيت فشل
            // (مسار FileProvider غلط، أو مفيش تطبيق يقدر يفتح النوع ده)
            // المستخدم مكانش بياخد أي تنبيه خالص والتحميل كان بيبان
            // "واقف" من غير تفسير.
            mainHandler.post(() -> android.widget.Toast.makeText(appContext,
                    "التحميل خلص لكن فشل فتح شاٍشة التثبيت: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show());
        }
    }
}