package it.prosegnali.motore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prezzi: storico giornaliero (Yahoo), prezzi in tempo reale USA (Finnhub) ed europei (Yahoo,
 * con il ritardo misurato) e cambio EUR/USD. Stesse fonti della versione PC.
 */
public final class Prezzi {
    private Prezzi() {}

    /** Prezzo attuale e da quanti minuti e' aggiornato (NaN se ignoto). */
    public static final class Quotazione {
        public final double prezzo, ritardoMin;

        public Quotazione(double prezzo, double ritardoMin) { this.prezzo = prezzo; this.ritardoMin = ritardoMin; }
    }

    private static String simbolo(String t) { return Rete.codifica(t); }

    // ------------------------------------------------------------------ storico giornaliero

    /** Prezzi giornalieri rettificati (dividendi e frazionamenti), come yfinance auto_adjust. */
    @SuppressWarnings("unchecked")
    public static Serie giornalieri(String titolo, String periodo) throws Exception {
        String u = "https://query2.finance.yahoo.com/v8/finance/chart/" + simbolo(titolo)
                + "?range=" + periodo + "&interval=1d&includeAdjustedClose=true&events=div%2Csplit";
        Rete.Risposta r = Rete.get(u, 30);
        if (r.codice != 200) throw new IllegalStateException(titolo + ": HTTP " + r.codice);
        Map<String, Object> ris = (Map<String, Object>) ((List<Object>) ((Map<String, Object>)
                Json.oggetto(r.testo()).get("chart")).get("result")).get(0);
        Map<String, Object> meta = (Map<String, Object>) ris.get("meta");
        long offset = (long) Json.num(meta.get("gmtoffset"), 0);
        List<Object> tempi = (List<Object>) ris.get("timestamp");
        Map<String, Object> ind = (Map<String, Object>) ris.get("indicators");
        Map<String, Object> q = (Map<String, Object>) ((List<Object>) ind.get("quote")).get(0);
        List<Object> adj = null;
        if (ind.get("adjclose") != null)
            adj = (List<Object>) ((Map<String, Object>) ((List<Object>) ind.get("adjclose")).get(0)).get("adjclose");
        List<Object> O = (List<Object>) q.get("open"), H = (List<Object>) q.get("high"),
                L = (List<Object>) q.get("low"), C = (List<Object>) q.get("close"), V = (List<Object>) q.get("volume");
        List<LocalDate> d = new ArrayList<>();
        List<double[]> righe = new ArrayList<>();
        for (int i = 0; tempi != null && i < tempi.size(); i++) {
            double o = Json.num(O.get(i), Double.NaN), h = Json.num(H.get(i), Double.NaN),
                    l = Json.num(L.get(i), Double.NaN), c = Json.num(C.get(i), Double.NaN);
            if (Double.isNaN(o) || Double.isNaN(h) || Double.isNaN(l) || Double.isNaN(c) || c <= 0) continue;
            double a = adj == null ? c : Json.num(adj.get(i), c);
            double f = a / c;
            LocalDate giorno = Instant.ofEpochSecond(((Number) tempi.get(i)).longValue() + offset)
                    .atZone(ZoneOffset.UTC).toLocalDate();
            if (!d.isEmpty() && d.get(d.size() - 1).equals(giorno)) {      // doppione del giorno
                d.remove(d.size() - 1);
                righe.remove(righe.size() - 1);
            }
            d.add(giorno);
            righe.add(new double[]{o * f, h * f, l * f, a, Json.num(V.get(i), 0)});
        }
        int n = d.size();
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            double[] x = righe.get(i);
            o[i] = x[0]; h[i] = x[1]; l[i] = x[2]; c[i] = x[3]; v[i] = x[4];
        }
        return new Serie(titolo, d.toArray(new LocalDate[0]), o, h, l, c, v);
    }

    /** Scarica (o legge dalla cache del giorno) i dati di un mercato, indice compreso. */
    @SuppressWarnings("unchecked")
    public static Map<String, Serie> mercato(Mercati.Mercato m, File cartella, boolean forza,
                                             java.util.function.Consumer<String> avanzamento) throws Exception {
        File cache = new File(cartella, "cache_" + m.nome + ".json");
        String oggi = LocalDate.now().toString();
        if (!forza && cache.exists()) {
            try {
                Map<String, Object> j = Json.oggetto(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
                if (oggi.equals(j.get("giorno"))) {
                    Map<String, Serie> out = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : ((Map<String, Object>) j.get("serie")).entrySet())
                        out.put(e.getKey(), Serie.daLista(e.getKey(), (List<Object>) e.getValue()));
                    return out;
                }
            } catch (Exception ignorata) { /* cache rovinata: riscarico */ }
        }
        List<String> tutti = new ArrayList<>(m.titoli);
        tutti.add(m.benchmark);
        Map<String, Serie> out = new LinkedHashMap<>();
        List<String> mancanti = new ArrayList<>();
        int k = 0;
        for (String t : tutti) {
            k++;
            if (avanzamento != null) avanzamento.accept("Scarico " + m.nome + ": " + k + "/" + tutti.size());
            try {
                Serie s = giornalieri(t, "6y");
                if (s.n > 250) out.put(t, s); else mancanti.add(t);
            } catch (Exception e) {
                mancanti.add(t);
            }
            Thread.sleep(250);
        }
        if (out.size() < tutti.size() / 2)
            throw new IllegalStateException("Yahoo non risponde (" + mancanti.size() + " titoli non scaricati)");
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("giorno", oggi);
        Map<String, Object> serie = new LinkedHashMap<>();
        for (Map.Entry<String, Serie> e : out.entrySet()) serie.put(e.getKey(), e.getValue().comeLista());
        j.put("serie", serie);
        j.put("mancanti", new ArrayList<Object>(mancanti));
        Files.write(cache.toPath(), Json.scrivi(j).getBytes(StandardCharsets.UTF_8));
        return out;
    }

    // ------------------------------------------------------------------ tempo reale

    private static long ultimaFinnhub;

    @SuppressWarnings("unchecked")
    public static synchronized Quotazione finnhub(String titolo, String chiave) {
        try {
            long attesa = 1050 - (System.currentTimeMillis() - ultimaFinnhub);     // max 60 al minuto
            if (attesa > 0) Thread.sleep(attesa);
            ultimaFinnhub = System.currentTimeMillis();
            Rete.Risposta r = Rete.get("https://finnhub.io/api/v1/quote?symbol=" + simbolo(titolo)
                    + "&token=" + Rete.codifica(chiave), 15);
            if (r.codice == 429) { Thread.sleep(10000); return null; }
            if (r.codice != 200) return null;
            Map<String, Object> j = Json.oggetto(r.testo());
            double c = Json.num(j.get("c"), 0);
            if (c <= 0) return null;
            double t = Json.num(j.get("t"), 0);
            double rit = t > 0 ? Math.max(0, (System.currentTimeMillis() / 1000.0 - t) / 60) : Double.NaN;
            return new Quotazione(c, rit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Stato del mercato USA da Finnhub (festivi compresi); null se non disponibile. */
    @SuppressWarnings("unchecked")
    public static Boolean mercatoUsaAperto(String chiave) {
        try {
            Rete.Risposta r = Rete.get("https://finnhub.io/api/v1/stock/market-status?exchange=US&token="
                    + Rete.codifica(chiave), 15);
            if (r.codice != 200) return null;
            Object v = Json.oggetto(r.testo()).get("isOpen");
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Prezzi europei a 1 minuto da Yahoo, a gruppi di 20 titoli. */
    @SuppressWarnings("unchecked")
    public static Map<String, Quotazione> yahoo(List<String> titoli) {
        Map<String, Quotazione> out = new LinkedHashMap<>();
        double adesso = System.currentTimeMillis() / 1000.0;
        for (int i = 0; i < titoli.size(); i += 20) {
            List<String> gruppo = titoli.subList(i, Math.min(i + 20, titoli.size()));
            try {
                Rete.Risposta r = Rete.get("https://query1.finance.yahoo.com/v7/finance/spark?symbols="
                        + Rete.codifica(String.join(",", gruppo)) + "&range=1d&interval=1m", 30);
                if (r.codice != 200) continue;
                List<Object> ris = (List<Object>) ((Map<String, Object>) Json.oggetto(r.testo()).get("spark")).get("result");
                for (Object x : ris) {
                    Map<String, Object> m = (Map<String, Object>) x;
                    List<Object> resp = (List<Object>) m.get("response");
                    if (resp == null || resp.isEmpty()) continue;
                    Map<String, Object> meta = (Map<String, Object>) ((Map<String, Object>) resp.get(0)).get("meta");
                    double p = Json.num(meta.get("regularMarketPrice"), 0);
                    double t = Json.num(meta.get("regularMarketTime"), 0);
                    if (p > 0) out.put((String) m.get("symbol"), new Quotazione(p, t > 0 ? Math.max(0, (adesso - t) / 60) : Double.NaN));
                }
            } catch (Exception ignorata) { /* gruppo saltato */ }
        }
        if (out.isEmpty()) {                           // riserva: un titolo alla volta
            for (String t : titoli) {
                try {
                    Rete.Risposta r = Rete.get("https://query2.finance.yahoo.com/v8/finance/chart/" + simbolo(t)
                            + "?range=1d&interval=1m", 20);
                    if (r.codice != 200) continue;
                    Map<String, Object> meta = (Map<String, Object>) ((Map<String, Object>) ((List<Object>)
                            ((Map<String, Object>) Json.oggetto(r.testo()).get("chart")).get("result")).get(0)).get("meta");
                    double p = Json.num(meta.get("regularMarketPrice"), 0);
                    double tt = Json.num(meta.get("regularMarketTime"), 0);
                    if (p > 0) out.put(t, new Quotazione(p, tt > 0 ? Math.max(0, (adesso - tt) / 60) : Double.NaN));
                    Thread.sleep(200);
                } catch (Exception ignorata) { /* titolo saltato */ }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ cambio

    private static volatile double cambio = Double.NaN;
    private static volatile long cambioQuando;

    /** Dollari per 1 euro, aggiornato al massimo ogni 15 minuti (NaN se mai ottenuto). */
    @SuppressWarnings("unchecked")
    public static double cambioEurUsd(File cartella) {
        if (!Double.isNaN(cambio) && System.currentTimeMillis() - cambioQuando < 15 * 60_000) return cambio;
        File f = cartella == null ? null : new File(cartella, "cambio.json");
        try {
            Rete.Risposta r = Rete.get("https://query2.finance.yahoo.com/v8/finance/chart/EURUSD%3DX?range=5d&interval=15m", 20);
            Map<String, Object> meta = (Map<String, Object>) ((Map<String, Object>) ((List<Object>)
                    ((Map<String, Object>) Json.oggetto(r.testo()).get("chart")).get("result")).get(0)).get("meta");
            double v = Json.num(meta.get("regularMarketPrice"), 0);
            if (v > 0.5 && v < 2.5) {
                cambio = v;
                cambioQuando = System.currentTimeMillis();
                if (f != null) {
                    Map<String, Object> j = new LinkedHashMap<>();
                    j.put("valore", v);
                    j.put("quando", cambioQuando / 1000.0);
                    Files.write(f.toPath(), Json.scrivi(j).getBytes(StandardCharsets.UTF_8));
                }
                return v;
            }
        } catch (Exception ignorata) { /* uso l'ultimo noto */ }
        if (Double.isNaN(cambio) && f != null && f.exists()) {
            try {
                cambio = Json.num(Json.oggetto(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).get("valore"), Double.NaN);
            } catch (Exception ignorata) { /* niente */ }
        }
        return cambio;
    }

    public static double ultimoCambio() { return cambio; }
}
