package it.prosegnali.motore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Prezzi giornalieri di un titolo con gli indicatori gia' calcolati.
 * Stessi calcoli di motore.indicatori() della versione PC.
 */
public final class Serie {
    public final String titolo;
    public final LocalDate[] date;
    public final double[] o, h, l, c, v;
    public final int n;

    public double[] sma5, sma20, sma50, sma200, avgG, avgL, rsi2, max20, max252, min10, min20, ibs;
    public boolean[] nr7;

    public Serie(String titolo, LocalDate[] date, double[] o, double[] h, double[] l, double[] c,
                 double[] v) {
        this.titolo = titolo;
        this.date = date;
        this.o = o; this.h = h; this.l = l; this.c = c; this.v = v;
        this.n = c.length;
        calcolaIndicatori();
    }

    /** Copia senza l'ultima barra (barra di oggi ancora incompleta). */
    public Serie senzaUltima() {
        int m = n - 1;
        LocalDate[] d2 = new LocalDate[m];
        System.arraycopy(date, 0, d2, 0, m);
        return new Serie(titolo, d2, taglia(o, m), taglia(h, m), taglia(l, m), taglia(c, m), taglia(v, m));
    }

    /** Copia fino alla barra `fine` esclusa (per i test). */
    public Serie fino(int fine) {
        LocalDate[] d2 = new LocalDate[fine];
        System.arraycopy(date, 0, d2, 0, fine);
        return new Serie(titolo, d2, taglia(o, fine), taglia(h, fine), taglia(l, fine), taglia(c, fine),
                taglia(v, fine));
    }

    private static double[] taglia(double[] a, int m) {
        double[] r = new double[m];
        System.arraycopy(a, 0, r, 0, m);
        return r;
    }

    // ------------------------------------------------------------------ indicatori

    static double[] media(double[] x, int finestra) {
        double[] r = nan(x.length);
        double somma = 0;
        for (int i = 0; i < x.length; i++) {
            somma += x[i];
            if (i >= finestra) somma -= x[i - finestra];
            if (i >= finestra - 1) r[i] = somma / finestra;
        }
        return r;
    }

    static double[] massimo(double[] x, int finestra) {
        double[] r = nan(x.length);
        for (int i = finestra - 1; i < x.length; i++) {
            double m = Double.NEGATIVE_INFINITY;
            for (int k = i - finestra + 1; k <= i; k++) m = Math.max(m, x[k]);
            r[i] = m;
        }
        return r;
    }

    static double[] minimo(double[] x, int finestra) {
        double[] r = nan(x.length);
        for (int i = finestra - 1; i < x.length; i++) {
            double m = Double.POSITIVE_INFINITY;
            for (int k = i - finestra + 1; k <= i; k++) m = Math.min(m, x[k]);
            r[i] = m;
        }
        return r;
    }

    static double[] nan(int n) {
        double[] r = new double[n];
        java.util.Arrays.fill(r, Double.NaN);
        return r;
    }

    private void calcolaIndicatori() {
        sma5 = media(c, 5);
        sma20 = media(c, 20);
        sma50 = media(c, 50);
        sma200 = media(c, 200);
        // RSI a 2 periodi: medie esponenziali (alpha 0,5) di rialzi e ribassi
        avgG = nan(n);
        avgL = nan(n);
        rsi2 = nan(n);
        for (int i = 1; i < n; i++) {
            double d = c[i] - c[i - 1];
            double g = Math.max(d, 0), p = Math.max(-d, 0);
            if (i == 1) { avgG[i] = g; avgL[i] = p; }
            else { avgG[i] = 0.5 * g + 0.5 * avgG[i - 1]; avgL[i] = 0.5 * p + 0.5 * avgL[i - 1]; }
            rsi2[i] = avgL[i] == 0 ? Double.NaN : 100 - 100 / (1 + avgG[i] / avgL[i]);
        }
        max20 = massimo(h, 20);
        max252 = massimo(h, 252);
        min10 = minimo(l, 10);
        min20 = minimo(l, 20);
        double[] esc = new double[n];
        for (int i = 0; i < n; i++) esc[i] = h[i] - l[i];
        double[] esc7 = minimo(esc, 7);
        nr7 = new boolean[n];
        ibs = nan(n);
        for (int i = 0; i < n; i++) {
            nr7[i] = !Double.isNaN(esc7[i]) && esc[i] <= esc7[i];
            ibs[i] = esc[i] == 0 ? Double.NaN : (c[i] - l[i]) / esc[i];
        }
    }

    /** Elenco per la serializzazione nella cache. */
    public List<Object> comeLista() {
        List<Object> righe = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Object> r = new ArrayList<>();
            r.add(date[i].toString());
            r.add(tondo(o[i])); r.add(tondo(h[i])); r.add(tondo(l[i])); r.add(tondo(c[i])); r.add((double) Math.round(v[i]));
            righe.add(r);
        }
        return righe;
    }

    private static double tondo(double x) { return Math.round(x * 10000) / 10000.0; }

    @SuppressWarnings("unchecked")
    public static Serie daLista(String titolo, List<Object> righe) {
        int m = righe.size();
        LocalDate[] d = new LocalDate[m];
        double[] o = new double[m], h = new double[m], l = new double[m], c = new double[m],
                v = new double[m];
        for (int i = 0; i < m; i++) {
            List<Object> r = (List<Object>) righe.get(i);
            d[i] = LocalDate.parse((String) r.get(0));
            o[i] = Json.num(r.get(1), Double.NaN);
            h[i] = Json.num(r.get(2), Double.NaN);
            l[i] = Json.num(r.get(3), Double.NaN);
            c[i] = Json.num(r.get(4), Double.NaN);
            v[i] = Json.num(r.get(5), 0);
        }
        return new Serie(titolo, d, o, h, l, c, v);
    }
}
