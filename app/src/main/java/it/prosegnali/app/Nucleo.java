package it.prosegnali.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.prosegnali.motore.Analisi;
import it.prosegnali.motore.Archivio;
import it.prosegnali.motore.ArchivioVirtuale;
import it.prosegnali.motore.Ascolto;
import it.prosegnali.motore.Json;
import it.prosegnali.motore.Mercati;
import it.prosegnali.motore.Monitor;
import it.prosegnali.motore.Prezzi;
import it.prosegnali.motore.Sincronia;
import it.prosegnali.motore.Strategie;
import it.prosegnali.motore.Telegram;

/**
 * Cuore dell'app, condiviso da schermate e servizio in background: archivio, impostazioni,
 * sincronia con il PC, monitoraggio e stato da mostrare.
 */
public final class Nucleo {
    private static Nucleo istanza;

    public static synchronized Nucleo get(Context c) {
        if (istanza == null) istanza = new Nucleo(c.getApplicationContext());
        return istanza;
    }

    public final Context ctx;
    public final File cartella;
    public final Archivio archivio;
    public final ArchivioVirtuale virtuali;
    public final Sincronia sincronia;
    private final SharedPreferences prefs;

    public final Map<String, Monitor> monitor = new LinkedHashMap<>();
    private final Map<String, Thread> threadMonitor = new LinkedHashMap<>();
    private Ascolto ascolto;
    private Thread threadAscolto;

    public volatile String statoSincronia = "non collegata";
    public final Map<String, String> statoMercati = Collections.synchronizedMap(new LinkedHashMap<>());
    public final List<String> avvisi = Collections.synchronizedList(new ArrayList<>());
    public final List<String> registro = Collections.synchronizedList(new ArrayList<>());
    public final Map<String, List<Analisi.RigaClassifica>> classifiche = Collections.synchronizedMap(new LinkedHashMap<>());
    public final Map<String, List<String>> sceltiTop = Collections.synchronizedMap(new LinkedHashMap<>());
    /** Incrementato a ogni cambiamento: le schermate si aggiornano quando cambia. */
    public volatile long versione;

    private Nucleo(Context ctx) {
        this.ctx = ctx;
        this.cartella = ctx.getFilesDir();
        this.prefs = ctx.getSharedPreferences("prosegnali", Context.MODE_PRIVATE);
        this.archivio = new Archivio(cartella);
        this.virtuali = new ArchivioVirtuale(archivio);
        this.sincronia = new Sincronia(archivio, "telefono");
        this.archivio.commissione = Json.num(impostazioni().get("commissione"), 1.0);
        this.archivio.dopoModifica = this::modificato;
        for (String m : Mercati.NOMI) statoMercati.put(m, "Spento");
    }

    // ------------------------------------------------------------------ impostazioni del telefono

    public String pref(String k) { return prefs.getString(k, ""); }

    public void setPref(String k, String v) { prefs.edit().putString(k, v == null ? "" : v.trim()).apply(); }

    public boolean servizioAttivo() { return prefs.getBoolean("servizio_attivo", true); }

    public void setServizioAttivo(boolean v) { prefs.edit().putBoolean("servizio_attivo", v).apply(); }

    public Telegram telegram() { return new Telegram(pref("telegram_token"), pref("telegram_chat")); }

    public String canale() { return pref("canale_sync"); }

    // ------------------------------------------------------------------ impostazioni condivise

    public static Map<String, Object> predefinite() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capitale", 3000L);
        m.put("max_posizioni", 3L);
        m.put("commissione", 1.0);
        m.put("spread", 0.1);
        Map<String, Object> s = new LinkedHashMap<>();
        for (String mercato : Mercati.NOMI) s.put(mercato, new ArrayList<Object>(Collections.singletonList("RSI2 rimbalzo")));
        m.put("strategie", s);
        m.put("mercati_attivi", new ArrayList<Object>(Mercati.NOMI));
        m.put("filtro_mercato", false);
        m.put("solo_migliori", false);
        m.put("quanti_migliori", 3L);
        m.put("orizzonte_classifica", 30L);
        m.put("punteggio_minimo", 60L);
        m.put("monitor_su", "");
        return m;
    }

    /** Impostazioni condivise con il PC (impostazioni.json), con i valori predefiniti mancanti. */
    public synchronized Map<String, Object> impostazioni() {
        Map<String, Object> m = predefinite();
        File f = new File(cartella, Sincronia.FILE_IMPOSTAZIONI);
        if (f.exists()) {
            try { m.putAll(Json.oggetto(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))); }
            catch (Exception ignorata) { /* file rovinato: valori predefiniti */ }
        }
        return m;
    }

    public synchronized void salvaImpostazioni(Map<String, Object> m) {
        archivio.scriviFile(Sincronia.FILE_IMPOSTAZIONI, m, true);
        archivio.commissione = Json.num(m.get("commissione"), 1.0);
    }

    public synchronized void imposta(String chiave, Object valore) {
        Map<String, Object> m = impostazioni();
        m.put(chiave, valore);
        salvaImpostazioni(m);
    }

    @SuppressWarnings("unchecked")
    public List<String> strategieDi(String mercato) {
        List<String> out = new ArrayList<>();
        Object s = impostazioni().get("strategie");
        Object v = s instanceof Map ? ((Map<String, Object>) s).get(mercato) : null;
        if (v instanceof String) v = Collections.singletonList(v);
        if (v instanceof List) for (Object n : (List<Object>) v)
            if (Strategie.ATTIVABILI.contains(String.valueOf(n))) out.add(String.valueOf(n));
        if (out.isEmpty()) out.add(Strategie.ATTIVABILI.get(0));
        return out;
    }

    public String monitorSu() { return Json.str(impostazioni().get("monitor_su"), ""); }

    @SuppressWarnings("unchecked")
    public List<String> mercatiAttivi() {
        List<String> out = new ArrayList<>();
        Object v = impostazioni().get("mercati_attivi");
        if (v instanceof List) for (Object x : (List<Object>) v) if (Mercati.NOMI.contains(String.valueOf(x))) out.add(String.valueOf(x));
        return out;
    }

    public double cambio() { return Prezzi.cambioEurUsd(cartella); }

    /** Cambio da usare per un titolo (1 per l'Europa, null se non disponibile). */
    public Double cambioPer(String titolo) {
        if (!Mercati.eUsa(titolo)) return 1.0;
        double c = Prezzi.ultimoCambio();
        if (Double.isNaN(c)) c = cambio();
        return Double.isNaN(c) ? null : c;
    }

    // ------------------------------------------------------------------ eventi

    public volatile Runnable dopoModificaLocale;

    void modificato() {
        versione++;
        Runnable r = dopoModificaLocale;
        if (r != null) r.run();
    }

    public void log(String testo) {
        String riga = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")) + "  " + testo;
        registro.add(riga);
        while (registro.size() > 300) registro.remove(0);
        versione++;
    }

    // ------------------------------------------------------------------ monitoraggio

    public synchronized boolean monitoraggioAttivo() {
        for (Thread t : threadMonitor.values()) if (t.isAlive()) return true;
        return false;
    }

    public synchronized Map<String, Monitor> monitorAttivi() {
        Map<String, Monitor> out = new LinkedHashMap<>();
        for (Map.Entry<String, Monitor> e : monitor.entrySet()) {
            Thread t = threadMonitor.get(e.getKey());
            if (t != null && t.isAlive()) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private final Monitor.Ascoltatore ascoltatore = new Monitor.Ascoltatore() {
        public void stato(String mercato, String testo) { statoMercati.put(mercato, testo); versione++; }
        public void log(String testo) { Nucleo.this.log(testo); }
        public void avviso(String titolo, String testo, boolean acquisto) {
            avvisi.add(0, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + "  " + testo);
            while (avvisi.size() > 100) avvisi.remove(avvisi.size() - 1);
            Notifiche.avviso(ctx, titolo, testo, acquisto);
            versione++;
        }
        public void livelli(String mercato, Map<String, List<Analisi.Livello>> l) { versione++; }
        public void classifica(String mercato, List<Analisi.RigaClassifica> c, List<String> scelti) {
            classifiche.put(mercato, c);
            sceltiTop.put(mercato, scelti);
            versione++;
        }
    };

    /** Avvia o ferma i monitor del telefono secondo la scelta condivisa (monitor_su). */
    public synchronized void allineaMonitor() {
        boolean qui = "telefono".equals(monitorSu());
        List<String> attivi = mercatiAttivi();
        for (String nome : Mercati.NOMI) {
            Thread t = threadMonitor.get(nome);
            boolean vivo = t != null && t.isAlive();
            boolean serve = qui && attivi.contains(nome);
            if (serve && !vivo) {
                Monitor mo = new Monitor(Mercati.get(nome), archivio, this::impostazioni, telegram(),
                        pref("finnhub_key"), cartella, ascoltatore);
                monitor.put(nome, mo);
                Thread nt = new Thread(mo, "monitor-" + nome);
                threadMonitor.put(nome, nt);
                nt.start();
            } else if (!serve && vivo) {
                monitor.get(nome).fermo = true;
                t.interrupt();
                statoMercati.put(nome, "Spento");
            }
        }
        boolean ascoltoVivo = threadAscolto != null && threadAscolto.isAlive();
        if (qui && !ascoltoVivo && telegram().configurato()) {
            ascolto = new Ascolto(archivio, this::impostazioni, telegram(), azioni, cartella);
            threadAscolto = new Thread(ascolto, "ascolto-telegram");
            threadAscolto.start();
        } else if (!qui && ascoltoVivo) {
            ascolto.fermo = true;
            threadAscolto.interrupt();
        }
        versione++;
    }

    public synchronized void fermaTutto() {
        for (Monitor mo : monitor.values()) mo.fermo = true;
        for (Thread t : threadMonitor.values()) t.interrupt();
        if (ascolto != null) ascolto.fermo = true;
        if (threadAscolto != null) threadAscolto.interrupt();
        for (String m : Mercati.NOMI) statoMercati.put(m, "Spento");
        versione++;
    }

    /** Ricalcola i livelli (pattern o filtri cambiati). */
    public synchronized void ricalcolaLivelli() {
        for (Monitor mo : monitor.values()) mo.ricalcola();
    }

    private final Ascolto.Azioni azioni = new Ascolto.Azioni() {
        public void monitorSu(String dove) { imposta("monitor_su", dove); allineaMonitor(); }
        public void collegaCanale(String id) { setPref("canale_sync", id); log("Canale di sincronia collegato: " + id); }
        public void modificato() { Nucleo.this.modificato(); }
        public Map<String, Monitor> monitorAttivi() { return Nucleo.this.monitorAttivi(); }
    };

    // ------------------------------------------------------------------ sincronia

    /** Un giro di sincronia (da chiamare in un thread di lavoro). */
    private final Object lockSincronia = new Object();

    public Sincronia.Esito sincronizza() throws Exception {
        synchronized (lockSincronia) { return sincronizzaInterno(); }
    }

    private Sincronia.Esito sincronizzaInterno() throws Exception {
        if (canale().isEmpty() || pref("telegram_token").isEmpty())
            throw new IllegalStateException("inserisci token del bot e ID del canale nelle impostazioni");
        Map<String, Object> primaImp = impostazioni();
        Sincronia.Esito e = sincronia.giro(Sincronia.canaleTelegram(telegram(), canale()));
        statoSincronia = "sincronizzata alle " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                + (e.pubblicato ? " (inviati aggiornamenti)" : "");
        if (!e.cambiati.isEmpty()) {
            log("Dati aggiornati dal PC: " + e.cambiati);
            Map<String, Object> dopo = impostazioni();
            archivio.commissione = Json.num(dopo.get("commissione"), 1.0);
            for (String k : new String[]{"strategie", "filtro_mercato", "solo_migliori", "quanti_migliori", "orizzonte_classifica"}) {
                if (!Json.canonico(primaImp.get(k)).equals(Json.canonico(dopo.get(k)))) { ricalcolaLivelli(); break; }
            }
        }
        versione++;
        return e;
    }
}
