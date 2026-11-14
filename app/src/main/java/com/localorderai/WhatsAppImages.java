package com.localorderai;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** بيلاقي أحدث صورة نزّلها واتساب (من MediaStore) وبيحمّلها بحجم مناسب للقراءة. */
final class WhatsAppImages {

    private static final int TRIES = 15;
    private static final long POLL_MS = 2000;

    private WhatsAppImages() {
    }

    /** بيستنى لحد ما صورة جديدة من واتساب تظهر بعد وقت الإشعار (التحميل ممكن يتأخر). */
    static Uri waitForNewest(Context c, long postMs, Set<Long> seen) throws InterruptedException {
        long since = postMs / 1000 - 30;
        long until = postMs / 1000 + 120;
        for (int i = 0; i < TRIES; i++) {
            Uri u = query(c, since, until, seen);
            if (u != null) return u;
            Thread.sleep(POLL_MS);
        }
        return null;
    }

    private static Uri query(Context c, long since, long until, Set<Long> seen) {
        Uri coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH};
        String sel = MediaStore.Images.Media.DATE_ADDED + " BETWEEN ? AND ? AND "
                + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {String.valueOf(since), String.valueOf(until), "%WhatsApp%"};
        Cursor cur = c.getContentResolver().query(coll, proj, sel, args,
                MediaStore.Images.Media.DATE_ADDED + " DESC");
        if (cur == null) return null;
        try {
            while (cur.moveToNext()) {
                long id = cur.getLong(0);
                String rel = cur.getString(1);
                if (seen.contains(id)) continue;
                if (rel != null && rel.contains("/Sent")) continue; // صور أنا اللي بعتّها
                return ContentUris.withAppendedId(coll, id);
            }
        } finally {
            cur.close();
        }
        return null;
    }

    /** بيحمّل الصورة بحيث أطول ضلع مايزيدش عن maxSide، وبيظبط الاتجاه من EXIF. */
    static Bitmap load(Context c, Uri uri, int maxSide) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("مش قادر أفتح الصورة");
        try {
            BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("مش قادر أفتح الصورة");
        Bitmap b;
        try {
            b = BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
        if (b == null) throw new IOException("الصورة تالفة");

        int degrees = 0;
        in = c.getContentResolver().openInputStream(uri);
        if (in != null) {
            try {
                int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            } catch (IOException ignored) {
            } finally {
                in.close();
            }
        }
        float scale = Math.min(1f, (float) maxSide / Math.max(b.getWidth(), b.getHeight()));
        if (degrees == 0 && scale >= 1f) return b;
        Matrix m = new Matrix();
        if (scale < 1f) m.postScale(scale, scale);
        if (degrees != 0) m.postRotate(degrees);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }
}
