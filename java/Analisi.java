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

    public static final int[] GIORNI_RIALZO = {7, 15, 30};
    public static final int PESO_MERCATO = 10;
    public static final int PUNTEGGIO_MINIMO = 60;

    /** Somme delle statistiche di un pattern (si possono sommare tra titoli), come sul PC. */
    public static final class Stat {
        public int casi, oltre, pos30;
        public double r7, r15, r30;

        void somma(Stat x) {
            casi += x.casi; oltre += x.oltre; pos30 += x.pos30;
            r7 += x.r7; r15 += x.r15; r30 += x.r30;
        }

        double valore(String k) {
            switch (k) {
                case "oltre": return oltre;
                case "r7": return r7;
                case "r15": return r15;
                case "r30": return r30;
                default: return pos30;
            }
        }
    }

    public static Stat statisticaCompleta(Serie s, String nome, int orizzonte) {
        Strategie.Strategia st = Strategie.get(nome);
        Strategie.Livelli L = st.calcolo.calcola(s);
        Stat r = new Stat();
        int lungo = Math.max(orizzonte, 30);
        int i = Math.max(inizioAnni(s, ANNI_STATISTICA), 1);
        while (i < s.n - lungo) {
            double prezzo = ingresso(st, s, L, i);
            if (Double.isNaN(prezzo)) { i++; continue; }
            r.casi++;
            double max = Double.NEGATIVE_INFINITY;
            for (int k = i + 1; k <= i + orizzonte; k++) if (!Double.isNaN(s.h[k])) max = Math.max(max, s.h[k]);
            if (max >= prezzo * (1 + SOGLIA_STATISTICA)) r.oltre++;
            r.r7 += s.c[i + 7] / prezzo - 1;
            r.r15 += s.c[i + 15] / prezzo - 1;
            r.r30 += s.c[i + 30] / prezzo - 1;
            if (s.c[i + 30] > prezzo) r.pos30++;
            i += orizzonte;
        }
        return r;
    }

    /** [casi, casi oltre +5% entro `orizzonte` giorni] (compatibilita'). */
    public static int[] statisticaPattern(Serie s, String nome, int orizzonte) {
        Stat x = statisticaCompleta(s, nome, orizzonte);
        return new int[]{x.casi, x.oltre};
    }

    /** Statistiche di mercato: chiave "TITOLO|pattern" per i titoli e "pattern" per il totale. */
    public static Map<String, Stat> statisticheMercato(Map<String, Serie> serie, List<String> nomi,
                                                       int orizzonte) {
        Map<String, Stat> out = new LinkedHashMap<>();
        for (String nome : nomi) {
            Stat tot = new Stat();
            for (Map.Entry<String, Serie> e : serie.entrySet()) {
                if (e.getValue().n < 300) continue;
                Stat x = statisticaCompleta(e.getValue(), nome, orizzonte);
                out.put(e.getKey() + "|" + nome, x);
                tot.somma(x);
            }
            out.put(nome, tot);
        }
        return out;
    }

    private static double scala(double x, double zero, double pieno) {
        return Math.min(Math.max((x - zero) / (pieno - zero), 0.0), 1.0);
    }

    /** Punteggio 0-100 (stesso algoritmo del PC). */
    public static int punteggio(Stat titolo, Stat mercato) {
        int n = titolo != null ? titolo.casi : 0, nm = mercato != null ? mercato.casi : 0;
        if (n == 0 && nm == 0) return 0;
        String[] k = {"oltre", "r7", "r15", "r30", "pos30"};
        double[] v = new double[5];
        for (int j = 0; j < 5; j++) {
            double base = nm > 0 ? mercato.valore(k[j]) / nm : 0.0;
            v[j] = n == 0 ? base : (titolo.valore(k[j]) + PESO_MERCATO * base) / (n + PESO_MERCATO);
        }
        double p = 0.40 * scala(v[0], 0.30, 0.80) + 0.25 * scala(v[3], -0.02, 0.08)
                + 0.15 * scala(v[2], -0.02, 0.06) + 0.10 * scala(v[1], -0.02, 0.04)
                + 0.10 * scala(v[4], 0.40, 0.80);
        return (int) Math.floor(p * 100 + 0.5);
    }

    public static int punteggioTitolo(String titolo, String nome, Map<String, Stat> stat) {
        if (stat == null) return 0;
        Stat t = stat.get(titolo + "|" + nome), m = stat.get(nome);
        return punteggio(t != null && t.casi > 0 ? t : null, m != null && m.casi > 0 ? m : null);
    }

    public static String giudizio(int p) {
        if (p >= 80) return "molto favorevole";
        if (p >= 60) return "favorevole";
        if (p >= 40) return "incerto";
        if (p >= 20) return "sfavorevole";
        return "molto sfavorevole";
    }

    // ---------------- punteggio 2: storico + volatilita' + distanza dai massimi + trimestrale
    static final double[] MODELLO_P5 = {1.9062, 0.1999, 0.5007, -0.8193, 0.1848};
    static final double[] MODELLO_P10 = {2.28, 0.687, 0.9928, -1.6832, 0.2752};
    public static final int GIORNI_TRIM = 42;

    /** {atr, dmax} sull'ultima barra completa, null se i dati non bastano. */
    public static double[] fattori(Serie s) {
        if (s == null || s.n < 253) return null;
        int n = s.n;
        double somma = 0.0;
        for (int i = n - 14; i < n; i++)
            somma += Math.max(Math.max(s.h[i] - s.l[i], Math.abs(s.h[i] - s.c[i - 1])), Math.abs(s.l[i] - s.c[i - 1]));
        double massimo = Double.NEGATIVE_INFINITY;
        for (int i = n - 252; i < n; i++) if (!Double.isNaN(s.h[i])) massimo = Math.max(massimo, s.h[i]);
        double atr = somma / 14 / s.c[n - 1];
        if (Double.isNaN(atr) || massimo <= 0 || Double.isNaN(s.c[n - 1])) return null;
        return new double[]{atr, s.c[n - 1] / massimo - 1};
    }

    private static double logistica(double[] k, double storico, double atr, double dmax, double trim) {
        double z = k[0] + k[1] * storico / 100 + k[2] * Math.log(Math.min(Math.max(atr, 0.005), 0.15))
                + k[3] * Math.min(Math.max(dmax, -0.5), 0.0) + k[4] * trim;
        return 1 / (1 + Math.exp(-z));
    }

    /** {punteggio, p5, p10, storico}; p5/p10 NaN se mancano i fattori. */
    public static double[] punteggioSegnale(String titolo, String nome, Map<String, Stat> stat,
                                            double[] fattori, Integer ggTrim) {
        int storico = punteggioTitolo(titolo, nome, stat);
        if (fattori == null) return new double[]{storico, Double.NaN, Double.NaN, storico};
        double trim = ggTrim != null && ggTrim >= 0 && ggTrim <= GIORNI_TRIM ? 1.0 : 0.0;
        double p5 = logistica(MODELLO_P5, storico, fattori[0], fattori[1], trim);
        double p10 = logistica(MODELLO_P10, storico, fattori[0], fattori[1], trim);
        double p = 0.5 * scala(p5, 0.45, 0.70) + 0.5 * scala(p10, 0.15, 0.50);
        return new double[]{Math.floor(p * 100 + 0.5), p5, p10, storico};
    }

    public static Integer giorniATrimestrale(String data, LocalDate oggi) {
        if (data == null || data.isEmpty()) return null;
        try {
            long gg = java.time.temporal.ChronoUnit.DAYS.between(oggi, LocalDate.parse(data));
            return gg >= 0 ? (int) gg : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String testoStatistica(String titolo, String nome, String mercato, int orizzonte,
                                         Map<String, Stat> stat) {
        return testoStatistica(titolo, nome, mercato, orizzonte, stat, null, null, LocalDate.now());
    }

    public static String testoStatistica(String titolo, String nome, String mercato, int orizzonte,
                                         Map<String, Stat> stat, double[] fattori, String dataTrim,
                                         LocalDate oggi) {
        StringBuilder b = new StringBuilder("📈 Pattern identificato: ").append(nome).append('\n');
        Stat st = stat == null ? null : stat.get(titolo + "|" + nome);
        if (st != null && st.casi > 0) {
            int c = st.casi;
            b.append("Negli ultimi ").append(ANNI_STATISTICA).append(" anni su ").append(titolo)
                    .append(" si è verificato ").append(c).append(" volte: in ").append(st.oltre)
                    .append(" (").append((long) Math.floor(st.oltre * 100.0 / c + 0.5))
                    .append("%) è salito oltre +5% entro ").append(orizzonte).append(" giorni.");
            if (c < 10) b.append(" (pochi casi: dato poco affidabile)");
            b.append("\nRialzo medio: a 7 giorni ").append(Formato.pct(st.r7 / c * 100, 1))
                    .append(" · a 15 giorni ").append(Formato.pct(st.r15 / c * 100, 1))
                    .append(" · a 30 giorni ").append(Formato.pct(st.r30 / c * 100, 1));
        } else {
            b.append("Su ").append(titolo).append(" il pattern non si era mai verificato negli ultimi ")
                    .append(ANNI_STATISTICA).append(" anni.");
        }
        Stat tot = stat == null ? null : stat.get(nome);
        if (tot != null && tot.casi > 0) {
            b.append("\nSu tutti i titoli ").append(mercato).append(": ").append(tot.casi).append(" volte, ")
                    .append((long) Math.floor(tot.oltre * 100.0 / tot.casi + 0.5)).append("% oltre +5%, ")
                    .append("rialzo medio a 30 giorni ").append(Formato.pct(tot.r30 / tot.casi * 100, 1)).append('.');
        }
        Integer gg = giorniATrimestrale(dataTrim, oggi);
        double[] ps = punteggioSegnale(titolo, nome, stat, fattori, gg);
        int punti = (int) ps[0];
        boolean conModello = !Double.isNaN(ps[1]);
        if (fattori != null)
            b.append("\nVolatilità giornaliera media: ").append(Formato.num(fattori[0] * 100, 1))
                    .append("% · dal massimo a 52 settimane: ").append(Formato.pct(fattori[1] * 100, 1));
        if (gg != null && gg <= GIORNI_TRIM)
            b.append("\n📅 Trimestrale il ").append(dataTrim, 8, 10).append('/').append(dataTrim, 5, 7).append(" (")
                    .append(gg == 0 ? "oggi" : gg == 1 ? "domani" : "tra " + gg + " giorni")
                    .append("): possibili salti di prezzo.");
        if (conModello)
            b.append("\nProbabilità stimata: +5% prima dello stop ").append((long) Math.floor(ps[1] * 100 + 0.5))
                    .append("% · +10% prima dello stop ").append((long) Math.floor(ps[2] * 100 + 0.5)).append('%');
        b.append("\n🎯 PUNTEGGIO: ").append(punti).append("/100 - ").append(giudizio(punti));
        if (conModello) b.append(" (storico del pattern ").append((int) ps[3]).append("/100)");
        return b.toString();
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
