package com.localorderai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** بيشوف آخر Release على GitHub، ولو أحدث من النسخة الحالية بيعرض زرار تحميل. */
public final class UpdateChecker {

    private static final String API =
            "https://api.github.com/repos/meow42399-star/localorderai/releases/latest";

    private UpdateChecker() {
    }

    public static void check(final Activity activity) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONObject o = new JSONObject(sb.toString());
                    final String tag = o.getString("tag_name");
                    JSONArray assets = o.getJSONArray("assets");
                    String url = null;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.getString("name").endsWith(".apk")) {
                            url = a.getString("browser_download_url");
                            break;
                        }
                    }
                    if (url == null || !isNewer(tag, BuildConfig.VERSION_NAME)) return;
                    final String apkUrl = url;
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isFinishing()) return;
                            new AlertDialog.Builder(activity)
                                    .setTitle("تحديث متاح")
                                    .setMessage("الإصدار " + tag + " متاح (الحالي " + BuildConfig.VERSION_NAME + ").")
                                    .setPositiveButton("تحميل", new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface d, int w) {
                                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)));
                                        }
                                    })
                                    .setNegativeButton("لاحقاً", null)
                                    .show();
                        }
                    });
                } catch (Exception ignored) {
                    // مفيش نت أو GitHub مش متاح: تجاهل
                }
            }
        }).start();
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
