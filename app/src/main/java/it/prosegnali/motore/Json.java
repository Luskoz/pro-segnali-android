package it.prosegnali.motore;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON minimo, senza librerie esterne: oggetti = Map, array = List, numeri interi = Long,
 * numeri decimali = Double. Distinguere interi e decimali evita di "cambiare" dati
 * scritti dal PC (es. 1050 resta 1050, 1000.0 resta 1000.0).
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object leggi(String testo) {
        Json p = new Json(testo);
        p.spazi();
        Object v = p.valore();
        p.spazi();
        if (p.i != p.s.length()) throw new IllegalArgumentException("JSON: testo in eccesso");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> oggetto(String testo) {
        return (Map<String, Object>) leggi(testo);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> lista(String testo) {
        return (List<Object>) leggi(testo);
    }

    private void spazi() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object valore() {
        if (i >= s.length()) throw new IllegalArgumentException("JSON: fine inattesa");
        char c = s.charAt(i);
        if (c == '{') return oggettoInterno();
        if (c == '[') return listaInterna();
        if (c == '"') return stringa();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        if (s.startsWith("NaN", i)) { i += 3; return Double.NaN; }
        if (s.startsWith("Infinity", i)) { i += 8; return Double.POSITIVE_INFINITY; }
        if (s.startsWith("-Infinity", i)) { i += 9; return Double.NEGATIVE_INFINITY; }
        return numero();
    }

    private Map<String, Object> oggettoInterno() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        spazi();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            spazi();
            String k = stringa();
            spazi();
            if (s.charAt(i) != ':') throw new IllegalArgumentException("JSON: atteso ':'");
            i++;
            spazi();
            m.put(k, valore());
            spazi();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("JSON: atteso ',' o '}'");
        }
    }

    private List<Object> listaInterna() {
        List<Object> l = new ArrayList<>();
        i++;
        spazi();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            spazi();
            l.add(valore());
            spazi();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("JSON: atteso ',' o ']'");
        }
    }

    private String stringa() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("JSON: attesa stringa");
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object numero() {
        int inizio = i;
        boolean decimale = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.' || c == 'e' || c == 'E') decimale = true;
            else if (!(c == '-' || c == '+' || Character.isDigit(c))) break;
            i++;
        }
        String t = s.substring(inizio, i);
        if (t.isEmpty()) throw new IllegalArgumentException("JSON: carattere inatteso a " + i);
        if (!decimale) {
            try { return Long.parseLong(t); } catch (NumberFormatException ex) { /* troppo grande */ }
        }
        return Double.parseDouble(t);
    }

    // ------------------------------------------------------------------ scrittura

    public static String scrivi(Object v) {
        StringBuilder b = new StringBuilder();
        scrivi(b, v, false);
        return b.toString();
    }

    /** Scrittura con chiavi ordinate: serve a confrontare contenuti ("impronta"). */
    public static String canonico(Object v) {
        StringBuilder b = new StringBuilder();
        scrivi(b, v, true);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void scrivi(StringBuilder b, Object v, boolean ordina) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String) { stringa(b, (String) v); return; }
        if (v instanceof Boolean) { b.append(v.toString()); return; }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d)) b.append("NaN");
            else if (Double.isInfinite(d)) b.append(d > 0 ? "Infinity" : "-Infinity");
            else b.append(Double.toString(d));
            return;
        }
        if (v instanceof Number) { b.append(((Number) v).longValue()); return; }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (ordina) m = new TreeMap<>(m);
            b.append('{');
            Iterator<Map.Entry<String, Object>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                stringa(b, e.getKey());
                b.append(':');
                scrivi(b, e.getValue(), ordina);
                if (it.hasNext()) b.append(',');
            }
            b.append('}');
            return;
        }
        if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(',');
                scrivi(b, l.get(k), ordina);
            }
            b.append(']');
            return;
        }
        stringa(b, v.toString());
    }

    private static void stringa(StringBuilder b, String t) {
        b.append('"');
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------------ utilita'

    /** Copia profonda (per non modificare strutture condivise). */
    @SuppressWarnings("unchecked")
    public static Object copia(Object v) {
        if (v instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet())
                m.put(e.getKey(), copia(e.getValue()));
            return m;
        }
        if (v instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object x : (List<Object>) v) l.add(copia(x));
            return l;
        }
        return v;
    }

    public static double num(Object v, double predefinito) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble(((String) v).replace(",", ".")); }
            catch (NumberFormatException e) { return predefinito; }
        }
        return predefinito;
    }

    public static String str(Object v, String predefinito) {
        return v == null ? predefinito : v.toString();
    }
}
