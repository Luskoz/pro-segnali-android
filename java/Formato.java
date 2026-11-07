package it.prosegnali.motore;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Numeri all'italiana (1.234,56) come nella versione PC. */
public final class Formato {
    private Formato() {}

    public static String num(double x, int dec) {
        if (Double.isNaN(x)) return "";
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.ITALY);
        s.setDecimalSeparator(',');
        s.setGroupingSeparator('.');
        StringBuilder p = new StringBuilder("#,##0");
        if (dec > 0) { p.append('.'); for (int i = 0; i < dec; i++) p.append('0'); }
        DecimalFormat f = new DecimalFormat(p.toString(), s);
        f.setRoundingMode(java.math.RoundingMode.HALF_EVEN);
        return f.format(x);
    }

    public static String num(double x) { return num(x, 2); }

    public static String pct(double x, int dec) {
        if (Double.isNaN(x)) return "";
        return (x >= 0 ? "+" : "-") + num(Math.abs(x), dec) + "%";
    }

    public static String euroSegno(double v) {
        if (Double.isNaN(v)) return "";
        return (v > 0 ? "+" : v < 0 ? "-" : "") + num(Math.abs(v)) + " €";
    }

    /** "479,40 $ (≈ 443,90 €)" per gli USA, "26,57 €" per l'Europa. */
    public static String conEuro(String titolo, double valore, double cambio) {
        if (!Mercati.eUsa(titolo)) return num(valore) + " €";
        String s = num(valore) + " $";
        if (!Double.isNaN(cambio) && cambio > 0) s += " (≈ " + num(valore / cambio) + " €)";
        return s;
    }
}
