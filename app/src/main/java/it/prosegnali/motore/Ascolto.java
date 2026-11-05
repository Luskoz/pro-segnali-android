package it.prosegnali.motore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Comandi e pulsanti Telegram quando e' il telefono a monitorare (come Ascolto della versione PC). */
public final class Ascolto implements Runnable {

    public interface Azioni {
        /** "pc", "telefono" oppure "" (fermo ovunque). */
        void monitorSu(String dove);
        void collegaCanale(String id);
        void modificato();
        Map<String, Monitor> monitorAttivi();
    }

    static final String AIUTO = "Comandi disponibili:\n"
            + "/stato - cosa sta facendo il programma\n"
            + "/posizioni - le tue posizioni aperte\n"
            + "/livelli - i titoli più vicini al segnale\n"
            + "/comprato TITOLO PREZZO [IMPORTO] - registra o corregge un acquisto\n"
            + "   es. /comprato AAPL 295,40 1000   oppure   /comprato ENI.MI 15,42\n"
            + "/venduto TITOLO PREZZO - registra una vendita\n"
            + "/storico - ultime operazioni chiuse\n"
            + "/monitora pc  oppure  /monitora telefono - dove fare il monitoraggio\n"
            + "/ferma - ferma il monitoraggio su tutti i dispositivi\n\n"
            + "Prezzi e importi sempre in EURO, come su Trade Republic (anche per i titoli USA). "
            + "I titoli europei hanno il suffisso di borsa: .MI, .DE, .PA...";

    private final Archivio archivio;
    private final Supplier<Map<String, Object>> imp;
    private final Telegram tg;
    private final Azioni azioni;
    private final java.io.File cartella;
    public volatile boolean fermo;
    private Long offset;

    public Ascolto(Archivio archivio, Supplier<Map<String, Object>> imp, Telegram tg, Azioni azioni,
                   java.io.File cartella) {
        this.archivio = archivio; this.imp = imp; this.tg = tg; this.azioni = azioni; this.cartella = cartella;
    }

    @Override
    public void run() {
        List<Map<String, Object>> vecchi = tg.aggiornamenti(-1L, 0);   // salto i messaggi arretrati
        if (!vecchi.isEmpty()) offset = ((Number) vecchi.get(vecchi.size() - 1).get("update_id")).longValue() + 1;
        while (!fermo) {
            for (Map<String, Object> agg : tg.aggiornamenti(offset, 25)) {
                offset = ((Number) agg.get("update_id")).longValue() + 1;
                try { gestisci(agg); } catch (Exception e) { System.err.println("Comando Telegram: " + e); }
            }
        }
    }

    private double importo() {
        Map<String, Object> i = imp.get();
        return Json.num(i.get("capitale"), 3000) / Math.max(1, Json.num(i.get("max_posizioni"), 3));
    }

    @SuppressWarnings("unchecked")
    private String strategia(String titolo, Integer indice) {
        if (indice != null && indice >= 0 && indice < Strategie.NOMI.size()) return Strategie.NOMI.get(indice);
        Object s = imp.get().get("strategie");
        Object v = s instanceof Map ? ((Map<String, Object>) s).get(Mercati.mercatoDi(titolo)) : null;
        if (v instanceof List && !((List<Object>) v).isEmpty()) return String.valueOf(((List<Object>) v).get(0));
        if (v instanceof String) return (String) v;
        return Strategie.ATTIVABILI.get(0);
    }

    private Double cambio(String titolo) {
        if (!Mercati.eUsa(titolo)) return 1.0;
        double c = Prezzi.cambioEurUsd(cartella);
        return Double.isNaN(c) ? null : c;
    }

    private boolean dalMioChat(Object chatId) { return String.valueOf(chatId).equals(tg.chat); }

    private static double numero(String t) { return Double.parseDouble(t.replace(",", ".")); }

    @SuppressWarnings("unchecked")
    void gestisci(Map<String, Object> agg) throws Exception {
        Map<String, Object> post = (Map<String, Object>) agg.get("channel_post");
        if (post != null) {
            String t = Json.str(post.get("text"), "").trim().toLowerCase();
            if (t.startsWith("/collega")) {
                Object id = ((Map<String, Object>) post.get("chat")).get("id");
                azioni.collegaCanale(String.valueOf(id));
                new Telegram(tg.token, String.valueOf(id)).invia("✅ Canale collegato al telefono.\nID del canale: " + id);
            }
            return;
        }
        Map<String, Object> cq = (Map<String, Object>) agg.get("callback_query");
        if (cq != null) {
            Map<String, Object> msg = (Map<String, Object>) cq.get("message");
            if (msg == null || !dalMioChat(((Map<String, Object>) msg.get("chat")).get("id"))) return;
            String[] parti = Json.str(cq.get("data"), "").split("\\|");
            tg.togliPulsanti(msg.get("message_id"));
            String id = String.valueOf(cq.get("id"));
            if ("c".equals(parti[0]) && (parti.length == 3 || parti.length == 4)) {
                Integer indice = parti.length == 4 ? Integer.valueOf(parti[3]) : null;
                try {
                    Map<String, Object> p = (Map<String, Object>) archivio.aggiungi(parti[1], strategia(parti[1], indice),
                            Double.parseDouble(parti[2]), importo(), null, cambio(parti[1]))[0];
                    tg.rispondiPulsante(id, "Acquisto registrato");
                    tg.invia("✅ Registrato: " + Mercati.nome(parti[1]) + " (" + parti[1] + ") a "
                            + Formato.num(Json.num(p.get("prezzo_eur"), 0)) + " €, ~" + Math.round(Json.num(p.get("importo"), 0))
                            + " €.\nTi avviso io per stop e obiettivo.\nSe su Trade Republic hai pagato un prezzo diverso, scrivi: /comprato "
                            + parti[1] + " PREZZO_IN_EURO");
                } catch (IllegalStateException e) {
                    tg.rispondiPulsante(id, "Non registrato");
                    tg.invia("⚠️ Non ho potuto registrare l'acquisto (" + e.getMessage() + "). Riprova con: /comprato "
                            + parti[1] + " PREZZO_IN_EURO");
                }
            } else if ("v".equals(parti[0]) && parti.length == 3) {
                Double c = cambio(parti[1]);
                vendita(parti[1], c == null ? Double.NaN : Double.parseDouble(parti[2]) / c);
                tg.rispondiPulsante(id, "Vendita registrata");
            } else {
                tg.rispondiPulsante(id, "Ok, ignorato");
            }
            azioni.modificato();
            return;
        }
        Map<String, Object> msg = (Map<String, Object>) agg.get("message");
        if (msg == null || !dalMioChat(((Map<String, Object>) msg.get("chat")).get("id"))) return;
        String testo = Json.str(msg.get("text"), "").trim();
        if (testo.isEmpty()) return;
        String[] p = testo.split("\\s+");
        String cmd = p[0].toLowerCase().split("@")[0];
        if (cmd.equals("/start") || cmd.equals("/aiuto") || cmd.equals("/help")) {
            tg.invia(AIUTO);
        } else if (cmd.equals("/stato")) {
            stato();
        } else if (cmd.equals("/posizioni")) {
            posizioni();
        } else if (cmd.equals("/livelli")) {
            livelli();
        } else if (cmd.equals("/storico")) {
            storico();
        } else if (cmd.equals("/monitora") && p.length > 1 && (p[1].equalsIgnoreCase("pc") || p[1].equalsIgnoreCase("telefono"))) {
            String dove = p[1].toLowerCase();
            azioni.monitorSu(dove);
            tg.invia("👍 Monitoraggio spostato su: " + dove.toUpperCase() + ". "
                    + (dove.equals("telefono") ? "Parte subito." : "Parte quando il PC si sincronizza (entro un minuto circa)."));
        } else if (cmd.equals("/ferma")) {
            azioni.monitorSu("");
            tg.invia("⏸ Monitoraggio fermato su tutti i dispositivi.");
        } else if (cmd.equals("/comprato") && p.length >= 3) {
            double prezzo; Double imp2 = null;
            try { prezzo = numero(p[2]); if (p.length > 3) imp2 = numero(p[3]); }
            catch (NumberFormatException e) { tg.invia("Formato: /comprato AAPL 295,40 1000  (prezzo e importo in euro)"); return; }
            String titolo = p[1].toUpperCase();
            if (imp2 == null && archivio.posizione(titolo) == null) imp2 = importo();
            try {
                Object[] r = archivio.aggiungiEur(titolo, strategia(titolo, null), prezzo, imp2, null, cambio(titolo));
                Map<String, Object> pos = (Map<String, Object>) r[0];
                tg.invia(((Boolean) r[1] ? "✅ Registrato " : "✏️ Corretto ") + Mercati.nome(titolo) + " (" + titolo + ") a "
                        + Formato.num(prezzo) + " €, ~" + Math.round(Json.num(pos.get("importo"), 0)) + " €.");
            } catch (IllegalStateException e) {
                tg.invia("⚠️ Non registrato: " + e.getMessage() + ". Riprova tra qualche minuto.");
            }
            azioni.modificato();
        } else if (cmd.equals("/venduto") && p.length >= 3) {
            try { vendita(p[1], numero(p[2])); }
            catch (NumberFormatException e) { tg.invia("Formato: /venduto AAPL 305,10  (prezzo in euro)"); }
            azioni.modificato();
        } else {
            tg.invia("Non ho capito. " + AIUTO);
        }
    }

    private void vendita(String titolo, double prezzoEur) {
        try {
            Map<String, Object> rec = archivio.chiudi(titolo, prezzoEur, cambio(titolo), LocalDate.now().toString());
            if (rec == null) { tg.invia("Non trovo " + titolo.toUpperCase() + " tra le posizioni aperte."); return; }
            tg.invia("✅ Vendita registrata: " + Mercati.nome(titolo.toUpperCase()) + " a "
                    + Formato.num(Json.num(rec.get("prezzo_vendita_eur"), 0)) + " € - "
                    + Formato.pct(Json.num(rec.get("rend_lordo_pct"), 0), 2) + " (~"
                    + Formato.euroSegno(Json.num(rec.get("netto_eur_stimato"), 0)) + " netti di commissioni)");
        } catch (IllegalStateException e) {
            tg.invia("⚠️ Vendita non registrata: " + e.getMessage() + ". Riprova tra qualche minuto.");
        }
    }

    private void stato() {
        Map<String, Monitor> att = azioni.monitorAttivi();
        if (att.isEmpty()) { tg.invia("⚪ Monitoraggio spento sul telefono."); return; }
        List<String> r = new ArrayList<>();
        for (Monitor mo : att.values())
            r.add(mo.m.bandiera + " " + mo.m.nome + " (" + String.join(", ", mo.strategie()) + "): " + mo.stato);
        tg.invia("📱 Telefono\n" + String.join("\n", r));
    }

    private void posizioni() {
        List<Map<String, Object>> pos = archivio.posizioni(null);
        if (pos.isEmpty()) { tg.invia("Nessuna posizione aperta."); return; }
        Map<String, Monitor> att = azioni.monitorAttivi();
        StringBuilder b = new StringBuilder("Posizioni aperte:");
        for (Map<String, Object> p : pos) {
            String tk = String.valueOf(p.get("titolo"));
            Double c = cambio(tk);
            double acq = Archivio.prezzoAcquistoEur(p, c);
            b.append("\n• ").append(Mercati.nome(tk)).append(" (").append(tk).append(") acq. ")
                    .append(Double.isNaN(acq) ? "n.d." : Formato.num(acq)).append(" €");
            Monitor mo = att.get(String.valueOf(p.get("mercato")));
            Double ora = mo == null ? null : mo.ultimiPrezzi.get(tk);
            if (ora != null && c != null && !Double.isNaN(acq))
                b.append(" | ora ").append(Formato.num(ora / c)).append(" € (").append(Formato.pct((ora / c / acq - 1) * 100, 2)).append(')');
            Analisi.Riferimenti rif = Analisi.riferimenti(mo == null ? null : mo.serie, tk, String.valueOf(p.get("strategia")),
                    Json.num(p.get("prezzo"), 0), String.valueOf(p.get("data")), LocalDate.now());
            if (rif != null && c != null) {
                b.append(" | stop ").append(Formato.num(rif.stop / c)).append(" €");
                if (!Double.isNaN(rif.obiettivo)) b.append(" | obiettivo ").append(Formato.num(rif.obiettivo / c)).append(" €");
                b.append(" | giorno ").append(rif.giorni).append('/').append(rif.maxGiorni);
            }
        }
        tg.invia(b.toString());
    }

    private void livelli() {
        List<Object[]> tutti = new ArrayList<>();
        for (Monitor mo : azioni.monitorAttivi().values())
            for (List<Analisi.Livello> el : mo.livelli.values())
                for (Analisi.Livello x : el) {
                    Double ora = mo.ultimiPrezzi.get(x.titolo);
                    double d = ora != null ? Math.abs(x.livello / ora - 1) : Math.abs(x.distanzaPct) / 100;
                    tutti.add(new Object[]{d, x, ora});
                }
        if (tutti.isEmpty()) { tg.invia("Livelli non ancora calcolati: avvia il monitoraggio."); return; }
        tutti.sort((a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        StringBuilder b = new StringBuilder("Titoli più vicini al segnale:");
        double cb = Prezzi.cambioEurUsd(cartella);
        for (Object[] o : tutti.subList(0, Math.min(12, tutti.size()))) {
            Analisi.Livello x = (Analisi.Livello) o[1];
            if (x.tipo == Strategie.Tipo.APERTURA) {
                b.append("\n• ").append(x.titolo).append(" da comprare all'apertura - ").append(x.strategia);
                continue;
            }
            b.append("\n• ").append(Mercati.nome(x.titolo)).append(" (").append(x.titolo).append(") ")
                    .append(x.tipo == Strategie.Tipo.SOTTO ? "↓ sotto " : "↑ sopra ")
                    .append(Formato.conEuro(x.titolo, x.livello, cb));
            if (o[2] != null) b.append(" - manca ").append(Formato.pct((x.livello / (Double) o[2] - 1) * 100, 1));
            b.append(" - ").append(x.strategia);
        }
        tg.invia(b.toString());
    }

    private void storico() {
        List<Map<String, Object>> st = archivio.storico();
        if (st.isEmpty()) { tg.invia("Nessuna operazione chiusa."); return; }
        double tot = 0;
        for (Map<String, Object> r : st) tot += Json.num(r.get("netto_eur_stimato"), 0);
        StringBuilder b = new StringBuilder("Ultime operazioni:");
        List<Map<String, Object>> ultime = new ArrayList<>(st.subList(Math.max(0, st.size() - 10), st.size()));
        for (Map<String, Object> r : ultime)
            b.append("\n• ").append(r.get("titolo")).append(' ').append(r.get("data_vendita")).append(": ")
                    .append(Formato.pct(Json.num(r.get("rend_lordo_pct"), 0), 2)).append(" (")
                    .append(Formato.euroSegno(Json.num(r.get("netto_eur_stimato"), 0))).append(')');
        b.append("\n\nTotale stimato: ").append(Formato.euroSegno(tot)).append(" (prima delle tasse)");
        tg.invia(b.toString());
    }
}
