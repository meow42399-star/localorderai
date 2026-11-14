package com.localorderai;

/** فحص وتوحيد أرقام الموبايل المصرية. أي رقم مش مطابق بيرجّع null (وبيروح لقايمة المراجعة). */
final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /**
     * بيحوّل الأرقام العربية الهندية لإنجليزي، وبيشيل أي رموز، وبيقبل 01XXXXXXXXX و+20 و0020.
     * بيرجّع الرقم بصيغة 01XXXXXXXXX، أو null لو مش موبايل مصري صالح (010 / 011 / 012 / 015).
     */
    static String normalize(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char ch : raw.toCharArray()) {
            if (ch >= '\u0660' && ch <= '\u0669') {
                sb.append((char) ('0' + (ch - '\u0660')));
            } else if (ch >= '\u06F0' && ch <= '\u06F9') {
                sb.append((char) ('0' + (ch - '\u06F0')));
            } else if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            }
        }
        String d = sb.toString();
        if (d.startsWith("0020")) {
            d = d.substring(4);
        } else if (d.startsWith("20") && d.length() == 12) {
            d = d.substring(2);
        }
        if (d.length() == 10 && d.startsWith("1")) d = "0" + d;
        return d.matches("01[0125]\\d{8}") ? d : null;
    }
}
