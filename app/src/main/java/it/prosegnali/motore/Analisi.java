package it.prosegnali.motore;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Livelli del giorno, riferimenti delle posizioni, statistiche e classifica dei pattern. */
public final class Analisi {
    private Analisi() {}

    public static final int ANNI_STATISTICA = 5;
    public static final double SOGLIA_STATISTICA = 0.05;
    public static final int CASI_MINIMI_CLASSIFICA = 30;
    public static final double VANTAGGIO_MINIMO = 3.0;
    public static final int[] ORIZZONTI = {10, 20, 30};

    // ------------------------------------------------------------------ dati del giorno

    /** Toglie la barra di oggi se la seduta non e' ancora chiusa (+30 minuti). */
    public static Serie soloBarreComplete(Serie s, Mercati.Mercato m, ZonedDateTime adesso) {
        ZonedDateTime ora = adesso.withZoneSameInstant(m.fuso);
        LocalDateTime fine = ora.toLocalDate().atTime(m.chiusura).plusMinutes(30);
        if (s.n > 0 && !s.date[s.n - 1].isBefore(ora.toLocalDate())
                && ora.toLocalDateTime().isBefore(fine)) {
            return s.senzaUltima();
        }
        return s;
    }

    /** True se l'indice e' sopra la sua media a 200 giorni (ultima barra). */
    public static Boolean indiceSopraMedia(Serie indice) {
        if (indice == null || indice.n < 200) return null;
        int i = indice.n - 1;
        return indice.c[i] > indice.sma200[i];
    }

    /** Un possibile segnale per oggi. */
    public static final class Livello {
        public String titolo, strategia, data;
        public Strategie.Tipo tipo;
        public double livello, obiettivo, trailing, priorita, chiusura, distanzaPct;
    }

    /** Per ogni titolo, i livelli delle strategie attive che hanno un segnale (regola del 5%). */
    public static Map<String, List<Livello>> livelliOggi(Map<String, Serie> serie, List<String> nomi) {
        Map<String, List<Livello>> elenco = new LinkedHashMap<>();
        for (Serie s : serie.values()) {
            if (s.n < 260) continue;
            int i = s.n - 1;
            double chiusura = s.c[i];
            for (String nome : nomi) {
                Strategie.Strategia st = Strategie.get(nome);
                if (st == null) continue;
                Strategie.Livelli L = st.calcolo.calcola(s);
                double liv = L.livello[i];
                if (Double.isNaN(liv)) continue;
                double prezzoIngresso = st.tipo == Strategie.Tipo.APERTURA ? chiusura : liv;
                if (!Strategie.ingressoValido(nome, prezzoIngresso, L.obiettivo[i])) continue;
                Livello x = new Livello();
                x.titolo = s.titolo;
                x.strategia = nome;
                x.tipo = st.tipo;
                x.livello = Math.round(liv * 100) / 100.0;
                x.obiettivo = L.obiettivo[i];
                x.trailing = L.trailing[i];
                x.priorita = Double.isNaN(L.priorita[i]) ? 0 : L.priorita[i];
                x.chiusura = Math.round(chiusura * 100) / 100.0;
                x.distanzaPct = st.tipo == Strategie.Tipo.APERTURA ? 0
                        : Math.round((liv / chiusura - 1) * 10000) / 100.0;
                x.data = s.date[i].toString();
                elenco.computeIfAbsent(s.titolo, k -> new ArrayList<>()).add(x);
            }
        }
        return elenco;
    }

    // ------------------------------------------------------------------ posizioni

    /** Stop, obiettivo, uscita sul minimo e giorni (stessa logica della versione PC). */
    public static final class Riferimenti {
        public double stop, obiettivo = Double.NaN, trailing = Double.NaN;
        public int giorni, maxGiorni;
    }

    /** Giorni lavorativi in [inizio, fine) come numpy.busday_count. */
    public static int giorniLavorativi(LocalDate inizio, LocalDate fine) {
        int n = 0;
        for (LocalDate d = inizio; d.isBefore(fine); d = d.plusDays(1)) {
            DayOfWeek g = d.getDayOfWeek();
            if (g != DayOfWeek.SATURDAY && g != DayOfWeek.SUNDAY) n++;
        }
        return n;
    }

    public static Riferimenti riferimenti(Map<String, Serie> serie, String titolo, String strategia,
                                          double prezzo, String dataAcquisto, LocalDate oggi) {
        Strategie.Strategia st = Strategie.get(strategia);
        if (st == null) return null;
        Riferimenti r = new Riferimenti();
        r.stop = Math.round(prezzo * (1 - st.stopPct) * 100) / 100.0;
        r.maxGiorni = st.maxGiorni;
        r.giorni = giorniLavorativi(LocalDate.parse(dataAcquisto), oggi) + 1;
        Serie s = serie == null ? null : serie.get(titolo);
        double obLivello = Double.NaN;
        if (s != null && s.n > 20) {
            Strategie.Livelli L = st.calcolo.calcola(s);
            obLivello = L.obiettivo[s.n - 1];
            if (!Double.isNaN(L.trailing[s.n - 1])) r.trailing = Math.round(L.trailing[s.n - 1] * 100) / 100.0;
        } else if (st.tipo != Strategie.Tipo.SOPRA) {
            obLivello = prezzo * (1 + Strategie.GUADAGNO_MINIMO);
        }
        double ob = Strategie.obiettivoEffettivo(strategia, prezzo, obLivello);
        if (!Double.isNaN(ob)) r.obiettivo = Math.round(ob * 100) / 100.0;
        return r;
    }

    // ------------------------------------------------------------------ statistiche

    private static int inizioAnni(Serie s, int anni) {
        LocalDate da = s.date[s.n - 1].minusYears(anni);
        int i = 0;
        while (i < s.n && s.date[i].isBefore(da)) i++;
        return i;
    }

    /** Prezzo d'ingresso alla barra i (livelli calcolati il giorno prima), NaN se nessun segnale. */
    private static double ingresso(Strategie.Strategia st, Serie s, Strategie.Livelli L, int i) {
        if (i < 1) return Double.NaN;
        double liv = L.livello[i - 1];
        if (Double.isNaN(liv) || Double.isNaN(s.o[i])) return Double.NaN;
        double prezzo;
        if (st.tipo == Strategie.Tipo.SOTTO && s.l[i] <= liv) prezzo = Math.min(s.o[i], liv);
        else if (st.tipo == Strategie.Tipo.SOPRA && s.h[i] >= liv) prezzo = Math.max(s.o[i], liv);
        else if (st.tipo == Strategie.Tipo.APERTURA) prezzo = s.o[i];
        else return Double.NaN;
        return Strategie.ingressoValido(st.nome, prezzo, L.obiettivo[i - 1]) ? prezzo : Double.NaN;
    }

    /** [casi, casi oltre +5% entro `orizzonte` giorni] negli ultimi anni (come la versione PC). */
    public static int[] statisticaPattern(Serie s, String nome, int orizzonte) {
        Strategie.Strategia st = Strategie.get(nome);
        Strategie.Livelli L = st.calcolo.calcola(s);
        int casi = 0, oltre = 0;
        int i = Math.max(inizioAnni(s, ANNI_STATISTICA), 1);
        while (i < s.n - orizzonte) {
            double prezzo = ingresso(st, s, L, i);
            if (Double.isNaN(prezzo)) { i++; continue; }
            casi++;
            double max = Double.NEGATIVE_INFINITY;
            for (int k = i + 1; k <= i + orizzonte; k++) max = Math.max(max, s.h[k]);
            if (max >= prezzo * (1 + SOGLIA_STATISTICA)) oltre++;
            i += orizzonte;
        }
        return new int[]{casi, oltre};
    }

    public static String testoStatistica(Map<String, Serie> serie, String titolo, String nome,
                                         String mercato, int orizzonte,
                                         Map<String, int[]> perStrategia) {
        StringBuilder b = new StringBuilder("📈 Pattern: ").append(nome).append('\n');
        Serie s = serie.get(titolo);
        int[] st = s != null && s.n >= 300 ? statisticaPattern(s, nome, orizzonte) : new int[]{0, 0};
        if (st[0] > 0) {
            b.append("Su ").append(titolo).append(" negli ultimi ").append(ANNI_STATISTICA)
                    .append(" anni si è verificato ").append(st[0]).append(" volte: in ").append(st[1])
                    .append(" (").append(Math.round(st[1] * 100.0 / st[0])).append("%) è salito oltre +5% entro ")
                    .append(orizzonte).append(" giorni.");
            if (st[0] < 10) b.append(" (pochi casi: dato poco affidabile)");
        } else {
            b.append("Su ").append(titolo).append(" il pattern non si era mai verificato negli ultimi ")
                    .append(ANNI_STATISTICA).append(" anni.");
        }
        int[] tot = perStrategia == null ? null : perStrategia.get(nome);
        if (tot != null && tot[0] > 0) {
            b.append("\nSu tutti i titoli ").append(mercato).append(": ").append(tot[0]).append(" volte, ")
                    .append(Math.round(tot[1] * 100.0 / tot[0])).append("% oltre +5%.");
        }
        return b.toString();
    }

    public static Map<String, int[]> statisticheMercato(Map<String, Serie> serie, List<String> nomi,
                                                        int orizzonte) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (String nome : nomi) {
            int c = 0, o = 0;
            for (Serie s : serie.values()) {
                if (s.n < 300) continue;
                int[] x = statisticaPattern(s, nome, orizzonte);
                c += x[0];
                o += x[1];
            }
            out.put(nome, new int[]{c, o});
        }
        return out;
    }

    // ------------------------------------------------------------------ classifica

    /** Per ogni orizzonte: true se +soglia arriva prima dello stop entro quei giorni. */
    static boolean[] successi(double[] h, double[] l, int i, double prezzo, double stopPct, double soglia,
                              int[] orizzonti) {
        double obiettivo = prezzo * (1 + soglia), stop = prezzo * (1 - stopPct);
        boolean[] esito = new boolean[orizzonti.length];
        if (l[i] <= stop) return esito;
        int max = 0;
        for (int o : orizzonti) max = Math.max(max, o);
        for (int k = 1; k <= max; k++) {
            if (i + k >= h.length) break;
            if (l[i + k] <= stop) break;
            if (h[i + k] >= obiettivo) {
                for (int j = 0; j < orizzonti.length; j++) if (k <= orizzonti[j]) esito[j] = true;
                break;
            }
        }
        return esito;
    }

    public static final class RigaClassifica {
        public String pattern, nota = "";
        public int casi, orizzonte;
        public double stopPct, pct10, pct20, pct30, pct, base, vantaggio;
        public boolean idoneo;
    }

    /** Classifica dei pattern: % di +5% prima dello stop contro la base con lo stesso stop. */
    public static List<RigaClassifica> classifica(Map<String, Serie> serie, int riferimento) {
        int lungo = 30;
        TreeSet<Double> stopUsati = new TreeSet<>();
        for (String n : Strategie.NOMI) stopUsati.add(Strategie.get(n).stopPct);
        stopUsati.add(0.08);
        Map<Double, int[]> basi = new LinkedHashMap<>();          // [casi, s10, s20, s30]
        for (double sp : stopUsati) basi.put(sp, new int[4]);
        Map<String, int[]> conteggi = new LinkedHashMap<>();
        for (String n : Strategie.NOMI) conteggi.put(n, new int[4]);

        for (Serie s : serie.values()) {
            if (s.n < 300) continue;
            int i0 = Math.max(inizioAnni(s, ANNI_STATISTICA), 1);
            for (int i = i0; i < s.n - lungo; i += 5) {
                if (Double.isNaN(s.o[i])) continue;
                for (Map.Entry<Double, int[]> e : basi.entrySet()) {
                    boolean[] es = successi(s.h, s.l, i, s.o[i], e.getKey(), SOGLIA_STATISTICA, ORIZZONTI);
                    int[] b = e.getValue();
                    b[0]++;
                    for (int k = 0; k < 3; k++) if (es[k]) b[k + 1]++;
                }
            }
            for (String nome : Strategie.NOMI) {
                Strategie.Strategia st = Strategie.get(nome);
                Strategie.Livelli L = st.calcolo.calcola(s);
                int[] c = conteggi.get(nome);
                int i = i0;
                while (i < s.n - lungo) {
                    double prezzo = ingresso(st, s, L, i);
                    if (Double.isNaN(prezzo)) { i++; continue; }
                    boolean[] es = successi(s.h, s.l, i, prezzo, st.stopPct, SOGLIA_STATISTICA, ORIZZONTI);
                    c[0]++;
                    for (int k = 0; k < 3; k++) if (es[k]) c[k + 1]++;
                    i += 10;
                }
            }
        }
        int idx = riferimento == 10 ? 1 : riferimento == 20 ? 2 : 3;
        List<RigaClassifica> righe = new ArrayList<>();
        for (String nome : Strategie.NOMI) {
            Strategie.Strategia st = Strategie.get(nome);
            int[] d = conteggi.get(nome);
            int[] b = basi.get(st.stopPct);
            RigaClassifica r = new RigaClassifica();
            r.pattern = nome;
            r.casi = d[0];
            r.stopPct = st.stopPct;
            r.orizzonte = riferimento;
            r.pct10 = perc(d, 1); r.pct20 = perc(d, 2); r.pct30 = perc(d, 3);
            r.pct = perc(d, idx);
            r.base = perc(b, idx);
            r.vantaggio = Math.round((r.pct - r.base) * 10) / 10.0;
            if (st.disattivato) r.nota = "disattivato (obiettivo sotto il 5%)";
            else if (d[0] < CASI_MINIMI_CLASSIFICA) r.nota = "pochi casi (<" + CASI_MINIMI_CLASSIFICA + ")";
            else if (r.vantaggio < VANTAGGIO_MINIMO)
                r.nota = r.vantaggio <= 0 ? "non batte la base" : "vantaggio troppo piccolo (<3 punti)";
            r.idoneo = r.nota.isEmpty();
            righe.add(r);
        }
        righe.sort(Comparator.comparing((RigaClassifica r) -> r.idoneo)
                .thenComparingDouble(r -> r.vantaggio).thenComparingDouble(r -> r.pct).reversed());
        return righe;
    }

    private static double perc(int[] d, int k) {
        return d[0] == 0 ? 0 : Math.round(d[k] * 1000.0 / d[0]) / 10.0;
    }

    public static List<String> migliori(List<RigaClassifica> classifica, int quanti) {
        List<String> out = new ArrayList<>();
        for (RigaClassifica r : classifica) if (r.idoneo && out.size() < quanti) out.add(r.pattern);
        return out;
    }
}
