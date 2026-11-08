package it.prosegnali.motore;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Posizioni e operazioni virtuali, in file propri: stessa logica della versione PC. */
public class ArchivioVirtuale extends Archivio {
    public static final String FILE_POS = "virtuali.json";
    public static final String FILE_STORICO = "virtuali_storico.json";
    public static final double IMPORTO = 1000.0;
    public static final int GRADINO_INIZIO = 6;
    public static final int GRADINO_DISTANZA = 2;
    public static final int GIORNI = 30;

    public ArchivioVirtuale(Archivio base) {
        super(base.cartella, FILE_POS, FILE_STORICO, base);
    }

    // ------------------------------------------------------------------ regole

    public static int gradino(double prezzoAcquisto, double prezzo) {
        return (int) Math.floor((prezzo / prezzoAcquisto - 1) * 100 + 1e-9);
    }

    public static double stop(double prezzoAcquisto, double stopPct, int gradino) {
        if (gradino >= GRADINO_INIZIO) return prezzoAcquisto * (1 + (gradino - GRADINO_DISTANZA) / 100.0);
        return prezzoAcquisto * (1 - stopPct);
    }

    /** {nuovo gradino o null, motivo di vendita o null}. */
    public static Object[] valuta(Map<String, Object> p, double prezzo, int giorni, boolean ultimaMezzora) {
        int prima = (int) Json.num(p.get("gradino"), 0);
        double acq = Json.num(p.get("prezzo"), Double.NaN);
        double stopPct = Json.num(p.get("stop_pct"), 0.08);
        int g = Math.max(prima, gradino(acq, prezzo));
        Integer nuovo = g != prima ? g : null;
        if (prezzo <= stop(acq, stopPct, g)) {
            if (g >= GRADINO_INIZIO)
                return new Object[]{nuovo, String.format(Locale.ROOT, "stop a %+d%% (massimo %+d%%)", g - GRADINO_DISTANZA, g)};
            return new Object[]{nuovo, String.format(Locale.ROOT, "stop loss -%.0f%%", stopPct * 100)};
        }
        if (giorni >= GIORNI && ultimaMezzora) return new Object[]{nuovo, "tempo massimo (" + GIORNI + " giorni)"};
        return new Object[]{nuovo, null};
    }

    // ------------------------------------------------------------------ archivio

    /** Apre una posizione virtuale; null se ce n'e' gia' una su quel titolo. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> apri(String titolo, String strategia, double prezzo, Double cambio) {
        titolo = titolo.toUpperCase().trim();
        synchronized (lock) {
            if (posizione(titolo) != null) return null;
            Map<String, Object> p = (Map<String, Object>) aggiungi(titolo, strategia, prezzo, IMPORTO, null, cambio)[0];
            Strategie.Strategia s = Strategie.get(strategia);
            double stopPct = s != null ? s.stopPct : 0.08;
            LocalTime ora = LocalTime.now();
            return modificaVirtuale(titolo, new Object[][]{
                    {"quantita", arrotonda(IMPORTO / Json.num(p.get("prezzo_eur"), Double.NaN), 6)},
                    {"stop_pct", stopPct}, {"gradino", 0L},
                    {"ora", String.format(Locale.ROOT, "%02d:%02d", ora.getHour(), ora.getMinute())}});
        }
    }

    /** Aggiorna campi della posizione e ricalcola controvalore e stop. */
    public Map<String, Object> modificaVirtuale(String titolo, Object[][] campi) {
        synchronized (lock) {
            List<Map<String, Object>> pos = leggiLista(filePos);
            for (Map<String, Object> p : pos) {
                if (!titolo.equals(p.get("titolo"))) continue;
                if (campi != null) for (Object[] kv : campi) p.put((String) kv[0], kv[1]);
                double q = Json.num(p.get("quantita"), 0);
                if (q > 0) p.put("importo", arrotonda(q * Json.num(p.get("prezzo_eur"), 0), 2));
                p.put("stop", arrotonda(stop(Json.num(p.get("prezzo"), Double.NaN), Json.num(p.get("stop_pct"), 0.08),
                        (int) Json.num(p.get("gradino"), 0)), 4));
                scrivi(filePos, pos);
                return p;
            }
            return null;
        }
    }

    @Override
    public Map<String, Object> modifica(String titolo, Double prezzoEur, Double quantita, String dataAcq,
                                        String strategia, Double cambio) {
        Map<String, Object> p = super.modifica(titolo, prezzoEur, quantita, dataAcq, strategia, cambio);
        return p == null ? null : modificaVirtuale(titolo.toUpperCase().trim(), null);
    }

    /** Vende la posizione virtuale al prezzo (valuta della borsa) e annota il motivo. */
    public Map<String, Object> chiudiVirtuale(String titolo, double prezzo, double cambio, String motivo) {
        synchronized (lock) {
            Map<String, Object> rec = chiudi(titolo, prezzo / cambio, cambio, null);
            if (rec == null) return null;
            List<Map<String, Object>> st = leggiLista(fileStorico);
            for (Map<String, Object> r : st)
                if (String.valueOf(rec.get("id")).equals(r.get("id"))) { r.put("motivo", motivo); rec = r; }
            scrivi(fileStorico, st);
            return rec;
        }
    }
}
