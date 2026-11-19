package com.collectivelaunchpad.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupee amounts are stored as whole paise. */
public final class Money {

    private Money() {}

    public static String label(long paise) {
        long rupees = paise / 100;
        long rest = Math.abs(paise % 100);
        String whole = indianGrouping(rupees);
        return "₹" + (rest == 0 ? whole : whole + "." + String.format("%02d", rest));
    }

    /** 1234567 -> "12,34,567" */
    static String indianGrouping(long n) {
        String s = Long.toString(Math.abs(n));
        if (s.length() <= 3) {
            return (n < 0 ? "-" : "") + s;
        }
        String last3 = s.substring(s.length() - 3);
        String rest = s.substring(0, s.length() - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            if (i > 0 && (rest.length() - i) % 2 == 0) {
                sb.append(',');
            }
            sb.append(rest.charAt(i));
        }
        return (n < 0 ? "-" : "") + sb + "," + last3;
    }

    /** "450", "12.50", "₹1,200" -> paise. Throws NumberFormatException for anything else. */
    public static long parseRupees(String raw) {
        BigDecimal rupees = new BigDecimal(raw.strip().replace(",", "").replace("₹", ""));
        if (rupees.signum() < 0 || rupees.compareTo(BigDecimal.valueOf(10_000_000)) > 0) {
            throw new NumberFormatException("out of range");
        }
        return rupees.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** For upi:// links: "450.00". */
    public static String upiAmount(long paise) {
        return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
