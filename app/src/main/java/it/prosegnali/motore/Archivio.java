package it.prosegnali.motore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posizioni aperte e operazioni chiuse: stessi file e stessi campi della versione PC
 * (posizioni.json, storico_operazioni.json), cosi' la sincronia li scambia senza conversioni.
 * prezzo = valuta della borsa ($ per USA), prezzo_eur = euro, cambio = dollari per 1 euro.
 */
public final class Archivio {
    public static final String FILE_POS = "posizioni.json";
    public static final String FILE_STORICO = "storico_operazioni.json";

    public final File cartella;
    public final Object lock = new Object();
    public volatile double commissione = 1.0;
    /** Chiamato dopo ogni modifica (per avviare la sincronia). */
    public volatile Runnable dopoModifica;

    public Archivio(File cartella) { this.cartella = cartella; }

    // ------------------------------------------------------------------ file

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> leggiLista(String nome) {
        synchronized (lock) {
            File f = new File(cartella, nome);
            if (!f.exists()) return new ArrayList<>();
            try {
                Object v = Json.leggi(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object x : (List<Object>) v) out.add((Map<String, Object>) x);
                return out;
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }
    }

    public void scriviFile(String nome, Object dati, boolean avvisa) {
        synchronized (lock) {
            try {
                File tmp = new File(cartella, nome + ".tmp");
                Files.write(tmp.toPath(), Json.scrivi(dati).getBytes(StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), new File(cartella, nome).toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new RuntimeException("Scrittura " + nome + " non riuscita: " + e.getMessage(), e);
            }
        }
        Runnable r = dopoModifica;
        if (avvisa && r != null) r.run();
    }

    private void scrivi(String nome, Object dati) { scriviFile(nome, dati, true); }

    // ------------------------------------------------------------------ utilita'

    static double arrotonda(double x, int cifre) {
        double f = Math.pow(10, cifre);
        return Math.round(x * f) / f;
    }

    static double cambioValido(String titolo, Double cambio) {
        if ("Europa".equals(Mercati.mercatoDi(titolo))) return 1.0;
        if (cambio == null || cambio <= 0 || cambio.isNaN())
            throw new IllegalStateException("cambio EUR/USD non disponibile");
        return cambio;
    }

    /** Prezzo d'acquisto in euro (USA: dollari / cambio del giorno d'acquisto). NaN se ignoto. */
    public static double prezzoAcquistoEur(Map<String, Object> p, Double cambioOra) {
        double pe = Json.num(p.get("prezzo_eur"), 0);
        if (pe > 0) return pe;
        if ("Europa".equals(Mercati.mercatoDi((String) p.get("titolo")))) return Json.num(p.get("prezzo"), Double.NaN);
        double c = Json.num(p.get("cambio"), 0);
        if (c <= 0 && cambioOra != null) c = cambioOra;
        return c > 0 ? Json.num(p.get("prezzo"), Double.NaN) / c : Double.NaN;
    }

    // ------------------------------------------------------------------ posizioni

    public List<Map<String, Object>> posizioni(String mercato) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : leggiLista(FILE_POS)) {
            p.putIfAbsent("mercato", Mercati.mercatoDi((String) p.get("titolo")));
            if (mercato == null || mercato.equals(p.get("mercato"))) out.add(p);
        }
        return out;
    }

    public Map<String, Object> posizione(String titolo) {
        for (Map<String, Object> p : posizioni(null)) if (titolo.equals(p.get("titolo"))) return p;
        return null;
    }

    /** Aggiunge (prezzo nella valuta della borsa, importo in euro) o corregge se gia' presente.
     *  Restituisce [posizione, true se nuova]. */
    public Object[] aggiungi(String titolo, String strategia, double prezzo, Double importo,
                             String dataAcq, Double cambio) {
        titolo = titolo.toUpperCase().trim();
        double c = cambioValido(titolo, cambio);
        synchronized (lock) {
            List<Map<String, Object>> pos = leggiLista(FILE_POS);
            for (Map<String, Object> p : pos) {
                if (!titolo.equals(p.get("titolo"))) continue;
                p.put("prezzo", prezzo);
                p.put("cambio", c);
                p.put("prezzo_eur", arrotonda(prezzo / c, 4));
                if (importo != null) p.put("importo", importo);
                double q = Json.num(p.get("quantita"), 0);
                if (q > 0) p.put("importo", arrotonda(q * Json.num(p.get("prezzo_eur"), 0), 2));
                scrivi(FILE_POS, pos);
                return new Object[]{p, false};
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("titolo", titolo);
            p.put("mercato", Mercati.mercatoDi(titolo));
            p.put("strategia", strategia);
            p.put("data", dataAcq != null ? dataAcq : LocalDate.now().toString());
            p.put("prezzo", prezzo);
            p.put("prezzo_eur", arrotonda(prezzo / c, 4));
            p.put("cambio", c);
            p.put("importo", importo == null ? 0.0 : importo);
            pos.add(p);
            scrivi(FILE_POS, pos);
            return new Object[]{p, true};
        }
    }

    public Object[] aggiungiEur(String titolo, String strategia, double prezzoEur, Double importo,
                                String dataAcq, Double cambio) {
        double c = cambioValido(titolo, cambio);
        return aggiungi(titolo, strategia, prezzoEur * c, importo, dataAcq, c);
    }

    /** Risultato se venduta a prezzoVenditaEur: [azioni, lordo, commissioni, netto, var%, acquistoEur]. */
    public double[] risultato(Map<String, Object> p, double prezzoVenditaEur, Double cambio) {
        double acq = prezzoAcquistoEur(p, cambio);
        if (Double.isNaN(acq) || acq <= 0) return null;
        double q = Json.num(p.get("quantita"), 0);
        double azioni = q > 0 ? q : Json.num(p.get("importo"), 0) / acq;
        double lordo = azioni * (prezzoVenditaEur - acq);
        double comm = 2 * commissione;
        return new double[]{azioni, lordo, comm, lordo - comm, (prezzoVenditaEur / acq - 1) * 100, acq};
    }

    /** Chiude una posizione (prezzo di vendita in euro) e la registra nello storico. */
    public Map<String, Object> chiudi(String titolo, double prezzoVenditaEur, Double cambio, String dataVendita) {
        titolo = titolo.toUpperCase().trim();
        double c = cambioValido(titolo, cambio);
        synchronized (lock) {
            List<Map<String, Object>> pos = leggiLista(FILE_POS);
            for (int i = 0; i < pos.size(); i++) {
                Map<String, Object> p = pos.get(i);
                if (!titolo.equals(p.get("titolo"))) continue;
                double[] r = risultato(p, prezzoVenditaEur, c);
                if (r == null) throw new IllegalStateException("prezzo d'acquisto in euro mancante");
                pos.remove(i);
                Map<String, Object> rec = new LinkedHashMap<>(p);
                rec.put("prezzo_eur", arrotonda(r[5], 4));
                rec.put("quantita", arrotonda(r[0], 6));
                rec.put("data_vendita", dataVendita != null ? dataVendita : LocalDate.now().toString());
                rec.put("prezzo_vendita", arrotonda(prezzoVenditaEur * c, 4));
                rec.put("prezzo_vendita_eur", arrotonda(prezzoVenditaEur, 4));
                rec.put("cambio_vendita", c);
                rec.put("rend_lordo_pct", arrotonda(r[4], 2));
                rec.put("guadagno_lordo_eur", arrotonda(r[1], 2));
                rec.put("commissioni_eur", arrotonda(r[2], 2));
                rec.put("netto_eur_stimato", arrotonda(r[3], 2));
                rec.put("id", titolo + "-" + LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + "-tel");
                List<Map<String, Object>> st = leggiLista(FILE_STORICO);
                st.add(rec);
                scriviFile(FILE_STORICO, st, false);
                scrivi(FILE_POS, pos);
                return rec;
            }
            return null;
        }
    }

    /** Correzione manuale con il prezzo in euro; controvalore = azioni x prezzo. */
    public Map<String, Object> modifica(String titolo, Double prezzoEur, Double quantita, String dataAcq,
                                        String strategia, Double cambio) {
        titolo = titolo.toUpperCase().trim();
        synchronized (lock) {
            List<Map<String, Object>> pos = leggiLista(FILE_POS);
            for (Map<String, Object> p : pos) {
                if (!titolo.equals(p.get("titolo"))) continue;
                if (prezzoEur != null) {
                    double c = Json.num(p.get("cambio"), 0);
                    if (c <= 0) c = cambioValido(titolo, cambio);
                    p.put("prezzo_eur", prezzoEur);
                    p.put("cambio", c);
                    p.put("prezzo", arrotonda(prezzoEur * c, 4));
                }
                if (dataAcq != null) p.put("data", dataAcq);
                if (strategia != null) p.put("strategia", strategia);
                if (quantita != null) p.put("quantita", quantita);
                double q = Json.num(p.get("quantita"), 0);
                if (q > 0) {
                    double pe = prezzoAcquistoEur(p, cambio);
                    if (!Double.isNaN(pe)) p.put("importo", arrotonda(q * pe, 2));
                }
                scrivi(FILE_POS, pos);
                return p;
            }
            return null;
        }
    }

    public void elimina(String titolo) {
        synchronized (lock) {
            List<Map<String, Object>> pos = leggiLista(FILE_POS);
            pos.removeIf(p -> titolo.equalsIgnoreCase((String) p.get("titolo")));
            scrivi(FILE_POS, pos);
        }
    }

    // ------------------------------------------------------------------ storico

    public List<Map<String, Object>> storico() {
        List<Map<String, Object>> st = leggiLista(FILE_STORICO);
        for (int i = 0; i < st.size(); i++) st.get(i).putIfAbsent("id", "vecchia-" + i);
        return st;
    }

    public boolean eliminaOperazione(String id) {
        synchronized (lock) {
            List<Map<String, Object>> st = storico();
            int prima = st.size();
            st.removeIf(r -> id.equals(r.get("id")));
            scrivi(FILE_STORICO, st);
            return st.size() < prima;
        }
    }
}
