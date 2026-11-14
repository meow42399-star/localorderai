package com.localorderai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** بيبعت صورة الورقة لـ Claude (Messages API) ويرجّع الاسم والرقم والتفاصيل. المفتاح بيتبعت لـ api.anthropic.com بس. */
final class VisionClient {

    static final class Result {
        String name = "";
        String phone;
        String details = "";
        double confidence;
    }

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    /** بنجرّب الأول، ولو الموديل مش متاح على الحساب بنجرّب اللي بعده. */
    private static final String[] MODELS = {"claude-sonnet-5-5", "claude-haiku-5-5"};

    private static final String PROMPT =
            "This is a photo of a paper or invoice for ONE customer, taken with a phone camera. "
                    + "It may be printed or handwritten, in Arabic, English, or both. "
                    + "Extract: name (the customer's name), phone (the customer's mobile number; convert Arabic-Indic digits "
                    + "to Western digits; if several numbers appear choose the customer's mobile; NEVER guess a digit that is "
                    + "unclear, use null instead), details (other important details such as address, order and amount, "
                    + "in one or two short lines, in the language written), and confidence (a number from 0 to 1 "
                    + "describing how sure you are of the PHONE NUMBER specifically). "
                    + "Reply with JSON only, no explanation: "
                    + "{\"name\":\"...\",\"phone\":\"...\" or null,\"details\":\"...\",\"confidence\":0.0}";

    private VisionClient() {
    }

    static Result extract(String apiKey, String base64Jpeg) throws Exception {
        IOException last = null;
        for (String model : MODELS) {
            try {
                return call(apiKey, model, base64Jpeg);
            } catch (ModelUnavailable e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("مفيش موديل متاح");
    }

    /** الموديل مش موجود/مش متاح على الحساب، فنجرّب اللي بعده. */
    private static final class ModelUnavailable extends IOException {
        ModelUnavailable(String m) {
            super(m);
        }
    }

    private static Result call(String apiKey, String model, String base64Jpeg) throws Exception {
        JSONObject image = new JSONObject()
                .put("type", "image")
                .put("source", new JSONObject()
                        .put("type", "base64")
                        .put("media_type", "image/jpeg")
                        .put("data", base64Jpeg));
        JSONObject text = new JSONObject().put("type", "text").put("text", PROMPT);
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", 600)
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("content", new JSONArray().put(image).put(text))));

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-api-key", apiKey);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();

        int code = c.getResponseCode();
        String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        if (code != 200) {
            String msg = "HTTP " + code;
            try {
                msg += ": " + new JSONObject(resp).getJSONObject("error").optString("message", "");
            } catch (Exception ignored) {
            }
            if (code == 404 || (code == 400 && msg.toLowerCase().contains("model"))) {
                throw new ModelUnavailable(msg);
            }
            throw new IOException(msg);
        }
        String out = new JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text");
        return parse(out);
    }

    /** بيطلّع أول JSON من رد الموديل (حتى لو لفّه بكلام). */
    static Result parse(String out) throws Exception {
        int a = out.indexOf('{');
        int b = out.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IOException("الرد مش JSON");
        JSONObject j = new JSONObject(out.substring(a, b + 1));
        Result r = new Result();
        r.name = j.isNull("name") ? "" : j.optString("name", "");
        r.phone = j.isNull("phone") ? null : j.optString("phone", null);
        r.details = j.isNull("details") ? "" : j.optString("details", "");
        r.confidence = j.optDouble("confidence", 0);
        return r;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }
}
