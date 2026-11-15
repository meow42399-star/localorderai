package com.localorderai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** تحليل نص الـ OCR: استخراج أرقام الموبايل، تخمين الاسم، وقاعدة الاتفاق بين القراءات. Java نقية (بتتجرّب بدون Android). */
final class OcrParsing {

    private static final Pattern DIGIT_GROUP = Pattern.compile("(\\+?)\\s?([0-9\u0660-\u0669\u06F0-\u06F9]+)");
    private static final Pattern NAME = Pattern.compile(
            "(?:اسم العميل|الاسم|اسم|name|customer)\\s*[:：\\-–/|]?\\s*([^\\n\\d]{2,40})", Pattern.CASE_INSENSITIVE);

    private OcrParsing() {
    }

    /**
     * كل أرقام الموبايل المصرية الصالحة في النص بترتيب ظهورها.
     * الرقم ممكن يتقرا مقسوم (0100 123 4567) أو بأرقام عربية أو بـ +20، لكن بنجمّع بس أجزاء في نفس السطر،
     * وبنبدأ التجميع بس من جزء شكله بداية رقم موبايل (عشان مانركّبش رقم من أجزاء مالهاش علاقة، زي سعر وتاريخ).
     */
    static Set<String> findNumbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String line : text.split("\\r?\\n")) {
            List<String> groups = new ArrayList<>();
            List<Boolean> plus = new ArrayList<>();
            Matcher m = DIGIT_GROUP.matcher(line);
            while (m.find()) {
                groups.add(toWestern(m.group(2)));
                plus.add(!m.group(1).isEmpty());
            }
            for (int i = 0; i < groups.size(); i++) {
                if (!canStart(groups.get(i), plus.get(i))) continue;
                StringBuilder s = new StringBuilder();
                for (int j = i; j < groups.size() && s.length() <= 14; j++) {
                    s.append(groups.get(j));
                    String n = PhoneNumbers.normalize(s.toString());
                    if (n != null) {
                        out.add(n);
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static boolean canStart(String g, boolean plus) {
        return g.startsWith("01")
                || g.startsWith("0020")
                || (plus && g.startsWith("20"))
                || (g.length() == 10 && g.startsWith("1"))
                || (g.length() == 12 && g.startsWith("20"));
    }

    static String toWestern(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch >= '\u0660' && ch <= '\u0669') sb.append((char) ('0' + (ch - '\u0660')));
            else if (ch >= '\u06F0' && ch <= '\u06F9') sb.append((char) ('0' + (ch - '\u06F0')));
            else sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * الرقم اللي قراءتين أو أكتر اتفقوا عليه. بيرجّع null لو مفيش اتفاق،
     * أو لو فيه رقمين مختلفين كل واحد اتقرا مرتين أو أكتر (تعارض).
     */
    static String agree(List<Set<String>> passes) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Set<String> s : passes) {
            for (String n : s) {
                Integer old = count.get(n);
                count.put(n, old == null ? 1 : old + 1);
            }
        }
        String best = null;
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() >= 2) {
                if (best != null) return null;
                best = e.getKey();
            }
        }
        return best;
    }

    /** تخمين اسم العميل من سطر بيبدأ بـ "الاسم:" أو "Name:". لو مفيش بيرجّع "". */
    static String guessName(String text) {
        if (text == null) return "";
        Matcher m = NAME.matcher(text);
        if (!m.find()) return "";
        return m.group(1).trim().replaceAll("[\\s:：\\-–_.]+$", "");
    }
}
