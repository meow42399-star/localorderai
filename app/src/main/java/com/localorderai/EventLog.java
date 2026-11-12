package com.localorderai;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** سجل صغير لآخر الأحداث (في الذاكرة) بيتضاف للتقرير. الأرقام بتتخبّى. */
final class EventLog {

    private static final int MAX = 40;
    private static final ArrayDeque<String> Q = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private EventLog() {
    }

    static synchronized void add(String s) {
        Q.addLast(FMT.format(new Date()) + " " + mask(s));
        while (Q.size() > MAX) Q.removeFirst();
    }

    static synchronized String dump() {
        StringBuilder b = new StringBuilder();
        for (String s : Q) b.append(s).append('\n');
        return b.toString();
    }

    /** بيخبّي أي رقم طويل ويسيب آخر 3 أرقام بس. */
    static String mask(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '+') {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '+')) j++;
                String run = s.substring(i, j);
                if (run.length() >= 6) {
                    out.append("***").append(run.substring(run.length() - 3));
                } else {
                    out.append(run);
                }
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
