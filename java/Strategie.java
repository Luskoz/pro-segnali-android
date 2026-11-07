package it.prosegnali.motore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I pattern (stesse regole della versione PC). Per ogni barra t calcolano i livelli da usare
 * il GIORNO DOPO: livello d'acquisto, obiettivo, uscita sul minimo (trailing), priorita'.
 */
public final class Strategie {
    private Strategie() {}

    public static final double GUADAGNO_MINIMO = 0.05;   // regola del 5%

    public enum Tipo { SOTTO, SOPRA, APERTURA }

    /** Livelli di una strategia per tutte le barre (NaN = non previsto). */
    public static final class Livelli {
        public final double[] livello, obiettivo, trailing, priorita;

        Livelli(int n) {
            livello = Serie.nan(n);
            obiettivo = Serie.nan(n);
            trailing = Serie.nan(n);
            priorita = new double[n];
        }
    }

    public interface Calcolo { Livelli calcola(Serie s); }

    public static final class Strategia {
        public final String nome, descrizione;
        public final Tipo tipo;
        public final double stopPct, obiettivoPct;      // obiettivoPct = NaN se non fisso
        public final int maxGiorni;
        public final boolean disattivato;
        public final Calcolo calcolo;

        Strategia(String nome, Tipo tipo, double stopPct, int maxGiorni, double obiettivoPct,
                  boolean disattivato, String descrizione, Calcolo calcolo) {
            this.nome = nome; this.tipo = tipo; this.stopPct = stopPct; this.maxGiorni = maxGiorni;
            this.obiettivoPct = obiettivoPct; this.disattivato = disattivato;
            this.descrizione = descrizione; this.calcolo = calcolo;
        }
    }

    private static final Map<String, Strategia> TUTTE = new LinkedHashMap<>();
    public static final List<String> NOMI;              // elenco completo: non cambiare l'ordine
    public static final List<String> ATTIVABILI;

    private static void aggiungi(Strategia s) { TUTTE.put(s.nome, s); }

    static {
        double N = Double.NaN;
        aggiungi(new Strategia("RSI2 rimbalzo", Tipo.SOTTO, 0.08, 7, N, false,
                "Titolo in trend (sopra media 200 gg) che scende al prezzo che porta l'RSI a 2 giorni "
                        + "sotto 10; vende sulla media a 5 gg, stop -8%, max 7 giorni.",
                Strategie::rsi2));
        aggiungi(new Strategia("Micro-rimbalzo +1%", Tipo.SOTTO, 0.03, 3, 0.01, true,
                "DISATTIVATO (obiettivo +1%, sotto il minimo del 5%).", Strategie::micro));
        aggiungi(new Strategia("Bollinger in trend", Tipo.SOTTO, 0.08, 10, N, false,
                "Titolo in trend che scende sotto la banda di Bollinger inferiore; vende sulla media "
                        + "a 20 gg, stop -8%, max 10 giorni.", Strategie::bollinger));
        aggiungi(new Strategia("Calo improvviso -3%", Tipo.SOTTO, 0.05, 3, N, true,
                "DISATTIVATO (punta a recuperare solo il 3%, sotto il minimo del 5%).",
                Strategie::calo));
        aggiungi(new Strategia("IBS rimbalzo", Tipo.APERTURA, 0.05, 4, N, false,
                "Titolo in trend che ieri ha chiuso sui minimi della giornata: compra all'apertura, "
                        + "vende sopra il massimo di ieri, stop -5%, max 4 giorni.", Strategie::ibs));
        aggiungi(new Strategia("Pullback media 20", Tipo.SOTTO, 0.06, 10, N, false,
                "Titolo in forte trend (medie 20>50>200) che ritorna sulla media a 20 gg; vende sul "
                        + "massimo di 20 gg, stop -6%, max 10 giorni.", Strategie::pullback));
        aggiungi(new Strategia("Breakout 20gg", Tipo.SOPRA, 0.07, 20, N, false,
                "Titolo in trend che supera il massimo di 20 giorni; vende sotto il minimo di 10 gg, "
                        + "stop -7%, max 20 giorni.", Strategie::breakout));
        aggiungi(new Strategia("NR7 breakout", Tipo.SOPRA, 0.05, 5, N, false,
                "Dopo la giornata piu' 'stretta' degli ultimi 7, compra alla rottura del suo massimo; "
                        + "vende sotto il minimo del giorno prima, stop -5%, max 5 giorni.",
                Strategie::nr7));
        aggiungi(new Strategia("Massimi 52 settimane", Tipo.SOPRA, 0.08, 30, N, false,
                "Titolo in trend che rompe il massimo dell'ultimo anno (momentum); vende sotto il "
                        + "minimo di 20 gg, stop -8%, max 30 giorni.", Strategie::massimi));
        NOMI = Collections.unmodifiableList(new ArrayList<>(TUTTE.keySet()));
        List<String> a = new ArrayList<>();
        for (Strategia s : TUTTE.values()) if (!s.disattivato) a.add(s.nome);
        ATTIVABILI = Collections.unmodifiableList(a);
    }

    public static Strategia get(String nome) { return TUTTE.get(nome); }

    public static boolean esiste(String nome) { return TUTTE.containsKey(nome); }

    // ------------------------------------------------------------------ regola del 5%

    /** Rialzo previsto dal pattern da quel prezzo (NaN se il pattern non ha obiettivo). */
    public static double rialzoAtteso(String nome, double prezzo, double obiettivoLivello) {
        Strategia s = get(nome);
        if (!Double.isNaN(s.obiettivoPct)) return s.obiettivoPct;
        if (Double.isNaN(obiettivoLivello) || prezzo == 0 || Double.isNaN(prezzo)) return Double.NaN;
        return obiettivoLivello / prezzo - 1;
    }

    public static boolean ingressoValido(String nome, double prezzo, double obiettivoLivello) {
        Strategia s = get(nome);
        if (s == null || s.disattivato) return false;
        double r = rialzoAtteso(nome, prezzo, obiettivoLivello);
        return Double.isNaN(r) || r >= GUADAGNO_MINIMO - 1e-9;
    }

    /** Prezzo di vendita in guadagno (NaN se il pattern non lo prevede); mai sotto +5%. */
    public static double obiettivoEffettivo(String nome, double prezzoAcquisto, double obiettivoLivello) {
        Strategia s = get(nome);
        double minimo = prezzoAcquisto * (1 + GUADAGNO_MINIMO);
        if (!Double.isNaN(s.obiettivoPct)) return Math.max(prezzoAcquisto * (1 + s.obiettivoPct), minimo);
        if (Double.isNaN(obiettivoLivello)) return Double.NaN;
        return Math.max(obiettivoLivello, minimo);
    }

    // ------------------------------------------------------------------ calcoli

    private static double[] livelloRsi2(Serie s) {
        double[] r = Serie.nan(s.n);
        for (int i = 0; i < s.n; i++) {
            double liv = Math.min(s.c[i], s.c[i] + s.avgL[i] - 9 * s.avgG[i]);
            if (s.c[i] > s.sma200[i] && liv > 0) r[i] = liv;      // NaN nei confronti => falso
        }
        return r;
    }

    static Livelli rsi2(Serie s) {
        Livelli L = new Livelli(s.n);
        double[] liv = livelloRsi2(s);
        for (int i = 0; i < s.n; i++) {
            L.livello[i] = liv[i];
            L.obiettivo[i] = s.sma5[i];
            L.priorita[i] = s.sma5[i] / liv[i] - 1;
        }
        return L;
    }

    static Livelli micro(Serie s) {
        Livelli L = new Livelli(s.n);
        double[] liv = livelloRsi2(s);
        for (int i = 0; i < s.n; i++) {
            L.livello[i] = liv[i];
            L.priorita[i] = s.sma5[i] / liv[i] - 1;
        }
        return L;
    }

    static Livelli bollinger(Serie s) {
        Livelli L = new Livelli(s.n);
        for (int i = 18; i < s.n; i++) {
            double somma = 0, quadrati = 0;
            for (int k = i - 18; k <= i; k++) { somma += s.c[k]; quadrati += s.c[k] * s.c[k]; }
            double chiusura = s.c[i];
            double livello;
            if (distanzaBanda(somma, quadrati, chiusura) <= 0) {
                livello = chiusura;
            } else {
                double lo = chiusura * 0.5, hi = chiusura;
                for (int it = 0; it < 45; it++) {
                    double mid = (lo + hi) / 2;
                    if (distanzaBanda(somma, quadrati, mid) <= 0) lo = mid; else hi = mid;
                }
                livello = lo;
            }
            if (s.c[i] > s.sma200[i]) L.livello[i] = livello;
        }
        for (int i = 0; i < s.n; i++) {
            L.obiettivo[i] = s.sma20[i];
            L.priorita[i] = s.sma20[i] / L.livello[i] - 1;
        }
        return L;
    }

    /** p meno la banda di Bollinger inferiore calcolata con p come 20a chiusura. */
    private static double distanzaBanda(double somma, double quadrati, double p) {
        double m = (somma + p) / 20;
        double var = Math.max((quadrati + p * p - 20 * m * m) / 19, 0);
        return p - (m - 2 * Math.sqrt(var));
    }

    static Livelli calo(Serie s) {
        Livelli L = new Livelli(s.n);
        for (int i = 0; i < s.n; i++) {
            if (s.c[i] > s.sma200[i] && s.c[i] > s.sma50[i]) L.livello[i] = s.c[i] * 0.97;
            L.obiettivo[i] = s.c[i];
            L.priorita[i] = s.c[i] / s.sma200[i] - 1;
        }
        return L;
    }

    static Livelli ibs(Serie s) {
        Livelli L = new Livelli(s.n);
        for (int i = 0; i < s.n; i++) {
            if (s.ibs[i] < 0.15 && s.c[i] > s.sma200[i] && s.c[i] < s.sma5[i]) L.livello[i] = s.c[i];
            L.obiettivo[i] = s.h[i];
            L.priorita[i] = -s.ibs[i];
        }
        return L;
    }

    static Livelli nr7(Serie s) {
        Livelli L = new Livelli(s.n);
        for (int i = 0; i < s.n; i++) {
            if (s.nr7[i] && s.c[i] > s.sma50[i] && s.sma50[i] > s.sma200[i]) L.livello[i] = s.h[i];
            L.trailing[i] = s.l[i];
            L.priorita[i] = -(L.livello[i] / s.c[i] - 1);
        }
        return L;
    }

    static Livelli massimi(Serie s) {
        Livelli L = new Livelli(s.n);
        for (int i = 0; i < s.n; i++) {
            if (s.c[i] > s.sma50[i] && s.sma50[i] > s.sma200[i]) L.livello[i] = s.max252[i];
            L.trailing[i] = s.min20[i];
            L.priorita[i] = -(L.livello[i] / s.c[i] - 1);
        }
        return L;
    }

    static Livelli breakout(Serie s) {
        Livelli L = new Livelli(s.n);
        for (int i = 0; i < s.n; i++) {
            if (s.c[i] > s.sma200[i]) L.livello[i] = s.max20[i];
            L.trailing[i] = s.min10[i];
            L.priorita[i] = -(L.livello[i] / s.c[i] - 1);
        }
        return L;
    }

    static Livelli pullback(Serie s) {
        Livelli L = new Livelli(s.n);
        for (int i = 0; i < s.n; i++) {
            double m20 = s.sma20[i];
            if (s.c[i] > m20 && m20 > s.sma50[i] && s.sma50[i] > s.sma200[i]) L.livello[i] = m20;
            L.obiettivo[i] = s.max20[i];
            L.priorita[i] = s.max20[i] / L.livello[i] - 1;
        }
        return L;
    }
}
