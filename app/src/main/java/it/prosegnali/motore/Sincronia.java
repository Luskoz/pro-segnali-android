package it.prosegnali.motore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sincronia con il PC tramite il canale Telegram privato: stesso formato e stesso algoritmo di
 * sincronia.py (stato fissato nel canale, unione record per record con data e ora, cancellazioni
 * ricordate, impostazioni per chiave). I due programmi si scambiano lo stesso file.
 */
public final class Sincronia {
    public static final String FILE_BASE = "sincronia_base.json";
    public static final String FILE_IMPOSTAZIONI = "impostazioni.json";
    static final long FORMATO = 1;

    private final Archivio archivio;
    private final String dispositivo;
    private Long ultimoFissato;
    private final List<Long> documenti = new ArrayList<>();

    /** Dove sta lo stato condiviso (in produzione: il canale Telegram). */
    public interface Canale {
        /** [message_id (Long), file_id (String)] dello stato fissato, oppure null. */
        Object[] fissato() throws Exception;
        Map<String, Object> scarica(String fileId) throws Exception;
        long pubblica(Map<String, Object> stato, List<Long> vecchi) throws Exception;
    }

    public static Canale canaleTelegram(final Telegram tg, final String canale) {
        return new Canale() {
            public Object[] fissato() throws Exception { return tg.fissato(canale); }
            public Map<String, Object> scarica(String fileId) throws Exception { return tg.scaricaStato(fileId); }
            public long pubblica(Map<String, Object> stato, List<Long> vecchi) throws Exception {
                return tg.pubblicaStato(canale, stato, vecchi);
            }
        };
    }

    public Sincronia(Archivio archivio, String dispositivo) {
        this.archivio = archivio;
        this.dispositivo = dispositivo;
    }

    // ------------------------------------------------------------------ logica pura

    @SuppressWarnings("unchecked")
    static Map<String, Object> sez(Map<String, Object> st, String nome) {
        Object v = st.get(nome);
        if (!(v instanceof Map)) { v = new LinkedHashMap<String, Object>(); st.put(nome, v); }
        return (Map<String, Object>) v;
    }

    public static Map<String, Object> statoVuoto() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("formato", FORMATO);
        s.put("posizioni", new LinkedHashMap<String, Object>());
        s.put("storico", new LinkedHashMap<String, Object>());
        for (String[] f : SEZIONI_FILE) s.put(f[0], new LinkedHashMap<String, Object>());
        s.put("impostazioni", new LinkedHashMap<String, Object>());
        Map<String, Object> c = new LinkedHashMap<>();
        for (String k : SEZIONI) c.put(k, new LinkedHashMap<String, Object>());
        s.put("cancellati", c);
        return s;
    }

    public static final String[] SEZIONI = {"posizioni", "storico", "segnali", "v_posizioni", "v_storico"};
    /** Sezioni salvate in file propri: {sezione, file, campo chiave}. */
    public static final String[][] SEZIONI_FILE = {
            {"segnali", Archivio.FILE_SEGNALI, "id"},
            {"v_posizioni", ArchivioVirtuale.FILE_POS, "titolo"},
            {"v_storico", ArchivioVirtuale.FILE_STORICO, "id"}};

    static String impronta(Object dati) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(Json.canonico(dati).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static double ts(Object record) {
        return Json.num(((Map<?, ?>) record).get("_ts"), 0);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> unisci(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> out = statoVuoto();
        for (String sezione : SEZIONI) {
            Map<String, Object> canc = new LinkedHashMap<>();
            for (Map<String, Object> st : new Map[]{a, b}) {
                Map<String, Object> c = sez(sez(st, "cancellati"), sezione);
                for (Map.Entry<String, Object> e : c.entrySet())
                    canc.put(e.getKey(), Math.max(Json.num(canc.get(e.getKey()), 0), Json.num(e.getValue(), 0)));
            }
            Map<String, Object> record = new LinkedHashMap<>();
            for (Map<String, Object> st : new Map[]{a, b}) {
                for (Map.Entry<String, Object> e : sez(st, sezione).entrySet()) {
                    Object att = record.get(e.getKey());
                    if (att == null || ts(e.getValue()) > ts(att)) record.put(e.getKey(), e.getValue());
                }
            }
            for (Map.Entry<String, Object> e : record.entrySet())
                if (ts(e.getValue()) > Json.num(canc.get(e.getKey()), 0)) sez(out, sezione).put(e.getKey(), e.getValue());
            sez(out, "cancellati").put(sezione, canc);
        }
        for (Map<String, Object> st : new Map[]{a, b}) {
            for (Map.Entry<String, Object> e : sez(st, "impostazioni").entrySet()) {
                Object att = sez(out, "impostazioni").get(e.getKey());
                if (att == null || ts(e.getValue()) > ts(att)) sez(out, "impostazioni").put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    static String contenuto(Map<String, Object> st) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : SEZIONI) m.put(k, st.get(k) instanceof Map ? st.get(k) : new LinkedHashMap<>());
        m.put("impostazioni", st.get("impostazioni") instanceof Map ? st.get("impostazioni") : new LinkedHashMap<>());
        Map<String, Object> c = new LinkedHashMap<>();
        Object cc = st.get("cancellati");
        for (String k : SEZIONI) {
            Object v = cc instanceof Map ? ((Map<?, ?>) cc).get(k) : null;
            c.put(k, v instanceof Map ? v : new LinkedHashMap<>());
        }
        m.put("cancellati", c);
        return impronta(m);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> applicaModificheLocali(Map<String, Object> base,
            List<Map<String, Object>> posizioni, List<Map<String, Object>> storico,
            Map<String, Object> impostazioni, double adesso) {
        return applicaModificheLocali(base, posizioni, storico, impostazioni, adesso, new LinkedHashMap<>());
    }

    public static Map<String, Object> applicaModificheLocali(Map<String, Object> base,
            List<Map<String, Object>> posizioni, List<Map<String, Object>> storico,
            Map<String, Object> impostazioni, double adesso, List<Map<String, Object>> segnali) {
        Map<String, List<Map<String, Object>>> altre = new LinkedHashMap<>();
        altre.put("segnali", segnali);
        return applicaModificheLocali(base, posizioni, storico, impostazioni, adesso, altre);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> applicaModificheLocali(Map<String, Object> base,
            List<Map<String, Object>> posizioni, List<Map<String, Object>> storico,
            Map<String, Object> impostazioni, double adesso, Map<String, List<Map<String, Object>>> altre) {
        Map<String, Object> nuovo = (Map<String, Object>) Json.copia(base);
        List<Object[]> sezioni = new ArrayList<>();
        sezioni.add(new Object[]{"posizioni", posizioni, "titolo"});
        sezioni.add(new Object[]{"storico", storico, "id"});
        for (String[] f : SEZIONI_FILE) {
            List<Map<String, Object>> el = altre.get(f[0]);
            sezioni.add(new Object[]{f[0], el != null ? el : new ArrayList<Map<String, Object>>(), f[2]});
        }
        for (Object[] s : sezioni) {
            String sezione = (String) s[0];
            Map<String, Map<String, Object>> attuali = new LinkedHashMap<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) s[1]) attuali.put((String) r.get((String) s[2]), r);
            Map<String, Object> sezNuovo = sez(nuovo, sezione);
            Map<String, Object> canc = sez(sez(nuovo, "cancellati"), sezione);
            for (Map.Entry<String, Map<String, Object>> e : attuali.entrySet()) {
                Map<String, Object> prec = (Map<String, Object>) sezNuovo.get(e.getKey());
                if (prec == null || !impronta(prec.get("dati")).equals(impronta(e.getValue()))) {
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("_ts", adesso);
                    rec.put("dati", e.getValue());
                    sezNuovo.put(e.getKey(), rec);
                    canc.remove(e.getKey());
                }
            }
            for (String k : new ArrayList<>(sezNuovo.keySet())) {
                if (!attuali.containsKey(k)) {
                    sezNuovo.remove(k);
                    canc.put(k, adesso);
                }
            }
        }
        Map<String, Object> imp = sez(nuovo, "impostazioni");
        for (Map.Entry<String, Object> e : impostazioni.entrySet()) {
            if ("monitoraggio_attivo".equals(e.getKey())) continue;
            Map<String, Object> prec = (Map<String, Object>) imp.get(e.getKey());
            if (prec == null || !impronta(prec.get("valore")).equals(impronta(e.getValue()))) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("_ts", adesso);
                rec.put("valore", e.getValue());
                imp.put(e.getKey(), rec);
            }
        }
        return nuovo;
    }

    static String idOperazione(Map<String, Object> r) {
        Object id = r.get("id");
        if (id != null && !id.toString().startsWith("vecchia-")) return id.toString();
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : new String[]{"titolo", "data", "data_vendita", "prezzo", "prezzo_vendita"}) m.put(k, r.get(k));
        return "op-" + impronta(m).substring(0, 16);
    }

    // ------------------------------------------------------------------ file

    private Object leggiFile(String nome, Object predefinito) {
        File f = new File(archivio.cartella, nome);
        if (!f.exists()) return predefinito;
        try { return Json.leggi(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return predefinito; }
    }

    // ------------------------------------------------------------------ un giro

    /** Esito di un giro: sezioni cambiate dall'altro dispositivo e se ho pubblicato. */
    public static final class Esito {
        public final Set<String> cambiati = new HashSet<>();
        public boolean pubblicato;
    }

    @SuppressWarnings("unchecked")
    public Esito giro(Canale tg) throws Exception {
        Esito esito = new Esito();
        synchronized (archivio.lock) {
            Map<String, Object> base = (Map<String, Object>) leggiFile(FILE_BASE, null);
            boolean primo = base == null;
            if (primo) base = statoVuoto();
            List<Map<String, Object>> posizioni = archivio.leggiLista(Archivio.FILE_POS);
            List<Map<String, Object>> storico = archivio.leggiLista(Archivio.FILE_STORICO);
            for (Map<String, Object> r : storico) r.put("id", idOperazione(r));
            Map<String, Object> impostazioni = (Map<String, Object>) leggiFile(FILE_IMPOSTAZIONI, new LinkedHashMap<>());
            Map<String, List<Map<String, Object>>> altre = new LinkedHashMap<>();
            for (String[] f : SEZIONI_FILE) altre.put(f[0], archivio.leggiLista(f[1]));
            for (Map<String, Object> r : altre.get("v_storico")) r.put("id", idOperazione(r));

            Object[] fiss = tg.fissato();
            Long mid = fiss == null ? null : (Long) fiss[0];
            double adesso = (primo && mid != null) ? 1.0 : System.currentTimeMillis() / 1000.0;
            Map<String, Object> locale = applicaModificheLocali(base, posizioni, storico, impostazioni, adesso, altre);

            Map<String, Object> remoto = null;
            if (mid != null && !mid.equals(ultimoFissato)) {
                remoto = tg.scarica((String) fiss[1]);
                if (Json.num(remoto.get("formato"), 0) > FORMATO)
                    throw new IllegalStateException("stato creato da una versione più recente: aggiorna l'app");
            }
            Map<String, Object> unito = remoto != null ? unisci(locale, remoto) : locale;

            List<Map<String, Object>> nuovePos = new ArrayList<>();
            for (Object r : sez(unito, "posizioni").values()) nuovePos.add((Map<String, Object>) ((Map<String, Object>) r).get("dati"));
            List<Map<String, Object>> nuovoSto = new ArrayList<>();
            for (Object r : sez(unito, "storico").values()) nuovoSto.add((Map<String, Object>) ((Map<String, Object>) r).get("dati"));
            Comparator<Map<String, Object>> perTitolo = Comparator.comparing(r -> Json.str(r.get("titolo"), ""));
            Comparator<Map<String, Object>> perVendita = Comparator.comparing(
                    (Map<String, Object> r) -> Json.str(r.get("data_vendita"), "")).thenComparing(r -> Json.str(r.get("id"), ""));
            nuovoSto.sort(perVendita);
            List<Map<String, Object>> posOrd = new ArrayList<>(posizioni);
            posOrd.sort(perTitolo);
            List<Map<String, Object>> nuovePosOrd = new ArrayList<>(nuovePos);
            nuovePosOrd.sort(perTitolo);
            List<Map<String, Object>> stoOrd = new ArrayList<>(storico);
            stoOrd.sort(perVendita);
            if (!impronta(nuovePosOrd).equals(impronta(posOrd))) {
                archivio.scriviFile(Archivio.FILE_POS, nuovePos, false);
                esito.cambiati.add("posizioni");
            }
            if (!impronta(nuovoSto).equals(impronta(stoOrd))) {
                archivio.scriviFile(Archivio.FILE_STORICO, nuovoSto, false);
                esito.cambiati.add("storico");
            }
            for (String[] f : SEZIONI_FILE) {
                final String chiave = f[2];
                Comparator<Map<String, Object>> perChiave = Comparator.comparing(r -> Json.str(r.get(chiave), ""));
                List<Map<String, Object>> nuovi = new ArrayList<>();
                for (Object r : sez(unito, f[0]).values()) nuovi.add((Map<String, Object>) ((Map<String, Object>) r).get("dati"));
                nuovi.sort(perChiave);
                List<Map<String, Object>> vecchi = new ArrayList<>(altre.get(f[0]));
                vecchi.sort(perChiave);
                if (!impronta(nuovi).equals(impronta(vecchi))) {
                    archivio.scriviFile(f[1], nuovi, false);
                    esito.cambiati.add(f[0]);
                }
            }
            Map<String, Object> nuoveImp = new LinkedHashMap<>(impostazioni);
            for (Map.Entry<String, Object> e : sez(unito, "impostazioni").entrySet())
                nuoveImp.put(e.getKey(), ((Map<String, Object>) e.getValue()).get("valore"));
            if (!impronta(nuoveImp).equals(impronta(impostazioni))) {
                archivio.scriviFile(FILE_IMPOSTAZIONI, nuoveImp, false);
                esito.cambiati.add("impostazioni");
            }

            boolean pubblicare;
            if (remoto == null && mid != null && mid.equals(ultimoFissato))
                pubblicare = !contenuto(unito).equals(contenuto(base));
            else
                pubblicare = remoto == null || !contenuto(unito).equals(contenuto(remoto));
            if (pubblicare) {
                unito.put("aggiornato", System.currentTimeMillis() / 1000.0);
                unito.put("da", dispositivo);
                List<Long> vecchi = new ArrayList<>(documenti);
                if (mid != null && !vecchi.contains(mid)) vecchi.add(mid);
                long nuovo = tg.pubblica(unito, vecchi);
                documenti.clear();
                documenti.add(nuovo);
                ultimoFissato = nuovo;
            } else {
                ultimoFissato = mid;
            }
            esito.pubblicato = pubblicare;
            archivio.scriviFile(FILE_BASE, unito, false);
        }
        return esito;
    }
}
