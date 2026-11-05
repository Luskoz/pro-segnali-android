package it.prosegnali.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.prosegnali.motore.Analisi;
import it.prosegnali.motore.Archivio;
import it.prosegnali.motore.Formato;
import it.prosegnali.motore.Json;
import it.prosegnali.motore.Mercati;
import it.prosegnali.motore.Monitor;
import it.prosegnali.motore.Prezzi;
import it.prosegnali.motore.Serie;
import it.prosegnali.motore.Strategie;

public class MainActivity extends Activity {
    private static final String[] SCHEDE = {"Monitoraggio", "Posizioni", "Guadagni", "Classifica", "Impostazioni"};
    private static final DateTimeFormatter IT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Nucleo n;
    private LinearLayout contenuto;
    private TextView intestazione;
    private final List<Button> pulsantiSchede = new ArrayList<>();
    private int scheda = 0;
    private long ultimaVersione = -1;
    private boolean dialogoAperto;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Double> prezziManuali = new LinkedHashMap<>();

    // guadagni
    private String periodo = "Mese";
    private LocalDate riferimento = LocalDate.now();
    private LocalDate dal = LocalDate.now().withDayOfMonth(1), al = LocalDate.now();
    // classifica
    private String mercatoClassifica = "USA";
    private List<Analisi.RigaClassifica> ultimaClassifica;
    private String notaClassifica = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        n = Nucleo.get(this);
        Notifiche.creaCanali(this);

        LinearLayout radice = Ui.colonna(this);
        radice.setBackgroundColor(Ui.SFONDO);

        LinearLayout testa = Ui.riga(this);
        testa.setBackgroundColor(Ui.NOTTE);
        testa.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        testa.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout col = Ui.colonna(this);
        col.setPadding(Ui.dp(this, 10), 0, 0, 0);
        col.addView(Ui.testo(this, "PRO.SEGNALI", 20, true, Color.WHITE));
        intestazione = Ui.testo(this, "", 12, false, Color.parseColor("#C9D6EA"));
        col.addView(intestazione);
        testa.addView(col);
        radice.addView(testa);

        HorizontalScrollView barra = new HorizontalScrollView(this);
        barra.setBackgroundColor(Ui.NOTTE);
        LinearLayout tabs = Ui.riga(this);
        tabs.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 6));
        for (int i = 0; i < SCHEDE.length; i++) {
            final int k = i;
            Button t = new Button(this);
            t.setText(SCHEDE[i]);
            t.setAllCaps(false);
            t.setBackgroundColor(Color.TRANSPARENT);
            t.setOnClickListener(v -> { scheda = k; ricostruisci(); });
            pulsantiSchede.add(t);
            tabs.addView(t);
        }
        barra.addView(tabs);
        radice.addView(barra);

        ScrollView sv = new ScrollView(this);
        contenuto = Ui.colonna(this);
        contenuto.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 40));
        sv.addView(contenuto);
        radice.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(radice);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (n.servizioAttivo()) ServizioPro.avvia(this);
        ricostruisci();
    }

    private final Runnable aggiornaPeriodico = new Runnable() {
        @Override
        public void run() {
            aggiornaIntestazione();
            if (!dialogoAperto && n.versione != ultimaVersione && scheda <= 2) ricostruisci();
            handler.postDelayed(this, 2500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(aggiornaPeriodico);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(aggiornaPeriodico);
        super.onPause();
    }

    private void aggiornaIntestazione() {
        String su = n.monitorSu();
        intestazione.setText(("telefono".equals(su) ? "Monitoraggio su questo telefono" : "pc".equals(su)
                ? "Monitoraggio sul PC" : "Monitoraggio spento") + "  ·  Sincronia: "
                + (n.canale().isEmpty() ? "non collegata" : n.statoSincronia));
    }

    private void ricostruisci() {
        ultimaVersione = n.versione;
        for (int i = 0; i < pulsantiSchede.size(); i++) {
            Button t = pulsantiSchede.get(i);
            t.setTextColor(i == scheda ? Color.WHITE : Color.parseColor("#9FB3D1"));
            t.setTypeface(i == scheda ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        aggiornaIntestazione();
        contenuto.removeAllViews();
        try {
            switch (scheda) {
                case 0: schedaMonitoraggio(); break;
                case 1: schedaPosizioni(); break;
                case 2: schedaGuadagni(); break;
                case 3: schedaClassifica(); break;
                default: schedaImpostazioni();
            }
        } catch (Exception e) {
            contenuto.addView(Ui.testo(this, "Errore: " + e, 14, false, Ui.ROSSO));
        }
    }

    private void inBackground(String descrizione, Runnable lavoro, Runnable poi) {
        Toast.makeText(this, descrizione + "...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String errore = null;
            try { lavoro.run(); } catch (Exception e) { errore = e.getMessage(); }
            final String err = errore;
            runOnUiThread(() -> {
                if (err != null) Toast.makeText(this, "Errore: " + err, Toast.LENGTH_LONG).show();
                if (poi != null) poi.run();
                ricostruisci();
            });
        }).start();
    }

    private void apriScheda(String titolo) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Mercati.scheda(titolo))));
    }

    private AlertDialog.Builder dialogo(String titolo) {
        dialogoAperto = true;
        return new AlertDialog.Builder(this).setTitle(titolo).setOnDismissListener(d -> {
            dialogoAperto = false;
            ricostruisci();
        });
    }

    // ================================================================== MONITORAGGIO

    private void schedaMonitoraggio() {
        String su = n.monitorSu();
        boolean qui = "telefono".equals(su);
        LinearLayout c = Ui.scheda(this);
        c.addView(Ui.testo(this, qui ? "Monitoraggio attivo su: QUESTO TELEFONO 📱" : "pc".equals(su)
                ? "Monitoraggio attivo su: PC 💻" : "Monitoraggio spento su tutti i dispositivi", 16, true,
                su.isEmpty() ? Ui.GRIGIO : Ui.VERDE));
        LinearLayout r = Ui.riga(this);
        if (!qui) r.addView(Ui.pulsante(this, "▶ Monitora qui", Ui.VERDE, v -> spostaMonitor("telefono")));
        if (!su.isEmpty()) r.addView(Ui.pulsante(this, "■ Ferma", Ui.ROSSO, v -> spostaMonitor("")));
        if (!"pc".equals(su)) r.addView(Ui.pulsante(this, "💻 Sposta sul PC", Ui.BLU, v -> spostaMonitor("pc")));
        c.addView(r);
        if (qui) {
            for (String m : n.mercatiAttivi())
                c.addView(Ui.testo(this, Mercati.get(m).bandiera + " " + m + ": " + n.statoMercati.get(m), 13, false, Ui.GRIGIO));
        } else if ("pc".equals(su)) {
            c.addView(Ui.testo(this, "Gli avvisi arrivano dal PC su Telegram. Posizioni e vendite registrate qui "
                    + "vengono sincronizzate con il PC.", 13, false, Ui.GRIGIO));
        }
        contenuto.addView(c);

        contenuto.addView(Ui.titolo(this, "Mercati e pattern"));
        LinearLayout m = Ui.scheda(this);
        Map<String, Object> imp = n.impostazioni();
        boolean top = Boolean.TRUE.equals(imp.get("solo_migliori"));
        for (String mercato : Mercati.NOMI) {
            Mercati.Mercato mm = Mercati.get(mercato);
            CheckBox cb = new CheckBox(this);
            cb.setText(mm.bandiera + " " + mercato + " (" + mm.orarioItaliano + ")");
            cb.setChecked(n.mercatiAttivi().contains(mercato));
            cb.setOnCheckedChangeListener((v, si) -> {
                List<Object> att = new ArrayList<>();
                for (String x : Mercati.NOMI) if (x.equals(mercato) ? si : n.mercatiAttivi().contains(x)) att.add(x);
                n.imposta("mercati_attivi", att);
                inBackground("Aggiorno", n::allineaMonitor, null);
            });
            m.addView(cb);
            List<String> st = n.strategieDi(mercato);
            String et = top ? "Top " + (long) Json.num(imp.get("quanti_migliori"), 3) + " automatici"
                    : (st.size() == 1 ? st.get(0) : st.size() + " pattern attivi");
            Button b = Ui.pulsante(this, "Pattern: " + et + " ▾", top ? Ui.GRIGIO : Ui.NOTTE, v -> scegliPattern(mercato));
            b.setEnabled(!top);
            m.addView(b);
        }
        CheckBox filtro = new CheckBox(this);
        filtro.setText("Filtro mercato (niente acquisti con l'indice sotto la media a 200 gg)");
        filtro.setChecked(Boolean.TRUE.equals(imp.get("filtro_mercato")));
        filtro.setOnCheckedChangeListener((v, si) -> { n.imposta("filtro_mercato", si); n.ricalcolaLivelli(); });
        m.addView(filtro);
        LinearLayout rt = Ui.riga(this);
        CheckBox migliori = new CheckBox(this);
        migliori.setText("Solo pattern altamente performanti: i migliori");
        migliori.setChecked(top);
        migliori.setOnCheckedChangeListener((v, si) -> { n.imposta("solo_migliori", si); n.ricalcolaLivelli(); ricostruisci(); });
        rt.addView(migliori, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Spinner quanti = new Spinner(this);
        quanti.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"3", "4"}));
        quanti.setSelection(Json.num(imp.get("quanti_migliori"), 3) >= 4 ? 1 : 0, false);
        quanti.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                long q = pos == 1 ? 4 : 3;
                if (Json.num(n.impostazioni().get("quanti_migliori"), 3) != q) { n.imposta("quanti_migliori", q); n.ricalcolaLivelli(); }
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        rt.addView(quanti);
        m.addView(rt);
        contenuto.addView(m);

        if (!n.avvisi.isEmpty()) {
            contenuto.addView(Ui.titolo(this, "Avvisi inviati"));
            LinearLayout a = Ui.scheda(this);
            List<String> copia = new ArrayList<>(n.avvisi);
            for (String s : copia.subList(0, Math.min(10, copia.size()))) a.addView(Ui.testo(this, s, 13, false, Ui.TESTO));
            contenuto.addView(a);
        }

        contenuto.addView(Ui.titolo(this, "Titoli sotto osservazione"));
        Map<String, Monitor> att = n.monitorAttivi();
        if (att.isEmpty()) {
            contenuto.addView(Ui.testo(this, qui ? "Preparazione in corso..." :
                    "I livelli si vedono qui quando il monitoraggio è attivo su questo telefono.", 13, false, Ui.GRIGIO));
            return;
        }
        List<Object[]> righe = new ArrayList<>();
        double cambio = Prezzi.ultimoCambio();
        for (Monitor mo : att.values())
            for (List<Analisi.Livello> el : mo.livelli.values())
                for (Analisi.Livello x : el) {
                    Double ora = mo.ultimiPrezzi.get(x.titolo);
                    double manca = ora == null ? x.distanzaPct : (x.livello / ora - 1) * 100;
                    righe.add(new Object[]{Math.abs(manca), x, ora, manca});
                }
        righe.sort((p, q) -> Double.compare((Double) p[0], (Double) q[0]));
        for (Object[] o : righe.subList(0, Math.min(40, righe.size()))) {
            Analisi.Livello x = (Analisi.Livello) o[1];
            LinearLayout s = Ui.scheda(this);
            s.addView(Ui.testo(this, Mercati.nome(x.titolo) + "  (" + x.titolo + ")", 15, true, Ui.NOTTE));
            String cond = x.tipo == Strategie.Tipo.APERTURA ? "da comprare all'apertura" :
                    (x.tipo == Strategie.Tipo.SOTTO ? "se scende sotto " : "se sale sopra ") + Formato.conEuro(x.titolo, x.livello, cambio);
            s.addView(Ui.testo(this, x.strategia + " · " + cond, 13, false, Ui.TESTO));
            if (o[2] != null && x.tipo != Strategie.Tipo.APERTURA) {
                double manca = (Double) o[3];
                s.addView(Ui.testo(this, "Ora " + Formato.conEuro(x.titolo, (Double) o[2], cambio) + " · manca "
                        + Formato.pct(manca, 1), 13, false, Math.abs(manca) < 1 ? Color.parseColor("#B26A00") : Ui.GRIGIO));
            }
            s.setOnClickListener(v -> apriScheda(x.titolo));
            contenuto.addView(s);
        }
    }

    private void spostaMonitor(String dove) {
        n.imposta("monitor_su", dove);
        if (!n.servizioAttivo()) n.setServizioAttivo(true);
        ServizioPro.avvia(this);
        inBackground("Aggiorno", n::allineaMonitor, null);
    }

    @SuppressWarnings("unchecked")
    private void scegliPattern(String mercato) {
        List<String> nomi = Strategie.ATTIVABILI;
        boolean[] scelti = new boolean[nomi.size()];
        List<String> att = n.strategieDi(mercato);
        String[] etichette = new String[nomi.size()];
        for (int i = 0; i < nomi.size(); i++) {
            scelti[i] = att.contains(nomi.get(i));
            etichette[i] = nomi.get(i);
        }
        dialogo("Pattern attivi - " + mercato)
                .setMultiChoiceItems(etichette, scelti, (d, i, si) -> scelti[i] = si)
                .setPositiveButton("Salva", (d, w) -> {
                    List<Object> l = new ArrayList<>();
                    for (int i = 0; i < nomi.size(); i++) if (scelti[i]) l.add(nomi.get(i));
                    if (l.isEmpty()) { Toast.makeText(this, "Seleziona almeno un pattern", Toast.LENGTH_SHORT).show(); return; }
                    Map<String, Object> imp = n.impostazioni();
                    Map<String, Object> st = new LinkedHashMap<>((Map<String, Object>) imp.get("strategie"));
                    st.put(mercato, l);
                    n.imposta("strategie", st);
                    n.ricalcolaLivelli();
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    // ================================================================== POSIZIONI

    private Double prezzoOra(String titolo) {
        for (Monitor mo : n.monitorAttivi().values()) {
            Double p = mo.ultimiPrezzi.get(titolo);
            if (p != null) return p;
        }
        return prezziManuali.get(titolo);
    }

    private Map<String, Serie> serieDi(String titolo) {
        Monitor mo = n.monitorAttivi().get(Mercati.mercatoDi(titolo));
        return mo == null ? null : mo.serie;
    }

    private void schedaPosizioni() {
        LinearLayout r = Ui.riga(this);
        r.addView(Ui.pulsante(this, "+ Aggiungi", Ui.VERDE, v -> dialogoAggiungi()));
        r.addView(Ui.pulsante(this, "↻ Aggiorna prezzi", Ui.BLU, v -> aggiornaPrezzi()));
        contenuto.addView(r);
        List<Map<String, Object>> pos = n.archivio.posizioni(null);
        if (pos.isEmpty()) {
            contenuto.addView(Ui.testo(this, "Nessuna posizione aperta.", 14, false, Ui.GRIGIO));
            return;
        }
        double totale = 0;
        int conPrezzo = 0;
        for (Map<String, Object> p : pos) {
            String tk = String.valueOf(p.get("titolo"));
            Double cambio = n.cambioPer(tk);
            double acq = Archivio.prezzoAcquistoEur(p, cambio);
            Double ora = prezzoOra(tk);
            double oraEur = ora != null && cambio != null ? ora / cambio : Double.NaN;
            double[] ris = Double.isNaN(oraEur) ? null : n.archivio.risultato(p, oraEur, cambio);
            Analisi.Riferimenti rif = Analisi.riferimenti(serieDi(tk), tk, String.valueOf(p.get("strategia")),
                    Json.num(p.get("prezzo"), 0), String.valueOf(p.get("data")), LocalDate.now());
            LinearLayout s = Ui.scheda(this);
            s.addView(Ui.testo(this, Mercati.nome(tk) + "  (" + tk + ")", 16, true, Ui.NOTTE));
            s.addView(Ui.testo(this, "ISIN " + Mercati.isin(tk) + " · " + p.get("strategia"), 12, false, Ui.GRIGIO));
            double q = Json.num(p.get("quantita"), 0);
            double controvalore = q > 0 && !Double.isNaN(acq) ? q * acq : Json.num(p.get("importo"), 0);
            s.addView(Ui.testo(this, "Acquisto " + dataIt(String.valueOf(p.get("data"))) + " a "
                    + (Double.isNaN(acq) ? "n.d." : Formato.num(acq) + " €") + (q > 0 ? " · " + azioni(q) + " azioni" : "")
                    + " · " + Formato.num(controvalore) + " €", 13, false, Ui.TESTO));
            if (ris != null) {
                totale += ris[3];
                conPrezzo++;
                s.addView(Ui.testo(this, "Ora " + Formato.num(oraEur) + " € (" + Formato.pct(ris[4], 2) + ")  →  netto "
                        + Formato.euroSegno(ris[3]), 14, true, ris[3] >= 0 ? Ui.VERDE : Ui.ROSSO));
            }
            if (rif != null && cambio != null) {
                s.addView(Ui.testo(this, "Stop " + Formato.num(rif.stop / cambio) + " €"
                        + (Double.isNaN(rif.obiettivo) ? "" : " · Obiettivo " + Formato.num(rif.obiettivo / cambio) + " €")
                        + " · giorno " + rif.giorni + "/" + rif.maxGiorni, 13, false, Ui.GRIGIO));
            }
            s.setOnClickListener(v -> azioniPosizione(p));
            contenuto.addView(s);
        }
        if (conPrezzo > 0)
            contenuto.addView(Ui.testo(this, "Se vendessi ora: " + Formato.euroSegno(totale) + " netto commissioni",
                    15, true, totale >= 0 ? Ui.VERDE : Ui.ROSSO), 1);
        contenuto.addView(Ui.testo(this, "Tocca una posizione per vendere, modificarla o vedere il grafico. "
                + "Valori in euro; 'netto' = tolte le commissioni di acquisto e vendita, prima delle tasse.", 12, false, Ui.GRIGIO));
    }

    private static String azioni(double q) {
        return q == Math.rint(q) ? String.valueOf((long) q) : Formato.num(q, 4).replaceAll("0+$", "").replaceAll(",$", "");
    }

    private static String dataIt(String iso) {
        try { return LocalDate.parse(iso).format(IT); } catch (Exception e) { return iso; }
    }

    private static LocalDate leggiData(String t) {
        t = t.trim();
        for (String f : new String[]{"dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "yyyy-MM-dd"}) {
            try { return LocalDate.parse(t, DateTimeFormatter.ofPattern(f)); } catch (Exception ignorata) { /* prossimo */ }
        }
        throw new IllegalArgumentException("data non valida (usa GG/MM/AAAA)");
    }

    private void aggiornaPrezzi() {
        List<Map<String, Object>> pos = n.archivio.posizioni(null);
        inBackground("Scarico i prezzi", () -> {
            List<String> europa = new ArrayList<>();
            for (Map<String, Object> p : pos) {
                String tk = String.valueOf(p.get("titolo"));
                if (Mercati.eUsa(tk)) {
                    Prezzi.Quotazione q = n.pref("finnhub_key").isEmpty() ? null : Prezzi.finnhub(tk, n.pref("finnhub_key"));
                    if (q != null) prezziManuali.put(tk, q.prezzo);
                } else europa.add(tk);
            }
            for (Map.Entry<String, Prezzi.Quotazione> e : Prezzi.yahoo(europa).entrySet())
                prezziManuali.put(e.getKey(), e.getValue().prezzo);
            n.cambio();
        }, null);
    }

    private void azioniPosizione(Map<String, Object> p) {
        String tk = String.valueOf(p.get("titolo"));
        dialogo(Mercati.nome(tk) + " (" + tk + ")")
                .setItems(new String[]{"Vendi", "Modifica", "Grafico e dati", "Elimina (errore di inserimento)"}, (d, i) -> {
                    if (i == 0) handler.post(() -> dialogoVendi(p));
                    else if (i == 1) handler.post(() -> dialogoModifica(p));
                    else if (i == 2) apriScheda(tk);
                    else handler.post(() -> dialogo("Elimina " + tk)
                            .setMessage("Eliminare la posizione senza registrare la vendita nello storico?")
                            .setPositiveButton("Elimina", (x, y) -> n.archivio.elimina(tk))
                            .setNegativeButton("Annulla", null).show());
                }).show();
    }

    private void dialogoVendi(Map<String, Object> p) {
        String tk = String.valueOf(p.get("titolo"));
        Double cambio = n.cambioPer(tk);
        double acq = Archivio.prezzoAcquistoEur(p, cambio);
        if (Double.isNaN(acq)) { Toast.makeText(this, "Prezzo d'acquisto in euro mancante: usa Modifica", Toast.LENGTH_LONG).show(); return; }
        Double ora = prezzoOra(tk);
        LinearLayout l = Ui.colonna(this);
        l.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), 0);
        l.addView(Ui.testo(this, "Acquistato il " + dataIt(String.valueOf(p.get("data"))) + " a " + Formato.num(acq) + " €",
                13, false, Ui.GRIGIO));
        EditText prezzo = Ui.campoNumero(this, ora != null && cambio != null ? String.format(java.util.Locale.ITALY, "%.2f", ora / cambio) : "", "es. 16,50");
        EditText data = Ui.campoTesto(this, LocalDate.now().format(IT), "GG/MM/AAAA");
        Ui.etichettato(this, l, "Prezzo di vendita in EURO", prezzo);
        Ui.etichettato(this, l, "Data di vendita", data);
        TextView esito = Ui.testo(this, "", 14, true, Ui.TESTO);
        esito.setPadding(0, Ui.dp(this, 10), 0, 0);
        l.addView(esito);
        Runnable calcola = () -> {
            try {
                double[] r = n.archivio.risultato(p, Ui.numero(prezzo.getText().toString()), cambio);
                esito.setTextColor(r[3] >= 0 ? Ui.VERDE : Ui.ROSSO);
                esito.setText("Variazione " + Formato.pct(r[4], 2) + "\nLordo " + Formato.euroSegno(r[1])
                        + "\nCommissioni -" + Formato.num(r[2]) + " €\nRISULTATO NETTO " + Formato.euroSegno(r[3]));
            } catch (Exception e) { esito.setText(""); }
        };
        prezzo.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { calcola.run(); }
        });
        calcola.run();
        dialogo("Vendi " + Mercati.nome(tk))
                .setView(l)
                .setPositiveButton("Conferma vendita", (d, w) -> {
                    try {
                        double pr = Ui.numero(prezzo.getText().toString());
                        LocalDate dv = leggiData(data.getText().toString());
                        Map<String, Object> rec = n.archivio.chiudi(tk, pr, cambio, dv.toString());
                        if (rec != null) {
                            n.log("Venduto " + tk + " a " + Formato.num(pr) + " €: netto " + Formato.euroSegno(Json.num(rec.get("netto_eur_stimato"), 0)));
                            Toast.makeText(this, "Vendita registrata: " + Formato.euroSegno(Json.num(rec.get("netto_eur_stimato"), 0)), Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Vendita non registrata: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Annulla", null).show();
    }

    private Spinner spinnerPattern(String attuale) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Strategie.ATTIVABILI));
        int i = Strategie.ATTIVABILI.indexOf(attuale);
        if (i >= 0) s.setSelection(i);
        return s;
    }

    private void dialogoModifica(Map<String, Object> p) {
        String tk = String.valueOf(p.get("titolo"));
        Double cambio = n.cambioPer(tk);
        double acq = Archivio.prezzoAcquistoEur(p, cambio);
        double q = Json.num(p.get("quantita"), 0);
        LinearLayout l = Ui.colonna(this);
        l.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), 0);
        l.addView(Ui.testo(this, "ISIN " + Mercati.isin(tk), 13, false, Ui.GRIGIO));
        EditText azioni = Ui.campoNumero(this, q > 0 ? azioni(q) : "", "numero di azioni");
        EditText prezzo = Ui.campoNumero(this, Double.isNaN(acq) ? "" : String.format(java.util.Locale.ITALY, "%.4f", acq), "prezzo in euro");
        EditText data = Ui.campoTesto(this, dataIt(String.valueOf(p.get("data"))), "GG/MM/AAAA");
        Spinner pattern = spinnerPattern(String.valueOf(p.get("strategia")));
        Ui.etichettato(this, l, "Numero di azioni", azioni);
        Ui.etichettato(this, l, "Prezzo d'acquisto in EURO", prezzo);
        Ui.etichettato(this, l, "Data d'acquisto", data);
        Ui.etichettato(this, l, "Pattern", pattern);
        dialogo("Modifica " + Mercati.nome(tk))
                .setView(l)
                .setPositiveButton("Salva", (d, w) -> {
                    try {
                        String ta = azioni.getText().toString().trim();
                        n.archivio.modifica(tk, Ui.numero(prezzo.getText().toString()), ta.isEmpty() ? null : Ui.numero(ta),
                                leggiData(data.getText().toString()).toString(), (String) pattern.getSelectedItem(), cambio);
                    } catch (Exception e) {
                        Toast.makeText(this, "Non salvato: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Annulla", null).show();
    }

    private void dialogoAggiungi() {
        LinearLayout l = Ui.colonna(this);
        l.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), 0);
        EditText titolo = Ui.campo(this, "", "es. AAPL oppure ENI.MI", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText prezzo = Ui.campoNumero(this, "", "prezzo in euro");
        EditText azioni = Ui.campoNumero(this, "", "facoltativo");
        EditText data = Ui.campoTesto(this, LocalDate.now().format(IT), "GG/MM/AAAA");
        Spinner pattern = spinnerPattern(n.strategieDi("USA").get(0));
        Ui.etichettato(this, l, "Titolo", titolo);
        Ui.etichettato(this, l, "Prezzo d'acquisto in EURO", prezzo);
        Ui.etichettato(this, l, "Numero di azioni", azioni);
        Ui.etichettato(this, l, "Data d'acquisto", data);
        Ui.etichettato(this, l, "Pattern", pattern);
        dialogo("Aggiungi posizione")
                .setView(l)
                .setPositiveButton("Salva", (d, w) -> {
                    try {
                        String tk = titolo.getText().toString().trim().toUpperCase();
                        if (tk.isEmpty()) throw new IllegalArgumentException("titolo mancante");
                        double pr = Ui.numero(prezzo.getText().toString());
                        String ta = azioni.getText().toString().trim();
                        Double q = ta.isEmpty() ? null : Ui.numero(ta);
                        Map<String, Object> imp = n.impostazioni();
                        double importo = q != null ? q * pr : Json.num(imp.get("capitale"), 3000) / Math.max(1, Json.num(imp.get("max_posizioni"), 3));
                        n.archivio.aggiungiEur(tk, (String) pattern.getSelectedItem(), pr, importo,
                                leggiData(data.getText().toString()).toString(), n.cambioPer(tk));
                        if (q != null) n.archivio.modifica(tk, null, q, null, null, n.cambioPer(tk));
                    } catch (Exception e) {
                        Toast.makeText(this, "Non salvata: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Annulla", null).show();
    }

    // ================================================================== GUADAGNI

    private LocalDate[] intervallo() {
        LocalDate r = riferimento;
        switch (periodo) {
            case "Settimana": {
                LocalDate d = r.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                return new LocalDate[]{d, d.plusDays(6)};
            }
            case "Mese": return new LocalDate[]{r.withDayOfMonth(1), r.with(TemporalAdjusters.lastDayOfMonth())};
            case "Anno": return new LocalDate[]{r.withDayOfYear(1), r.with(TemporalAdjusters.lastDayOfYear())};
            case "Personalizzato": return dal.isAfter(al) ? new LocalDate[]{al, dal} : new LocalDate[]{dal, al};
            default: return new LocalDate[]{null, null};
        }
    }

    private String descrizionePeriodo(LocalDate[] i) {
        switch (periodo) {
            case "Settimana": return i[0].format(DateTimeFormatter.ofPattern("dd/MM")) + " - " + i[1].format(IT);
            case "Mese": {
                String[] mesi = {"Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno", "Luglio", "Agosto",
                        "Settembre", "Ottobre", "Novembre", "Dicembre"};
                return mesi[riferimento.getMonthValue() - 1] + " " + riferimento.getYear();
            }
            case "Anno": return "Anno " + riferimento.getYear();
            case "Personalizzato": return i[0].format(IT) + " - " + i[1].format(IT);
            default: return "Tutte le operazioni";
        }
    }

    private void schedaGuadagni() {
        List<String> periodi = Arrays.asList("Tutto", "Settimana", "Mese", "Anno", "Personalizzato");
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, periodi));
        sp.setSelection(periodi.indexOf(periodo), false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!periodi.get(pos).equals(periodo)) { periodo = periodi.get(pos); riferimento = LocalDate.now(); ricostruisci(); }
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        contenuto.addView(sp);
        LocalDate[] i = intervallo();
        LinearLayout nav = Ui.riga(this);
        boolean frecce = Arrays.asList("Settimana", "Mese", "Anno").contains(periodo);
        if (frecce) nav.addView(Ui.pulsante(this, "◀", Ui.NOTTE, v -> { sposta(-1); ricostruisci(); }));
        TextView et = Ui.testo(this, descrizionePeriodo(i), 15, true, Ui.NOTTE);
        et.setGravity(Gravity.CENTER);
        nav.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (frecce) nav.addView(Ui.pulsante(this, "▶", Ui.NOTTE, v -> { sposta(1); ricostruisci(); }));
        contenuto.addView(nav);
        if ("Personalizzato".equals(periodo)) {
            LinearLayout r = Ui.riga(this);
            r.addView(Ui.pulsante(this, "Dal " + dal.format(IT), Ui.BLU, v -> sceltaData(true)));
            r.addView(Ui.pulsante(this, "Al " + al.format(IT), Ui.BLU, v -> sceltaData(false)));
            contenuto.addView(r);
        }
        List<Map<String, Object>> ops = new ArrayList<>();
        for (Map<String, Object> r : n.archivio.storico()) {
            try {
                LocalDate d = LocalDate.parse(String.valueOf(r.get("data_vendita")));
                if ((i[0] == null || !d.isBefore(i[0])) && (i[1] == null || !d.isAfter(i[1]))) ops.add(r);
            } catch (Exception ignorata) { /* data mancante */ }
        }
        ops.sort((a, b) -> String.valueOf(b.get("data_vendita")).compareTo(String.valueOf(a.get("data_vendita"))));
        double netto = 0, lordo = 0, comm = 0;
        int vinte = 0;
        for (Map<String, Object> r : ops) {
            double nt = Json.num(r.get("netto_eur_stimato"), 0);
            double cm = Json.num(r.get("commissioni_eur"), 2 * n.archivio.commissione);
            netto += nt;
            comm += cm;
            lordo += Json.num(r.get("guadagno_lordo_eur"), nt + cm);
            if (nt > 0) vinte++;
        }
        LinearLayout t = Ui.scheda(this);
        if (ops.isEmpty()) {
            t.addView(Ui.testo(this, "Nessuna operazione chiusa in questo periodo", 15, true, Ui.GRIGIO));
        } else {
            t.addView(Ui.testo(this, "Risultato netto: " + Formato.euroSegno(netto), 18, true, netto >= 0 ? Ui.VERDE : Ui.ROSSO));
            t.addView(Ui.testo(this, ops.size() + " operazioni: " + vinte + " in guadagno, " + (ops.size() - vinte)
                    + " in perdita (" + Math.round(vinte * 100.0 / ops.size()) + "% vincenti)", 13, false, Ui.TESTO));
            t.addView(Ui.testo(this, "Lordo " + Formato.euroSegno(lordo) + " · commissioni -" + Formato.num(comm)
                    + " € · stima dopo tasse 26%: " + Formato.euroSegno(netto - Math.max(netto, 0) * 0.26), 13, false, Ui.GRIGIO));
        }
        contenuto.addView(t);
        for (Map<String, Object> r : ops) {
            String tk = String.valueOf(r.get("titolo"));
            double nt = Json.num(r.get("netto_eur_stimato"), 0);
            double acq = Json.num(r.get("prezzo_eur"), Json.num(r.get("prezzo"), Double.NaN));
            double ven = Json.num(r.get("prezzo_vendita_eur"), Json.num(r.get("prezzo_vendita"), Double.NaN));
            LinearLayout s = Ui.scheda(this);
            LinearLayout rr = Ui.riga(this);
            TextView nome = Ui.testo(this, Mercati.nome(tk) + " (" + tk + ")", 15, true, Ui.NOTTE);
            rr.addView(nome, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            rr.addView(Ui.testo(this, Formato.euroSegno(nt), 15, true, nt > 0 ? Ui.VERDE : Ui.ROSSO));
            s.addView(rr);
            String q = r.get("quantita") != null ? " · " + azioni(Json.num(r.get("quantita"), 0)) + " azioni" : "";
            s.addView(Ui.testo(this, dataIt(String.valueOf(r.get("data"))) + " → " + dataIt(String.valueOf(r.get("data_vendita")))
                    + q, 13, false, Ui.GRIGIO));
            s.addView(Ui.testo(this, Formato.num(acq) + " € → " + Formato.num(ven) + " € (" + Formato.pct(Json.num(r.get("rend_lordo_pct"), 0), 2)
                    + ") · " + Json.str(r.get("strategia"), ""), 13, false, Ui.TESTO));
            s.setOnLongClickListener(v -> {
                dialogo("Elimina operazione").setMessage("Eliminare dallo storico l'operazione su " + tk + "?\n"
                        + "Usalo solo per correggere un errore di inserimento.")
                        .setPositiveButton("Elimina", (d, w) -> n.archivio.eliminaOperazione(String.valueOf(r.get("id"))))
                        .setNegativeButton("Annulla", null).show();
                return true;
            });
            contenuto.addView(s);
        }
        contenuto.addView(Ui.testo(this, "Filtrate per data di vendita. Netto = tolte le commissioni, prima delle tasse. "
                + "Tieni premuto su un'operazione per eliminarla.", 12, false, Ui.GRIGIO));
    }

    private void sposta(int verso) {
        if ("Settimana".equals(periodo)) riferimento = riferimento.plusWeeks(verso);
        else if ("Mese".equals(periodo)) riferimento = riferimento.plusMonths(verso);
        else if ("Anno".equals(periodo)) riferimento = riferimento.plusYears(verso);
    }

    private void sceltaData(boolean inizio) {
        LocalDate d = inizio ? dal : al;
        dialogoAperto = true;
        DatePickerDialog dp = new DatePickerDialog(this, (v, y, m, g) -> {
            LocalDate nuova = LocalDate.of(y, m + 1, g);
            if (inizio) dal = nuova; else al = nuova;
        }, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
        dp.setOnDismissListener(x -> { dialogoAperto = false; ricostruisci(); });
        dp.show();
    }

    // ================================================================== CLASSIFICA

    private void schedaClassifica() {
        Spinner sm = new Spinner(this);
        sm.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Mercati.NOMI));
        sm.setSelection(Mercati.NOMI.indexOf(mercatoClassifica), false);
        sm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!Mercati.NOMI.get(pos).equals(mercatoClassifica)) {
                    mercatoClassifica = Mercati.NOMI.get(pos); ultimaClassifica = null; ricostruisci();
                }
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        contenuto.addView(sm);
        List<String> or = Arrays.asList("10", "20", "30");
        Spinner so = new Spinner(this);
        so.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"+5% entro 10 giorni", "+5% entro 20 giorni", "+5% entro 30 giorni"}));
        int oriz = (int) Json.num(n.impostazioni().get("orizzonte_classifica"), 30);
        so.setSelection(Math.max(0, or.indexOf(String.valueOf(oriz))), false);
        so.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                long val = Long.parseLong(or.get(pos));
                if (Json.num(n.impostazioni().get("orizzonte_classifica"), 30) != val) {
                    n.imposta("orizzonte_classifica", val);
                    n.ricalcolaLivelli();
                    ultimaClassifica = null;
                    ricostruisci();
                }
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        contenuto.addView(so);
        contenuto.addView(Ui.pulsante(this, "Calcola classifica", Ui.NOTTE, v -> calcolaClassifica()));
        contenuto.addView(Ui.testo(this, "Per ogni pattern, negli ultimi 5 anni: quante volte il titolo è salito di almeno +5% "
                + "PRIMA di toccare lo stop, contro la base (giorno qualsiasi con lo stesso stop). Verde = almeno 3 punti "
                + "sopra la base con almeno 30 casi.", 12, false, Ui.GRIGIO));
        List<Analisi.RigaClassifica> cl = ultimaClassifica;
        List<String> scelti = new ArrayList<>();
        if (cl == null && n.classifiche.get(mercatoClassifica) != null) {
            cl = n.classifiche.get(mercatoClassifica);
            scelti = n.sceltiTop.get(mercatoClassifica);
            notaClassifica = "Classifica di oggi del monitoraggio";
        }
        if (cl == null) return;
        if (scelti == null || scelti.isEmpty()) scelti = Analisi.migliori(cl, (int) Json.num(n.impostazioni().get("quanti_migliori"), 3));
        contenuto.addView(Ui.titolo(this, mercatoClassifica + " · " + notaClassifica));
        for (Analisi.RigaClassifica r : cl) {
            LinearLayout s = Ui.scheda(this);
            int colore = scelti.contains(r.pattern) ? Ui.VERDE : r.idoneo ? Ui.NOTTE : Ui.ROSSO;
            s.addView(Ui.testo(this, (scelti.contains(r.pattern) ? "✅ " : "") + r.pattern, 15, true, colore));
            s.addView(Ui.testo(this, "+5% entro 10/20/30 gg: " + Formato.num(r.pct10, 1) + "% / " + Formato.num(r.pct20, 1)
                    + "% / " + Formato.num(r.pct30, 1) + "%", 13, false, Ui.TESTO));
            s.addView(Ui.testo(this, "Base " + Formato.num(r.base, 1) + "% · vantaggio " + (r.vantaggio >= 0 ? "+" : "")
                    + Formato.num(r.vantaggio, 1) + " punti · " + r.casi + " casi · stop -" + Math.round(r.stopPct * 100)
                    + "% · durata max " + Strategie.get(r.pattern).maxGiorni + " gg", 12, false, Ui.GRIGIO));
            if (!r.nota.isEmpty()) s.addView(Ui.testo(this, r.nota, 12, false, Ui.ROSSO));
            contenuto.addView(s);
        }
    }

    private void calcolaClassifica() {
        String mercato = mercatoClassifica;
        int oriz = (int) Json.num(n.impostazioni().get("orizzonte_classifica"), 30);
        inBackground("Calcolo la classifica (alla prima volta scarica i dati: 1-2 minuti)", () -> {
            try {
                Mercati.Mercato m = Mercati.get(mercato);
                Map<String, Serie> dati = Prezzi.mercato(m, n.cartella, false, null);
                Map<String, Serie> complete = new LinkedHashMap<>();
                for (Map.Entry<String, Serie> e : dati.entrySet())
                    if (!e.getKey().equals(m.benchmark)) complete.put(e.getKey(), Analisi.soloBarreComplete(e.getValue(), m, ZonedDateTime.now()));
                ultimaClassifica = Analisi.classifica(complete, oriz);
                notaClassifica = "calcolata ora";
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, null);
    }

    // ================================================================== IMPOSTAZIONI

    private void schedaImpostazioni() {
        contenuto.addView(Ui.titolo(this, "Collegamenti (solo su questo telefono)"));
        LinearLayout c = Ui.scheda(this);
        EditText token = Ui.campo(this, n.pref("telegram_token"), "token del bot", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText chat = Ui.campoTesto(this, n.pref("telegram_chat"), "il tuo chat ID");
        EditText finnhub = Ui.campo(this, n.pref("finnhub_key"), "chiave Finnhub", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText canale = Ui.campoTesto(this, n.canale(), "es. -1001234567890");
        Ui.etichettato(this, c, "Token del bot Telegram (lo stesso del PC)", token);
        Ui.etichettato(this, c, "Chat ID Telegram", chat);
        Ui.etichettato(this, c, "Chiave API Finnhub", finnhub);
        Ui.etichettato(this, c, "ID del canale di sincronia (te lo dà il PC con /collega)", canale);
        c.addView(Ui.pulsante(this, "Salva collegamenti", Ui.VERDE, v -> {
            n.setPref("telegram_token", token.getText().toString());
            n.setPref("telegram_chat", chat.getText().toString());
            n.setPref("finnhub_key", finnhub.getText().toString());
            n.setPref("canale_sync", canale.getText().toString());
            Toast.makeText(this, "Salvato", Toast.LENGTH_SHORT).show();
            ServizioPro.avvia(this);
            ricostruisci();
        }));
        contenuto.addView(c);

        contenuto.addView(Ui.titolo(this, "Sincronia con il PC"));
        LinearLayout s = Ui.scheda(this);
        s.addView(Ui.testo(this, "Stato: " + (n.canale().isEmpty() ? "non collegata" : n.statoSincronia), 14, false, Ui.TESTO));
        s.addView(Ui.pulsante(this, "Sincronizza ora", Ui.BLU, v -> inBackground("Sincronizzo", () -> {
            try { n.sincronizza(); n.allineaMonitor(); } catch (Exception e) { throw new RuntimeException(e.getMessage(), e); }
        }, null)));
        contenuto.addView(s);

        contenuto.addView(Ui.titolo(this, "Parametri (condivisi con il PC)"));
        LinearLayout p = Ui.scheda(this);
        Map<String, Object> imp = n.impostazioni();
        EditText capitale = Ui.campoNumero(this, Formato.num(Json.num(imp.get("capitale"), 3000), 0).replace(".", ""), "");
        EditText maxPos = Ui.campoNumero(this, String.valueOf((long) Json.num(imp.get("max_posizioni"), 3)), "");
        EditText comm = Ui.campoNumero(this, Formato.num(Json.num(imp.get("commissione"), 1), 2), "");
        Ui.etichettato(this, p, "Capitale €", capitale);
        Ui.etichettato(this, p, "Posizioni massime", maxPos);
        Ui.etichettato(this, p, "Commissione € per ordine", comm);
        p.addView(Ui.pulsante(this, "Salva parametri", Ui.VERDE, v -> {
            try {
                Map<String, Object> m = n.impostazioni();
                m.put("capitale", Ui.numero(capitale.getText().toString()));
                m.put("max_posizioni", (long) Ui.numero(maxPos.getText().toString()));
                m.put("commissione", Ui.numero(comm.getText().toString()));
                n.salvaImpostazioni(m);
                Toast.makeText(this, "Salvato", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Valori non validi", Toast.LENGTH_LONG).show();
            }
        }));
        contenuto.addView(p);

        contenuto.addView(Ui.titolo(this, "Funzionamento in background"));
        LinearLayout b = Ui.scheda(this);
        CheckBox serv = new CheckBox(this);
        serv.setText("Servizio attivo (sincronia e monitoraggio anche ad app chiusa)");
        serv.setChecked(n.servizioAttivo());
        serv.setOnCheckedChangeListener((v, si) -> {
            n.setServizioAttivo(si);
            if (si) ServizioPro.avvia(this); else ServizioPro.ferma(this);
        });
        b.addView(serv);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean esente = pm.isIgnoringBatteryOptimizations(getPackageName());
        b.addView(Ui.testo(this, esente ? "✅ Escluso dal risparmio energetico" :
                "⚠️ Il risparmio energetico può fermare il monitoraggio a schermo spento.", 13, false, esente ? Ui.VERDE : Ui.ROSSO));
        if (!esente) b.addView(Ui.pulsante(this, "Escludi dal risparmio energetico", Ui.NOTTE, v -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }));
        b.addView(Ui.testo(this, "Su Xiaomi, Huawei e Samsung controlla anche: Impostazioni > App > PRO.SEGNALI > Batteria > "
                + "Senza restrizioni / Avvio automatico.", 12, false, Ui.GRIGIO));
        contenuto.addView(b);

        contenuto.addView(Ui.titolo(this, "Registro"));
        LinearLayout r = Ui.scheda(this);
        List<String> reg = new ArrayList<>(n.registro);
        for (int i = reg.size() - 1; i >= Math.max(0, reg.size() - 40); i--) r.addView(Ui.testo(this, reg.get(i), 11, false, Ui.GRIGIO));
        if (reg.isEmpty()) r.addView(Ui.testo(this, "(vuoto)", 12, false, Ui.GRIGIO));
        contenuto.addView(r);
        contenuto.addView(Ui.testo(this, "Versione " + versione(), 11, false, Ui.GRIGIO));
    }

    private String versione() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }
}
