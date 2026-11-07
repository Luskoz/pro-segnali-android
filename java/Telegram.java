package it.prosegnali.motore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bot Telegram: avvisi (con pulsanti, link alla scheda del titolo, ISIN da copiare) e canale. */
public class Telegram {
    public final String token, chat;
    private final String url;
    private static Pattern regexTitoli;

    public Telegram(String token, String chat) {
        this.token = token == null ? "" : token.trim();
        this.chat = chat == null ? "" : chat.trim();
        this.url = "https://api.telegram.org/bot" + this.token;
    }

    public boolean configurato() { return !token.isEmpty() && !chat.isEmpty(); }

    @SuppressWarnings("unchecked")
    public Object chiama(String metodo, Map<String, String> campi, int timeoutSec) throws Exception {
        Rete.Risposta r = Rete.postModulo(url + "/" + metodo, campi, timeoutSec);
        Map<String, Object> j = Json.oggetto(r.testo());
        if (!Boolean.TRUE.equals(j.get("ok")))
            throw new IllegalStateException(metodo + ": " + Json.str(j.get("description"), "errore " + r.codice));
        return j.get("result");
    }

    private static synchronized Pattern regex() {
        if (regexTitoli == null) {
            List<String> simboli = new ArrayList<>(Mercati.anagrafica().keySet());
            simboli.sort((a, b) -> b.length() - a.length());
            StringBuilder b = new StringBuilder("(?<![\\w.])(");
            for (int i = 0; i < simboli.size(); i++) {
                if (i > 0) b.append('|');
                b.append(Pattern.quote(simboli.get(i)));
            }
            b.append(")(?![\\w.])");
            regexTitoli = Pattern.compile(b.toString());
        }
        return regexTitoli;
    }

    /**
     * Invia un messaggio. codici: testi "tocca per copiare" (ISIN); collegamenti: testo -> url.
     * I simboli dei titoli diventano link alla loro scheda (Java usa UTF-16 come Telegram).
     */
    public void invia(String testo, List<String[]> pulsanti, List<String> codici, Map<String, String> collegamenti) {
        if (!configurato()) return;
        try {
            List<int[]> occupati = new ArrayList<>();
            List<Object> entita = new ArrayList<>();
            if (codici != null) for (String c : codici) {
                int pos = c == null || c.isEmpty() ? -1 : testo.indexOf(c);
                if (pos >= 0) aggiungi(entita, occupati, pos, pos + c.length(), "code", null);
            }
            if (collegamenti != null) for (Map.Entry<String, String> e : collegamenti.entrySet()) {
                int pos = testo.indexOf(e.getKey());
                if (pos >= 0) aggiungi(entita, occupati, pos, pos + e.getKey().length(), "text_link", e.getValue());
            }
            Matcher m = regex().matcher(testo);
            while (m.find()) {
                String simbolo = m.group(1);
                int a = m.start(), b = m.end();
                boolean parentesi = a > 0 && testo.charAt(a - 1) == '(' && b < testo.length() && testo.charAt(b) == ')';
                boolean inizioRiga = a == 0 || testo.charAt(a - 1) == '\n';
                if (simbolo.contains(".") || parentesi || inizioRiga)
                    aggiungi(entita, occupati, a, b, "text_link", Mercati.scheda(simbolo));
            }
            Map<String, String> campi = new LinkedHashMap<>();
            campi.put("chat_id", chat);
            campi.put("text", testo);
            campi.put("link_preview_options", "{\"is_disabled\":true}");
            if (!entita.isEmpty()) {
                entita.sort((x, y) -> Long.compare(((Number) ((Map<?, ?>) x).get("offset")).longValue(),
                        ((Number) ((Map<?, ?>) y).get("offset")).longValue()));
                campi.put("entities", Json.scrivi(entita));
            }
            if (pulsanti != null && !pulsanti.isEmpty()) {
                List<Object> riga = new ArrayList<>();
                for (String[] p : pulsanti) {
                    Map<String, Object> btn = new LinkedHashMap<>();
                    btn.put("text", p[0]);
                    btn.put("callback_data", p[1]);
                    riga.add(btn);
                }
                Map<String, Object> markup = new LinkedHashMap<>();
                markup.put("inline_keyboard", Collections.singletonList(riga));
                campi.put("reply_markup", Json.scrivi(markup));
            }
            chiama("sendMessage", campi, 20);
        } catch (Exception e) {
            System.err.println("Telegram non raggiungibile: " + e.getMessage());
        }
    }

    public void invia(String testo) { invia(testo, null, null, null); }

    private static void aggiungi(List<Object> entita, List<int[]> occupati, int a, int b, String tipo, String link) {
        for (int[] o : occupati) if (a < o[1] && o[0] < b) return;
        occupati.add(new int[]{a, b});
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", tipo);
        e.put("offset", (long) a);
        e.put("length", (long) (b - a));
        if (link != null) e.put("url", link);
        entita.add(e);
    }

    public void togliPulsanti(Object messageId) {
        try {
            Map<String, String> c = new LinkedHashMap<>();
            c.put("chat_id", chat);
            c.put("message_id", String.valueOf(messageId));
            c.put("reply_markup", "{\"inline_keyboard\":[]}");
            chiama("editMessageReplyMarkup", c, 15);
        } catch (Exception ignorata) { /* messaggio troppo vecchio */ }
    }

    public void rispondiPulsante(String id, String testo) {
        try {
            Map<String, String> c = new LinkedHashMap<>();
            c.put("callback_query_id", id);
            c.put("text", testo);
            chiama("answerCallbackQuery", c, 15);
        } catch (Exception ignorata) { /* niente */ }
    }

    /** Aggiornamenti (long polling). Lista vuota in caso di errore o di conflitto con il PC. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> aggiornamenti(Long offset, int attesa) {
        try {
            String u = url + "/getUpdates?timeout=" + attesa + (offset != null ? "&offset=" + offset : "");
            Map<String, Object> j = Json.oggetto(Rete.get(u, attesa + 15).testo());
            if (!Boolean.TRUE.equals(j.get("ok"))) {
                Thread.sleep(15000);                      // es. il PC sta gia' ascoltando
                return new ArrayList<>();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object x : (List<Object>) j.get("result")) out.add((Map<String, Object>) x);
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        } catch (Exception e) {
            try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------ canale di sincronia

    /** [message_id, file_id] del documento fissato nel canale, oppure null. */
    @SuppressWarnings("unchecked")
    public Object[] fissato(String canale) throws Exception {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("chat_id", canale);
        Map<String, Object> info = (Map<String, Object>) chiama("getChat", c, 20);
        Map<String, Object> msg = (Map<String, Object>) info.get("pinned_message");
        if (msg == null || msg.get("document") == null) return null;
        Map<String, Object> doc = (Map<String, Object>) msg.get("document");
        return new Object[]{((Number) msg.get("message_id")).longValue(), doc.get("file_id")};
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> scaricaStato(String fileId) throws Exception {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("file_id", fileId);
        Map<String, Object> f = (Map<String, Object>) chiama("getFile", c, 20);
        Rete.Risposta r = Rete.get("https://api.telegram.org/file/bot" + token + "/" + f.get("file_path"), 30);
        if (r.codice != 200) throw new IllegalStateException("download stato: HTTP " + r.codice);
        return Json.oggetto(r.testo());
    }

    @SuppressWarnings("unchecked")
    public long pubblicaStato(String canale, Map<String, Object> stato, List<Long> vecchi) throws Exception {
        byte[] dati = Json.scrivi(stato).getBytes(StandardCharsets.UTF_8);
        Map<String, String> c = new LinkedHashMap<>();
        c.put("chat_id", canale);
        c.put("disable_notification", "true");
        c.put("caption", "PRO.SEGNALI - stato sincronizzato (da " + stato.get("da") + "). Non cancellare.");
        Rete.Risposta r = Rete.postFile(url + "/sendDocument", c, "document", "pro_segnali_stato.json", dati, 30);
        Map<String, Object> j = Json.oggetto(r.testo());
        if (!Boolean.TRUE.equals(j.get("ok")))
            throw new IllegalStateException("sendDocument: " + j.get("description"));
        long mid = ((Number) ((Map<String, Object>) j.get("result")).get("message_id")).longValue();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("chat_id", canale);
        p.put("message_id", String.valueOf(mid));
        p.put("disable_notification", "true");
        chiama("pinChatMessage", p, 20);
        List<Long> daCancellare = new ArrayList<>(vecchi);
        daCancellare.add(mid + 1);                         // avviso di servizio "ha fissato..."
        for (Long v : daCancellare) {
            try {
                Map<String, String> d = new LinkedHashMap<>();
                d.put("chat_id", canale);
                d.put("message_id", String.valueOf(v));
                chiama("deleteMessage", d, 15);
            } catch (Exception ignorata) { /* gia' cancellato */ }
        }
        return mid;
    }
}
