package com.localorderai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * قراءة الورقة على الموبايل (من غير إنترنت ولا API): ثلاث قراءات،
 *  1) ML Kit (لاتيني: بيقرا الأرقام الإنجليزي)،
 *  2) Tesseract عربي+إنجليزي على الصورة الأصلية،
 *  3) Tesseract على نسخة رمادية عالية التباين.
 * الرقم بيتقبل بس لو قراءتين أو أكتر اتفقوا عليه (OcrParsing.agree).
 * ML Kit مش بيقرا العربي ولا الأرقام العربية، وTesseract ضعيف مع خط اليد: الأوراق المكتوبة بخط اليد غالباً هتروح للمراجعة.
 */
final class LocalOcr {

    static final class Result {
        /** الرقم اللي اتفقت عليه القراءات (أو null). */
        String number;
        /** تخمين اسم العميل (ممكن يبقى فاضي). */
        String name = "";
        /** عدد أرقام الموبايل الصالحة اللي كل قراءة لقيتها (للتشخيص بس، من غير الأرقام نفسها). */
        int mlkit;
        int tessRaw;
        int tessPre;
    }

    private static final String[] TESS_FILES = {"ara.traineddata", "eng.traineddata"};

    private LocalOcr() {
    }

    static Result read(Context c, Bitmap bmp) {
        Result r = new Result();
        List<Set<String>> passes = new ArrayList<>();

        Set<String> a = new LinkedHashSet<>();
        try {
            a = OcrParsing.findNumbers(mlKitText(bmp));
        } catch (Exception e) {
            EventLog.add("ocr mlkit فشل: " + e.getClass().getSimpleName());
        }
        passes.add(a);
        r.mlkit = a.size();

        String rawText = "";
        TessBaseAPI tess = new TessBaseAPI();
        try {
            if (tess.init(prepareData(c), "ara+eng")) {
                tess.setImage(bmp);
                rawText = tess.getUTF8Text();
                Set<String> b = OcrParsing.findNumbers(rawText);
                passes.add(b);
                r.tessRaw = b.size();

                tess.setImage(enhance(bmp));
                Set<String> d = OcrParsing.findNumbers(tess.getUTF8Text());
                passes.add(d);
                r.tessPre = d.size();
            } else {
                EventLog.add("ocr tesseract init فشل");
            }
        } catch (Exception e) {
            EventLog.add("ocr tesseract فشل: " + e.getClass().getSimpleName());
        } finally {
            tess.recycle();
        }

        r.number = OcrParsing.agree(passes);
        r.name = OcrParsing.guessName(rawText);
        return r;
    }

    private static String mlKitText(Bitmap bmp) throws Exception {
        TextRecognizer rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            Text t = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS);
            return t.getText();
        } finally {
            rec.close();
        }
    }

    /** بيفرد ملفات اللغة (عربي وإنجليزي) من الـ assets لمجلد التطبيق أول مرة بس. */
    private static String prepareData(Context c) throws IOException {
        File dir = new File(c.getFilesDir(), "tessdata");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("مش قادر أعمل مجلد tessdata");
        for (String f : TESS_FILES) {
            File out = new File(dir, f);
            if (out.exists() && out.length() > 0) continue;
            InputStream in = c.getAssets().open("tessdata/" + f);
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                os.close();
                in.close();
            }
        }
        return c.getFilesDir().getAbsolutePath();
    }

    /** رمادي + تباين أعلى حوالين المنتصف (بيساعد Tesseract مع صور الكاميرا). */
    private static Bitmap enhance(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        float s = 1.7f;
        float t = (-0.5f * s + 0.5f) * 255f;
        cm.postConcat(new ColorMatrix(new float[]{
                s, 0, 0, 0, t,
                0, s, 0, 0, t,
                0, 0, s, 0, t,
                0, 0, 0, 1, 0}));
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(out).drawBitmap(src, 0, 0, p);
        return out;
    }
}
