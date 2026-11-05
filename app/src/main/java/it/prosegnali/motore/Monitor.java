package it.prosegnali.motore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Monitoraggio di un mercato durante la seduta: stessa logica e stessi avvisi della versione PC.
 * La mattina calcola i livelli; poi controlla i prezzi circa ogni minuto e avvisa quando un
 * titolo tocca il suo livello o una posizione tocca stop, obiettivo o tempo massimo.
 */
public final class Monitor implements Runnable {

    /** Cosa il monitor comunica all'app. */
    public interface Ascoltatore {
        void stato(String mercato, String testo);
        void log(String testo);
        void avviso(String titolo, String testo, boolean acquisto);
        void livelli(String mercato, Map<String, List<Analisi.Livello>> livelli);
        void classifica(String mercato, List<Analisi.RigaClassifica> classifica, List<String> scelti);
    }

    public final Mercati.Mercato m;
    private final Archivio archivio;
    private final Supplier<Map<String, Object>> imp;
    private final Telegram tg;
    private final String chiaveFinnhub;
    private final File cartella;
    private final Ascoltatore asc;

    public volatile boolean fermo;
    public volatile String stato = "In avvio...";
    public volatile Map<String, Serie> serie = Collections.emptyMap();
    public volatile Map<String, List<Analisi.Livello>> livelli = Collections.emptyMap();
    public final Map<String, Double> ultimiPrezzi = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile LocalDate giorno;
    private Map<String, int[]> statStrategia = new LinkedHashMap<>();
    private List<String> migliori = new ArrayList<>();
    private boolean classificaCalcolata;
    private final Set<String> avvisati = Collections.synchronizedSet(new HashSet<>());
    private long statoUsaQuando;
    private Boolean statoUsa;

    public Monitor(Mercati.Mercato m, Archivio archivio, Supplier<Map<String, Object>> imp, Telegram tg,
                   String chiaveFinnhub, File cartella, Ascoltatore asc) {
        this.m = m; this.archivio = archivio; this.imp = imp; this.tg = tg;
        this.chiaveFinnhub = chiaveFinnhub; this.cartella = cartella; this.asc = asc;
    }

    // ------------------------------------------------------------------ impostazioni

    private int orizzonte() { return (int) Json.num(imp.get().get("orizzonte_classifica"), 30); }

    private boolean soloMigliori() { return Boolean.TRUE.equals(imp.get().get("solo_migliori")); }

    private double importo() {
        Map<String, Object> i = imp.get();
        return Json.num(i.get("capitale"), 3000) / Math.max(1, Json.num(i.get("max_posizioni"), 3));
    }

    @SuppressWarnings("unchecked")
    public List<String> strategie() {
        if (soloMigliori() && classificaCalcolata) return new ArrayList<>(migliori);
        List<String> out = new ArrayList<>();
        Object s = imp.get().get("strategie");
        Object v = s instanceof Map ? ((Map<String, Object>) s).get(m.nome) : null;
        if (v instanceof String) v = Collections.singletonList(v);
        if (v instanceof List) for (Object n : (List<Object>) v)
            if (Strategie.ATTIVABILI.contains(String.valueOf(n))) out.add(String.valueOf(n));
        if (out.isEmpty()) out.add(Strategie.ATTIVABILI.get(0));
        return out;
    }

    public void ricalcola() { giorno = null; }

    private double cambio() { return Prezzi.cambioEurUsd(cartella); }

    private String conEuro(String tk, double v) { return Formato.conEuro(tk, v, cambio()); }

    private void statoA(String t) { stato = t; asc.stato(m.nome, t); }

    private void log(String t) { asc.log("[" + m.nome + "] " + t); }

    // ------------------------------------------------------------------ avvisi del giorno

    private File fileAvvisi() { return new File(cartella, "avvisi_oggi_" + m.nome + ".json"); }

    @SuppressWarnings("unchecked")
    private void caricaAvvisati() {
        avvisati.clear();
        try {
            Map<String, Object> j = Json.oggetto(new String(Files.readAllBytes(fileAvvisi().toPath()), StandardCharsets.UTF_8));
            if (String.valueOf(giorno).equals(j.get("giorno")))
                for (Object x : (List<Object>) j.get("avvisi")) avvisati.add(String.valueOf(x));
        } catch (Exception ignorata) { /* nessun file */ }
    }

    private void segna(String chiave) {
        avvisati.add(chiave);
        try {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("giorno", String.valueOf(giorno));
            j.put("avvisi", new ArrayList<Object>(avvisati));
            Files.write(fileAvvisi().toPath(), Json.scrivi(j).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignorata) { /* non essenziale */ }
    }

    // ------------------------------------------------------------------ preparazione

    private LocalDate oggi() { return ZonedDateTime.now(m.fuso).toLocalDate(); }

    public void preparaGiorno() throws Exception {
        statoA("Scarico i dati e calcolo i livelli del giorno...");
        if ("$".equals(m.valuta)) cambio();
        Map<String, Serie> dati = Prezzi.mercato(m, cartella, false, t -> statoA(t));
        ZonedDateTime adesso = ZonedDateTime.now();
        Map<String, Serie> complete = new LinkedHashMap<>();
        Serie indice = null;
        for (Map.Entry<String, Serie> e : dati.entrySet()) {
            Serie s = Analisi.soloBarreComplete(e.getValue(), m, adesso);
            if (e.getKey().equals(m.benchmark)) indice = s; else complete.put(e.getKey(), s);
        }
        serie = complete;
        String testoClassifica = "";
        classificaCalcolata = false;
        if (soloMigliori()) {
            statoA("Calcolo la classifica dei pattern sugli ultimi 5 anni...");
            List<Analisi.RigaClassifica> cl = Analisi.classifica(complete, orizzonte());
            int quanti = (int) Json.num(imp.get().get("quanti_migliori"), 3);
            migliori = Analisi.migliori(cl, quanti);
            classificaCalcolata = true;
            asc.classifica(m.nome, cl, migliori);
            testoClassifica = testoClassifica(cl, migliori);
            if (migliori.isEmpty())
                testoClassifica += "\n⚠️ Oggi nessun pattern supera la base in modo significativo: nessun "
                        + "segnale d'acquisto, controllo solo le posizioni aperte.";
        }
        List<String> nomi = strategie();
        Map<String, List<Analisi.Livello>> lv = Analisi.livelliOggi(complete, nomi);
        boolean filtro = false;
        if (Boolean.TRUE.equals(imp.get().get("filtro_mercato")) && Boolean.FALSE.equals(Analisi.indiceSopraMedia(indice))) {
            filtro = true;
            lv = new LinkedHashMap<>();
        }
        livelli = lv;
        statoA("Calcolo le statistiche storiche dei pattern...");
        statStrategia = Analisi.statisticheMercato(complete, nomi, orizzonte());
        giorno = oggi();
        caricaAvvisati();
        asc.livelli(m.nome, lv);

        List<Analisi.Livello> tutti = new ArrayList<>();
        for (List<Analisi.Livello> el : lv.values()) tutti.addAll(el);
        StringBuilder t = new StringBuilder();
        t.append(m.bandiera).append(" PRO.SEGNALI ").append(m.nome).append(" (telefono)\n");
        t.append("Pattern attivi").append(soloMigliori() ? " (solo i più performanti)" : "").append(": ")
                .append(nomi.isEmpty() ? "nessuno" : String.join(", ", nomi));
        if (filtro) {
            t.append("\n\n⛔ Filtro di mercato: l'indice ").append(m.nomeBenchmark)
                    .append(" è sotto la sua media a 200 giorni. Oggi nessun nuovo acquisto, controllo solo le posizioni aperte.");
        } else if (!nomi.isEmpty()) {
            t.append("\nSeduta ").append(m.orarioItaliano).append(": ").append(tutti.size())
                    .append(" segnali possibili su ").append(lv.size()).append(" titoli. Ti scrivo appena uno scatta.");
            List<String> apertura = new ArrayList<>();
            List<Analisi.Livello> altri = new ArrayList<>();
            for (Analisi.Livello x : tutti) {
                if (x.tipo == Strategie.Tipo.APERTURA) apertura.add(x.titolo + " (" + x.strategia + ")");
                else altri.add(x);
            }
            if (!apertura.isEmpty()) t.append("\n\nDa comprare all'apertura: ").append(String.join(", ", apertura));
            altri.sort((a, b) -> Double.compare(Math.abs(a.distanzaPct), Math.abs(b.distanzaPct)));
            if (!altri.isEmpty()) t.append("\n\nI più vicini al segnale:");
            for (Analisi.Livello x : altri.subList(0, Math.min(5, altri.size()))) {
                t.append("\n• ").append(Mercati.nome(x.titolo)).append(" (").append(x.titolo).append("): se ")
                        .append(x.tipo == Strategie.Tipo.SOTTO ? "scende sotto " : "sale sopra ")
                        .append(conEuro(x.titolo, x.livello)).append(" (").append(Formato.pct(x.distanzaPct, 1))
                        .append(") - ").append(x.strategia);
            }
        }
        List<Map<String, Object>> pos = archivio.posizioni(m.nome);
        if (!pos.isEmpty()) {
            List<String> tit = new ArrayList<>();
            for (Map<String, Object> p : pos) tit.add(String.valueOf(p.get("titolo")));
            t.append("\n\nPosizioni sotto controllo: ").append(String.join(", ", tit));
        }
        if (!testoClassifica.isEmpty()) t.append("\n\n").append(testoClassifica);
        DayOfWeek g = ZonedDateTime.now(m.fuso).getDayOfWeek();
        if (g != DayOfWeek.SATURDAY && g != DayOfWeek.SUNDAY) tg.invia(t.toString());
        log("Livelli calcolati: " + tutti.size() + " segnali possibili su " + lv.size() + " titoli ("
                + String.join(", ", nomi) + ")" + (filtro ? " - FILTRO MERCATO ATTIVO" : ""));
    }

    static String testoClassifica(List<Analisi.RigaClassifica> cl, List<String> scelti) {
        StringBuilder b = new StringBuilder("🏆 Pattern più affidabili (ultimi 5 anni):\n% di casi con +5% prima dello stop entro ")
                .append(cl.isEmpty() ? 30 : cl.get(0).orizzonte).append(" giorni, contro la base con lo stesso stop");
        for (Analisi.RigaClassifica r : cl) {
            b.append('\n').append(scelti.contains(r.pattern) ? "✅ " : "▫️ ").append(r.pattern).append(": ")
                    .append(Math.round(r.pct)).append("% contro ").append(Math.round(r.base)).append("% (")
                    .append(r.vantaggio >= 0 ? "+" : "").append(Math.round(r.vantaggio)).append(" punti, ")
                    .append(r.casi).append(" casi)");
            if (!r.nota.isEmpty()) b.append("  [").append(r.nota).append(']');
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ valutazioni

    private String notaRitardo(Prezzi.Quotazione q) {
        return !Double.isNaN(q.ritardoMin) && q.ritardoMin > 3 ? "  ⏱ prezzo di " + Math.round(q.ritardoMin) + " min fa" : "";
    }

    private double minutiDaApertura() {
        ZonedDateTime ora = ZonedDateTime.now(m.fuso);
        return (ora.toLocalTime().toSecondOfDay() - m.apertura.toSecondOfDay()) / 60.0;
    }

    void valutaEntrata(String tk, Prezzi.Quotazione q) {
        if (avvisati.contains(tk + "|entrata")) return;
        double c = q.prezzo;
        List<Analisi.Livello> scattati = new ArrayList<>();
        for (Analisi.Livello lv : livelli.getOrDefault(tk, Collections.emptyList())) {
            boolean ok;
            if (lv.tipo == Strategie.Tipo.SOTTO) ok = c <= lv.livello;
            else if (lv.tipo == Strategie.Tipo.SOPRA) ok = c >= lv.livello;
            else ok = minutiDaApertura() <= 30;
            if (ok && Strategie.ingressoValido(lv.strategia, c, lv.obiettivo)) scattati.add(lv);
        }
        if (scattati.isEmpty()) return;
        Analisi.Livello lv = scattati.get(0);
        for (Analisi.Livello x : scattati) if (x.priorita > lv.priorita) lv = x;
        String nome = lv.strategia;
        segna(tk + "|entrata");
        Strategie.Strategia s = Strategie.get(nome);
        double stop = c * (1 - s.stopPct);
        String nomeSoc = Mercati.nome(tk), isin = Mercati.isin(tk);
        StringBuilder r = new StringBuilder();
        r.append(lv.tipo == Strategie.Tipo.APERTURA ? "🟢 COMPRA ORA (all'apertura) " : "🟢 COMPRA ")
                .append(nomeSoc).append("  ").append(m.bandiera).append('\n')
                .append(tk).append(isin.isEmpty() ? "" : "  ·  ISIN " + isin).append('\n')
                .append("📊 Tocca il nome per grafico, dati e notizie\n")
                .append("Prezzo ora: ").append(conEuro(tk, c)).append(notaRitardo(q));
        if (lv.tipo != Strategie.Tipo.APERTURA) r.append("\nLivello: ").append(conEuro(tk, lv.livello));
        r.append("\nStop loss: ").append(conEuro(tk, stop)).append("  (-").append(Math.round(s.stopPct * 100)).append("%)");
        double ob = Strategie.obiettivoEffettivo(nome, c, lv.obiettivo);
        if (!Double.isNaN(ob))
            r.append("\nObiettivo: ").append(conEuro(tk, ob)).append("  (").append(Formato.pct((ob / c - 1) * 100, 1)).append(')');
        if (!Double.isNaN(lv.trailing)) r.append("\nUscita se scende sotto: ").append(conEuro(tk, lv.trailing));
        r.append("\nDurata massima: ").append(s.maxGiorni).append(" giorni");
        r.append("\nImporto suggerito: ~").append(Math.round(importo())).append(" €");
        if (Mercati.eUsa(tk)) {
            double cb = cambio();
            r.append("\nSu Trade Republic il prezzo è in euro: stop e obiettivo valgono in %")
                    .append(Double.isNaN(cb) ? "." : " (cambio 1 € = " + Formato.num(cb, 4) + " $).");
        }
        int n = archivio.posizioni(null).size();
        int mx = (int) Json.num(imp.get().get("max_posizioni"), 3);
        if (n >= mx) r.append("\n⚠️ Hai già ").append(n).append(" posizioni aperte (massimo ").append(mx).append(").");
        r.append("\n\n").append(Analisi.testoStatistica(serie, tk, nome, m.nome, orizzonte(), statStrategia));
        List<String> altri = new ArrayList<>();
        for (Analisi.Livello x : scattati) if (x != lv) altri.add(x.strategia);
        if (!altri.isEmpty()) r.append("\nScattati anche: ").append(String.join(", ", altri));
        int indice = Strategie.NOMI.indexOf(nome);
        Map<String, String> link = new LinkedHashMap<>();
        link.put(nomeSoc, Mercati.scheda(tk));
        tg.invia(r.toString(), Arrays.asList(
                new String[]{"✅ Ho comprato", "c|" + tk + "|" + String.format(java.util.Locale.US, "%.2f", c) + "|" + indice},
                new String[]{"❌ Ignoro", "x|" + tk}), Collections.singletonList(isin), link);
        asc.avviso(tk, "COMPRA " + nomeSoc + " a " + conEuro(tk, c) + " - " + nome, true);
        log("AVVISO: COMPRA " + tk + " a " + Formato.num(c) + " " + m.valuta + " (" + nome + ")");
    }

    void valutaPosizione(Map<String, Object> p, Prezzi.Quotazione q) {
        String tk = String.valueOf(p.get("titolo"));
        double c = q.prezzo;
        double prezzo = Json.num(p.get("prezzo"), Double.NaN);
        Analisi.Riferimenti rif = Analisi.riferimenti(serie, tk, String.valueOf(p.get("strategia")), prezzo,
                String.valueOf(p.get("data")), ZonedDateTime.now(m.fuso).toLocalDate());
        if (rif == null) return;
        double cb = Mercati.eUsa(tk) ? cambio() : 1.0;
        double acqEur = Archivio.prezzoAcquistoEur(p, Double.isNaN(cb) ? null : cb);
        double oraEur = Double.isNaN(cb) ? Double.NaN : c / cb;
        double var = (!Double.isNaN(oraEur) && !Double.isNaN(acqEur)) ? (oraEur / acqEur - 1) * 100 : (c / prezzo - 1) * 100;
        String motivo = null, icona = "", testo = "";
        if (c <= rif.stop) {
            motivo = "stop"; icona = "🔴"; testo = "STOP LOSS toccato: " + conEuro(tk, rif.stop);
        } else if (!Double.isNaN(rif.trailing) && c <= rif.trailing) {
            motivo = "trailing"; icona = "🔴"; testo = "sceso sotto il minimo dei 10 giorni: " + conEuro(tk, rif.trailing);
        } else if (!Double.isNaN(rif.obiettivo) && c >= rif.obiettivo) {
            motivo = "obiettivo"; icona = "💰"; testo = "OBIETTIVO raggiunto: " + conEuro(tk, rif.obiettivo);
        } else {
            LocalTime ora = ZonedDateTime.now(m.fuso).toLocalTime();
            if (rif.giorni >= rif.maxGiorni && !ora.isBefore(m.chiusura.minusMinutes(30))) {
                motivo = "tempo"; icona = "⏰";
                testo = "tempo massimo raggiunto (" + rif.maxGiorni + " giorni): chiudi entro le "
                        + m.orarioItaliano.split("-")[1];
            }
        }
        if (motivo == null || avvisati.contains(tk + "|" + motivo)) return;
        segna(tk + "|" + motivo);
        double guadagno = Json.num(p.get("importo"), 0) * var / 100 - 2 * Json.num(imp.get().get("commissione"), 1);
        String nomeSoc = Mercati.nome(tk), isin = Mercati.isin(tk);
        String msg = icona + " VENDI " + nomeSoc + "  " + m.bandiera + "\n" + tk + (isin.isEmpty() ? "" : "  ·  ISIN " + isin)
                + "\n" + testo + "\nPrezzo ora: " + conEuro(tk, c) + notaRitardo(q)
                + (Double.isNaN(acqEur) ? "" : "\nAcquisto: " + Formato.num(acqEur) + " €")
                + "\nRisultato: " + Formato.pct(var, 2) + "  (~" + Formato.euroSegno(guadagno) + " netti di commissioni)";
        Map<String, String> link = new LinkedHashMap<>();
        link.put(nomeSoc, Mercati.scheda(tk));
        tg.invia(msg, Collections.singletonList(new String[]{"✅ Ho venduto",
                "v|" + tk + "|" + String.format(java.util.Locale.US, "%.2f", c)}), Collections.singletonList(isin), link);
        asc.avviso(tk, "VENDI " + nomeSoc + " (" + motivo + ") " + Formato.pct(var, 2), false);
        log("AVVISO: VENDI " + tk + " (" + motivo + ") a " + Formato.num(c) + " " + m.valuta);
    }

    // ------------------------------------------------------------------ ciclo

    private boolean aperto() {
        ZonedDateTime ora = ZonedDateTime.now(m.fuso);
        DayOfWeek g = ora.getDayOfWeek();
        boolean orario = g != DayOfWeek.SATURDAY && g != DayOfWeek.SUNDAY
                && !ora.toLocalTime().isBefore(m.apertura) && ora.toLocalTime().isBefore(m.chiusura);
        if (!"USA".equals(m.nome) || chiaveFinnhub.isEmpty()) return orario;
        if (System.currentTimeMillis() - statoUsaQuando > 5 * 60_000) {
            Boolean v = Prezzi.mercatoUsaAperto(chiaveFinnhub);
            statoUsa = v;
            statoUsaQuando = System.currentTimeMillis();
        }
        return statoUsa != null ? statoUsa : orario;
    }

    int cicloPrezzi() {
        List<Map<String, Object>> posLista = archivio.posizioni(m.nome);
        Map<String, Map<String, Object>> posizioni = new LinkedHashMap<>();
        for (Map<String, Object> p : posLista) posizioni.put(String.valueOf(p.get("titolo")), p);
        List<String> osservati = new ArrayList<>();
        for (String t : livelli.keySet())
            if (!posizioni.containsKey(t) && !avvisati.contains(t + "|entrata")) osservati.add(t);
        osservati.sort((a, b) -> Double.compare(minDistanza(a), minDistanza(b)));
        List<String> elenco = new ArrayList<>(posizioni.keySet());
        elenco.addAll(osservati);
        long inizio = System.currentTimeMillis();
        int ricevuti = 0;
        List<Double> ritardi = new ArrayList<>();
        Map<String, Prezzi.Quotazione> quot;
        if ("finnhub".equals(m.fonte)) {
            quot = new LinkedHashMap<>();
            for (String t : elenco) {
                if (fermo) return ricevuti;
                Prezzi.Quotazione q = Prezzi.finnhub(t, chiaveFinnhub);
                if (q != null) { quot.put(t, q); gestisci(t, q); ricevuti++; if (!Double.isNaN(q.ritardoMin)) ritardi.add(q.ritardoMin); }
            }
        } else {
            quot = Prezzi.yahoo(elenco);
            for (Map.Entry<String, Prezzi.Quotazione> e : quot.entrySet()) {
                if (fermo) return ricevuti;
                gestisci(e.getKey(), e.getValue());
                ricevuti++;
                if (!Double.isNaN(e.getValue().ritardoMin)) ritardi.add(e.getValue().ritardoMin);
            }
        }
        Collections.sort(ritardi);
        String rit = ritardi.isEmpty() ? "" : ", ritardo prezzi ~" + Math.round(ritardi.get(ritardi.size() / 2)) + " min";
        statoA("Aperto - ultimo giro " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + ": " + ricevuti + "/"
                + elenco.size() + " prezzi in " + (System.currentTimeMillis() - inizio) / 1000 + " s" + rit);
        return ricevuti;
    }

    private double minDistanza(String t) {
        double d = Double.MAX_VALUE;
        for (Analisi.Livello x : livelli.getOrDefault(t, Collections.emptyList())) d = Math.min(d, Math.abs(x.distanzaPct));
        return d;
    }

    private void gestisci(String tk, Prezzi.Quotazione q) {
        ultimiPrezzi.put(tk, q.prezzo);
        try {
            Map<String, Object> p = archivio.posizione(tk);
            if (p != null) valutaPosizione(p, q);
            else if (livelli.containsKey(tk)) valutaEntrata(tk, q);
        } catch (Exception e) {
            log("Errore su " + tk + ": " + e.getMessage());
        }
    }

    private void dormi(long secondi) {
        for (long i = 0; i < secondi && !fermo; i++) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { fermo = true; Thread.currentThread().interrupt(); }
        }
    }

    @Override
    public void run() {
        log("Monitoraggio avviato sul telefono.");
        int pausaEuropa = 120;
        while (!fermo) {
            try {
                if (!oggi().equals(giorno) || serie.isEmpty()) preparaGiorno();
                if (aperto()) {
                    long inizio = System.currentTimeMillis();
                    int n = cicloPrezzi();
                    if (n == 0) {
                        statoA("Aperto, ma nessun prezzo ricevuto. Riprovo tra poco.");
                        if (!"finnhub".equals(m.fonte)) pausaEuropa = Math.min(pausaEuropa * 2, 900);
                        dormi("finnhub".equals(m.fonte) ? 60 : pausaEuropa);
                    } else {
                        pausaEuropa = 120;
                        long trascorsi = (System.currentTimeMillis() - inizio) / 1000;
                        long attesa = ("finnhub".equals(m.fonte) ? 60 : 120) - trascorsi;
                        if (attesa > 0) dormi(attesa);
                    }
                } else {
                    statoA("Chiuso - seduta " + m.orarioItaliano + " (ora italiana, giorni feriali)");
                    dormi(60);
                }
            } catch (Exception e) {
                log("Errore nel monitoraggio: " + e.getMessage() + " - riprovo tra 1 minuto");
                statoA("Errore: " + e.getMessage());
                dormi(60);
            }
        }
        statoA("Spento");
        log("Monitoraggio fermato sul telefono.");
    }
}
