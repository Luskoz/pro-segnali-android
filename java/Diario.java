package it.prosegnali.motore;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Diario dei segnali: stessa logica della versione PC (motore.esito_segnale e riepilogo_diario). */
public final class Diario {
    public static final int GIORNI = 30;
    public static final int CONSERVA = 400;

    private Diario() {}

    /** Esito di un segnale: date "AAAA-MM-GG" e barre complete. */
    public static Map<String, Object> esito(double prezzo, double stopPct, String[] date,
                                            double[] h, double[] l, double[] c, String dataSegnale) {
        int i = 0;
        while (i < date.length && date[i].compareTo(dataSegnale) < 0) i++;
        if (i >= date.length || !date[i].equals(dataSegnale)) i--;
        int dopo = date.length - 1 - i;
        int giorni = Math.min(dopo, GIORNI);
        Map<String, Object> es = new LinkedHashMap<>();
        es.put("giorni", (long) giorni);
        es.put("completo", giorni >= GIORNI);
        for (int g : new int[]{7, 15, 30}) {
            boolean ok = dopo >= g && !Double.isNaN(c[i + g]);
            es.put("r" + g, ok ? c[i + g] / prezzo - 1 : null);
        }
        double stop = prezzo * (1 - stopPct);
        double mx = 0.0, mn = 0.0;
        Long g5 = null, g10 = null, gstop = null;
        for (int k = 1; k <= giorni; k++) {
            if (Double.isNaN(h[i + k]) || Double.isNaN(l[i + k])) continue;
            if (l[i + k] <= stop && gstop == null) gstop = (long) k;
            if (h[i + k] >= prezzo * 1.05 && g5 == null) g5 = (long) k;
            if (h[i + k] >= prezzo * 1.10 && g10 == null) g10 = (long) k;
            mx = Math.max(mx, h[i + k] / prezzo - 1);
            mn = Math.min(mn, l[i + k] / prezzo - 1);
        }
        es.put("max", mx);
        es.put("min", mn);
        es.put("g5", g5);
        es.put("g10", g10);
        es.put("gstop", gstop);
        String primo = null;
        if (g5 != null && (gstop == null || g5 < gstop)) primo = "+5%";
        else if (gstop != null) primo = "stop";
        else if (giorni >= GIORNI) primo = "nessuno";
        es.put("primo", primo);
        return es;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> esitoDi(Map<String, Object> s) {
        Object e = s.get("esito");
        return e instanceof Map ? (Map<String, Object>) e : new LinkedHashMap<>();
    }

    /** Id dei segnali seguiti da un acquisto dello stesso titolo entro 3 giorni. */
    public static Set<String> comprati(List<Map<String, Object>> segnali, List<Map<String, Object>> posizioni,
                                       List<Map<String, Object>> storico) {
        List<Map<String, Object>> acquisti = new ArrayList<>(posizioni);
        acquisti.addAll(storico);
        Set<String> out = new HashSet<>();
        for (Map<String, Object> s : segnali) {
            LocalDate d0 = LocalDate.parse(String.valueOf(s.get("data")));
            for (Map<String, Object> a : acquisti) {
                String d = a.get("data") == null ? "" : String.valueOf(a.get("data"));
                if (!String.valueOf(a.get("titolo")).equals(s.get("titolo")) || d.length() < 10) continue;
                try {
                    long diff = ChronoUnit.DAYS.between(d0, LocalDate.parse(d.substring(0, 10)));
                    if (diff >= 0 && diff <= 3) { out.add(String.valueOf(s.get("id"))); break; }
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    public static final class Riga {
        public String nome;
        public int segnali, conclusi;
        public Double p5, p10, pstop, r30;
    }

    private static double punti(Map<String, Object> s) { return Json.num(s.get("punteggio"), 0); }

    private static boolean inviato(Map<String, Object> s) { return Boolean.TRUE.equals(s.get("inviato")); }

    public static List<Riga> riepilogo(List<Map<String, Object>> segnali, List<Map<String, Object>> posizioni,
                                       List<Map<String, Object>> storico) {
        Set<String> comp = comprati(segnali, posizioni, storico);
        Map<String, Predicate<Map<String, Object>>> gruppi = new LinkedHashMap<>();
        gruppi.put("Tutti i segnali", s -> true);
        gruppi.put("Inviati (sopra la soglia)", Diario::inviato);
        gruppi.put("Scartati (sotto la soglia)", s -> !inviato(s));
        gruppi.put("Comprati da te", s -> comp.contains(String.valueOf(s.get("id"))));
        gruppi.put("Inviati ma non comprati", s -> inviato(s) && !comp.contains(String.valueOf(s.get("id"))));
        gruppi.put("Punteggio 80-100", s -> punti(s) >= 80);
        gruppi.put("Punteggio 60-79", s -> punti(s) >= 60 && punti(s) < 80);
        gruppi.put("Punteggio 40-59", s -> punti(s) >= 40 && punti(s) < 60);
        gruppi.put("Punteggio 0-39", s -> punti(s) < 40);
        List<Riga> out = new ArrayList<>();
        for (Map.Entry<String, Predicate<Map<String, Object>>> g : gruppi.entrySet()) {
            Riga r = new Riga();
            r.nome = g.getKey();
            int n = 0, a5 = 0, a10 = 0, ast = 0, nr = 0;
            double somma = 0;
            for (Map<String, Object> s : segnali) {
                if (!g.getValue().test(s)) continue;
                r.segnali++;
                Map<String, Object> es = esitoDi(s);
                Object primo = es.get("primo");
                if (primo == null) continue;
                n++;
                if ("+5%".equals(primo)) a5++;
                if ("stop".equals(primo)) ast++;
                Object g10 = es.get("g10"), gs = es.get("gstop");
                if (g10 != null && (gs == null || Json.num(g10, 0) < Json.num(gs, 0))) a10++;
                if (es.get("r30") != null) { somma += Json.num(es.get("r30"), 0); nr++; }
            }
            r.conclusi = n;
            if (n > 0) { r.p5 = (double) a5 / n; r.p10 = (double) a10 / n; r.pstop = (double) ast / n; }
            if (nr > 0) r.r30 = somma / nr;
            out.add(r);
        }
        return out;
    }
}
