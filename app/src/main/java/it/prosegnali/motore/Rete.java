package it.prosegnali.motore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Richieste HTTP essenziali (GET, POST di modulo, POST con file). */
public final class Rete {
    private Rete() {}

    public static final String AGENTE =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    public static final class Risposta {
        public final int codice;
        public final byte[] corpo;

        Risposta(int codice, byte[] corpo) { this.codice = codice; this.corpo = corpo; }

        public String testo() { return new String(corpo, StandardCharsets.UTF_8); }
    }

    public static String codifica(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    public static String modulo(Map<String, String> campi) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : campi.entrySet()) {
            if (e.getValue() == null) continue;
            if (b.length() > 0) b.append('&');
            b.append(codifica(e.getKey())).append('=').append(codifica(e.getValue()));
        }
        return b.toString();
    }

    private static byte[] leggiTutto(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static Risposta esegui(HttpURLConnection c) throws IOException {
        int codice = c.getResponseCode();
        InputStream in = codice >= 400 ? c.getErrorStream() : c.getInputStream();
        Risposta r = new Risposta(codice, leggiTutto(in));
        c.disconnect();
        return r;
    }

    private static HttpURLConnection apri(String url, int timeoutSec) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(timeoutSec * 1000);
        c.setRequestProperty("User-Agent", AGENTE);
        c.setRequestProperty("Accept", "application/json,*/*");
        return c;
    }

    public static Risposta get(String url, int timeoutSec) throws IOException {
        return esegui(apri(url, timeoutSec));
    }

    public static Risposta postModulo(String url, Map<String, String> campi, int timeoutSec) throws IOException {
        HttpURLConnection c = apri(url, timeoutSec);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] dati = modulo(campi).getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = c.getOutputStream()) { o.write(dati); }
        return esegui(c);
    }

    /** POST multipart con un file (per sendDocument di Telegram). */
    public static Risposta postFile(String url, Map<String, String> campi, String nomeCampo,
                                    String nomeFile, byte[] contenuto, int timeoutSec) throws IOException {
        String confine = "----ProSegnali" + System.nanoTime();
        HttpURLConnection c = apri(url, timeoutSec);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + confine);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : campi.entrySet()) {
            if (e.getValue() == null) continue;
            b.write(("--" + confine + "\r\nContent-Disposition: form-data; name=\"" + e.getKey()
                    + "\"\r\n\r\n" + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        b.write(("--" + confine + "\r\nContent-Disposition: form-data; name=\"" + nomeCampo
                + "\"; filename=\"" + nomeFile + "\"\r\nContent-Type: application/json\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        b.write(contenuto);
        b.write(("\r\n--" + confine + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try (OutputStream o = c.getOutputStream()) { o.write(b.toByteArray()); }
        return esegui(c);
    }

    /** GET con intestazioni aggiuntive; restituisce anche i cookie ricevuti ("nome=valore; ..."). */
    public static Risposta getConIntestazioni(String url, int timeoutSec, Map<String, String> intestazioni,
                                             StringBuilder cookieRicevuti) throws IOException {
        HttpURLConnection c = apri(url, timeoutSec);
        c.setInstanceFollowRedirects(false);
        if (intestazioni != null)
            for (Map.Entry<String, String> e : intestazioni.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        int codice = c.getResponseCode();
        if (cookieRicevuti != null) {
            java.util.List<String> sc = c.getHeaderFields().get("Set-Cookie");
            if (sc == null) sc = c.getHeaderFields().get("set-cookie");
            if (sc != null) for (String x : sc) {
                String coppia = x.split(";", 2)[0].trim();
                if (coppia.isEmpty()) continue;
                if (cookieRicevuti.length() > 0) cookieRicevuti.append("; ");
                cookieRicevuti.append(coppia);
            }
        }
        InputStream in = codice >= 400 ? c.getErrorStream() : c.getInputStream();
        Risposta r = new Risposta(codice, leggiTutto(in));
        c.disconnect();
        return r;
    }
}
