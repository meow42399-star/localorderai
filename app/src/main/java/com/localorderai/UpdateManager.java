package com.localorderai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
    private volatile boolean cancelled;
    private volatile boolean downloading;
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
        if (downloading) return; // تحميل شغال بالفعل
        File dir = act.getExternalFilesDir(null);
        if (dir == null) dir = act.getFilesDir();
        apk = new File(dir, "update-" + ver + ".apk");
        final File part = new File(dir, "update-" + ver + ".apk.part");
        final File target = apk;
        if (target.exists()) target.delete();
        if (part.exists()) part.delete();
        cancelled = false;
        downloading = true;
        status.set("فيه نسخة جديدة " + ver + " — بيبدأ التحميل...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = open(url);
                    int code = c.getResponseCode();
                    if (code != 200) {
                        post("فشل التحميل (HTTP " + code + "). جرّب تاني.");
                        return;
                    }
                    long total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    OutputStream out = new FileOutputStream(part);
                    byte[] buf = new byte[16 * 1024];
                    long done = 0;
                    long lastUi = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelled) {
                            out.close();
                            in.close();
                            part.delete();
                            return;
                        }
                        out.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 300) {
                            lastUi = now;
                            post("بينزّل النسخة " + ver + " — " + progress(done, total));
                        }
                    }
                    out.flush();
                    out.close();
                    in.close();
                    if (total > 0 && done != total) {
                        part.delete();
                        post("التحميل ما كملش (" + progress(done, total) + "). جرّب تاني.");
                        return;
                    }
                    if (!looksLikeApk(part) || !part.renameTo(target)) {
                        part.delete();
                        post("الملف المحمّل مش APK سليم. جرّب تاني.");
                        return;
                    }
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            status.set("تم التحميل. اضغط تثبيت.");
                            install();
                        }
                    });
                } catch (Exception e) {
                    part.delete();
                    post("فشل التحميل: " + e.getClass().getSimpleName()
                            + (e.getMessage() != null ? " - " + e.getMessage() : ""));
                } finally {
                    downloading = false;
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    /** بيفتح الاتصال ويتبع الـ redirects يدوي (GitHub بيحوّل لـ objects.githubusercontent.com). */
    private static HttpURLConnection open(String url) throws Exception {
        String cur = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "LocalOrderAI-Updater");
            c.setRequestProperty("Accept", "application/octet-stream");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new java.io.IOException("redirect without Location");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            return c;
        }
        throw new java.io.IOException("too many redirects");
    }

    private static String progress(long done, long total) {
        if (total > 0) {
            return (int) (done * 100 / total) + "% (" + mb(done) + " / " + mb(total) + " MB)";
        }
        return mb(done) + " MB";
    }

    private static String mb(long b) {
        return String.format(java.util.Locale.US, "%.1f", b / 1048576.0);
    }

    /** ملف APK هو ZIP: لازم يبدأ بـ "PK". */
    private static boolean looksLikeApk(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int a = in.read();
            int b = in.read();
            in.close();
            return a == 'P' && b == 'K';
        } catch (Exception e) {
            return false;
        }
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

    /** بيوقّف أي تحميل شغال (يتنادى من onDestroy). */
    void cancel() {
        cancelled = true;
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
