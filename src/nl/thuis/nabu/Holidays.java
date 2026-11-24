// SPDX-License-Identifier: Apache-2.0
package nl.thuis.nabu;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

/** Which holiday a day is: the names here are also the Holiday setting's choices. */
final class Holidays {
    static final String NONE = "";
    static final String SINTERKLAAS = "Sinterklaas";
    static final String KERST = "Kerst";
    static final String OUDEJAAR = "Oudejaarsavond";
    static final String NIEUWJAAR = "Nieuwjaarsdag";
    static final String VALENTIJN = "Valentijnsdag";
    static final String PASEN = "Pasen";
    static final String KONINGSDAG = "Koningsdag";
    static final String BEVRIJDING = "Bevrijdingsdag";
    static final String LENTE = "Hemelvaart en Pinksteren";
    static final String HALLOWEEN = "Halloween";
    static final String SINTMAARTEN = "Sint-Maarten";
    static final String VERJAARDAG = "Verjaardag";

    static final String[] ALL = {SINTERKLAAS, KERST, OUDEJAAR, NIEUWJAAR, VALENTIJN, PASEN, KONINGSDAG, BEVRIJDING, LENTE, HALLOWEEN, SINTMAARTEN, VERJAARDAG};

    private Holidays() {}

    static boolean known(Object name) {
        for (String each : ALL) if (each.equals(name)) return true;
        return false;
    }

    /** The holiday a public-holiday calendar's event names, such as "Tweede paasdag", or NONE. */
    static String fromCalendar(Object message) {
        if (!(message instanceof String)) return NONE;
        String m = ((String) message).toLowerCase(Locale.ROOT);
        if (m.contains("kerst")) return KERST;
        if (m.contains("nieuwjaar")) return NIEUWJAAR;
        if (m.contains("goede vrijdag") || m.contains("paas") || m.contains("pasen")) return PASEN;
        if (m.contains("koning")) return KONINGSDAG;
        if (m.contains("bevrijding")) return BEVRIJDING;
        if (m.contains("hemelvaart") || m.contains("pinkster")) return LENTE;
        return NONE;
    }

    /**
     * The holiday by the plugin's own dates: the days no public-holiday calendar has, and the official ones as a
     * backup for when the calendar can't be read.
     */
    static String byDate(Calendar day) {
        int y = day.get(Calendar.YEAR), m = day.get(Calendar.MONTH) + 1, d = day.get(Calendar.DAY_OF_MONTH);
        if (m == 10 && d == 31) return HALLOWEEN;
        if (m == 11 && d == 11) return SINTMAARTEN;
        if ((m == 11 && d >= sinterklaasStart(y)) || (m == 12 && d <= 5)) return SINTERKLAAS;
        if (m == 12 && d >= 6 && d <= 26) return KERST;
        if (m == 12 && d == 31) return OUDEJAAR;
        if (m == 1 && d == 1) return NIEUWJAAR;
        if (m == 2 && d == 14) return VALENTIJN;
        if (m == 4 && d == koningsdag(y)) return KONINGSDAG;
        if (m == 5 && d == 5) return BEVRIJDING;
        int today = day.get(Calendar.DAY_OF_YEAR), easter = easter(y).get(Calendar.DAY_OF_YEAR);
        if (today >= easter - 2 && today <= easter + 1) return PASEN;
        if (today == easter + 39 || today == easter + 49 || today == easter + 50) return LENTE;
        return NONE;
    }

    /** Sinterklaas arrives on the first Saturday from 11 November. */
    static int sinterklaasStart(int year) {
        Calendar day = new GregorianCalendar(year, Calendar.NOVEMBER, 11);
        while (day.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY) day.add(Calendar.DAY_OF_MONTH, 1);
        return day.get(Calendar.DAY_OF_MONTH);
    }

    /** 27 April, or the 26th when the 27th is a Sunday. */
    static int koningsdag(int year) {
        return new GregorianCalendar(year, Calendar.APRIL, 27).get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY ? 26 : 27;
    }

    /** Easter Sunday (the anonymous Gregorian computus). */
    static Calendar easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, dayOfMonth = (h + l - 7 * m + 114) % 31 + 1;
        return new GregorianCalendar(y, month - 1, dayOfMonth);
    }

    /** Orange lights for the orange days; otherwise the Light colour setting. */
    static int light(String holiday, int chosen) {
        return KONINGSDAG.equals(holiday) || HALLOWEEN.equals(holiday) ? 0xFFFFA040 : chosen;
    }
}
