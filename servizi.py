"""
PRO.SEGNALI - servizi in background
-----------------------------------
- Finnhub: prezzi in tempo reale USA e stato del mercato USA
- Yahoo:   prezzi europei (una richiesta per tutti i titoli), con misura del ritardo
- Telegram: invio avvisi con pulsanti e ricezione comandi
- Monitor: un thread per mercato; durante la seduta controlla i prezzi circa
  ogni minuto e avvisa quando un titolo tocca il suo livello, o quando una
  posizione aperta tocca stop / obiettivo / tempo massimo.
"""
import json
import re
import os
import threading
import time
from datetime import date, datetime, time as ora_t

import requests
from dotenv import load_dotenv

import motore

load_dotenv()
PAUSA_FINNHUB = 1.05        # secondi tra due richieste (piano gratuito: 60/minuto)
PAUSA_YAHOO = 120           # secondi tra due giri sui titoli europei
RITARDO_AVVISO_MIN = 3      # oltre questo ritardo lo scrivo nell'avviso


def fmt(x, dec=2):
    return f"{x:,.{dec}f}".replace(",", "X").replace(".", ",").replace("X", ".")


def pct(x, dec=1):
    return f"{x:+.{dec}f}%".replace(".", ",")


# =====================================================================
# FONTI DEI PREZZI
# =====================================================================
class Finnhub:
    URL = "https://finnhub.io/api/v1"

    def __init__(self):
        self.chiave = os.getenv("FINNHUB_API_KEY", "").strip()
        self._ultima = 0.0
        self._stato_cache = (0.0, None)
        self._lock = threading.Lock()

    def _get(self, percorso, **params):
        with self._lock:
            attesa = PAUSA_FINNHUB - (time.time() - self._ultima)
            if attesa > 0:
                time.sleep(attesa)
            self._ultima = time.time()
        r = requests.get(f"{self.URL}/{percorso}", params={**params, "token": self.chiave},
                         timeout=15)
        if r.status_code == 429:                      # troppe richieste: aspetto
            time.sleep(10)
            return None
        r.raise_for_status()
        return r.json()

    def quotazione(self, titolo):
        try:
            q = self._get("quote", symbol=titolo)
        except Exception:
            return None
        if not q or not q.get("c"):
            return None
        ritardo = None
        if q.get("t"):
            ritardo = max(0.0, (time.time() - float(q["t"])) / 60)
        return {"c": float(q["c"]), "ritardo": ritardo}

    def quotazioni(self, titoli, ferma=None):
        """Generatore: un titolo alla volta (il piano gratuito non ammette richieste multiple)."""
        for tk in titoli:
            if ferma is not None and ferma.is_set():
                return
            q = self.quotazione(tk)
            if q:
                yield tk, q

    def mercato_aperto(self):
        """Stato del mercato USA (festivi compresi). Ricontrolla ogni 5 minuti."""
        momento, valore = self._stato_cache
        if time.time() - momento < 300 and valore is not None:
            return valore
        valore = orario_di_borsa("USA")
        try:
            st = self._get("stock/market-status", exchange="US")
            if st and "isOpen" in st:
                valore = bool(st["isOpen"])
        except Exception:
            pass
        self._stato_cache = (time.time(), valore)
        return valore


class Trimestrali:
    """
    Date delle prossime trimestrali (pubblicazione dei conti) da Yahoo, una volta al giorno.
    Restituisce {titolo: "AAAA-MM-GG"}; in caso di errore un dizionario vuoto (nessun avviso).
    """
    FILE = "trimestrali.json"
    AGENTE = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")

    def __init__(self):
        self.lock = threading.Lock()
        self.ultimo_errore = ""

    def _leggi_cache(self):
        try:
            with open(self.FILE, encoding="utf-8") as f:
                return json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            return {}

    def prossime(self, titoli, forza=False):
        oggi = date.today().isoformat()
        with self.lock:
            cache = self._leggi_cache()
            if not forza and cache.get("giorno") == oggi and all(t in cache.get("fatti", [])
                                                                 for t in titoli):
                return {t: d for t, d in cache.get("date", {}).items() if t in titoli}
            try:
                nuove = self._scarica(titoli)
            except Exception as e:
                self.ultimo_errore = str(e)[:150]
                return {t: d for t, d in cache.get("date", {}).items()
                        if t in titoli and d >= oggi}
            if cache.get("giorno") != oggi:
                cache = {"giorno": oggi, "date": {}, "fatti": []}
            cache["date"].update(nuove)
            cache["fatti"] = sorted(set(cache["fatti"]) | set(titoli))
            try:
                with open(self.FILE, "w", encoding="utf-8") as f:
                    json.dump(cache, f, ensure_ascii=False, indent=1)
            except OSError:
                pass
            return {t: d for t, d in cache["date"].items() if t in titoli}

    def _scarica(self, titoli):
        sess = requests.Session()
        sess.headers["User-Agent"] = self.AGENTE
        sess.get("https://fc.yahoo.com/", timeout=20)          # imposta il cookie (risponde 404)
        crumb = sess.get("https://query1.finance.yahoo.com/v1/test/getcrumb", timeout=20).text
        if not crumb or "<" in crumb or len(crumb) > 40:
            raise RuntimeError("Yahoo non ha dato l'autorizzazione (crumb)")
        out = {}
        inizio_oggi = datetime.combine(date.today(), ora_t()).timestamp()
        for i in range(0, len(titoli), 40):
            gruppo = titoli[i:i + 40]
            r = sess.get("https://query1.finance.yahoo.com/v7/finance/quote", timeout=30, params={
                "symbols": ",".join(gruppo), "crumb": crumb,
                "fields": "earningsTimestamp,earningsTimestampStart,earningsTimestampEnd"})
            r.raise_for_status()
            for q in r.json().get("quoteResponse", {}).get("result", []):
                tempi = [q.get(k) for k in ("earningsTimestamp", "earningsTimestampStart",
                                            "earningsTimestampEnd")]
                futuri = [t for t in tempi if isinstance(t, (int, float)) and t >= inizio_oggi]
                if futuri and q.get("symbol"):
                    out[q["symbol"]] = date.fromtimestamp(min(futuri)).isoformat()
        return out


TRIMESTRALI = Trimestrali()


class Yahoo:
    """
    Prezzi a 1 minuto da Yahoo. Yahoo limita le richieste troppo frequenti: per questo
    il controllo e' ogni 2 minuti e, se Yahoo non risponde, la pausa raddoppia da sola
    (fino a 15 minuti) e torna normale appena i prezzi riprendono ad arrivare.
    """
    PAUSA_MAX = 900

    def __init__(self):
        self._ultima = 0.0
        self.pausa = PAUSA_YAHOO
        self.ultimo_errore = ""

    def scarica(self, titoli):
        import yfinance as yf          # import qui: rende il modulo caricabile anche senza rete
        if not titoli:
            return {}
        raw = yf.download(list(titoli), period="1d", interval="1m", group_by="ticker",
                          progress=False, threads=4, auto_adjust=False)
        errori = dict(getattr(getattr(yf, "shared", None), "_ERRORS", {}) or {})
        risultato = {}
        adesso = datetime.now().astimezone()
        for tk in titoli:
            try:
                df = raw[tk] if len(titoli) > 1 or tk in raw.columns.get_level_values(0) else raw
                df = df.dropna(subset=["Close"])
            except (KeyError, AttributeError, TypeError):
                continue
            if df.empty:
                continue
            ultimo = df.index[-1]
            if ultimo.tzinfo is None:
                ultimo = ultimo.tz_localize("UTC")
            ritardo = max(0.0, (adesso - ultimo.to_pydatetime()).total_seconds() / 60 - 1)
            risultato[tk] = {"c": float(df["Close"].iloc[-1]), "ritardo": ritardo}
        if not risultato:
            motivo = next(iter(errori.values()), "") if errori else ""
            self.ultimo_errore = (str(motivo)[:200] or "Yahoo ha risposto senza prezzi")
        return risultato

    def quotazioni(self, titoli, ferma=None):
        attesa = self.pausa - (time.time() - self._ultima)
        if attesa > 0 and ferma is not None:
            if ferma.wait(attesa):
                return
        self._ultima = time.time()
        try:
            dati = self.scarica(titoli)
        except Exception as e:
            self.ultimo_errore = f"{type(e).__name__}: {e}"[:200]
            dati = {}
        if dati:
            self.pausa, self.ultimo_errore = PAUSA_YAHOO, ""
        else:
            self.pausa = min(self.pausa * 2, self.PAUSA_MAX)
        for tk, q in dati.items():
            yield tk, q

    def mercato_aperto(self):
        return orario_di_borsa("Europa")


class Cambio:
    """
    Cambio EUR/USD (quanti dollari vale 1 euro), da Yahoo con riserva Finnhub.
    Si aggiorna al massimo ogni 15 minuti; l'ultimo valore valido resta salvato
    in cambio.json, cosi' e' disponibile anche subito dopo l'avvio.
    """
    FILE = "cambio.json"
    VALIDITA = 900

    def __init__(self):
        self._lock = threading.Lock()
        self._in_corso = False
        self.valore, self.quando = None, 0.0
        try:
            with open(self.FILE, encoding="utf-8") as f:
                d = json.load(f)
            self.valore, self.quando = float(d["valore"]), float(d["quando"])
        except Exception:
            pass

    @staticmethod
    def _plausibile(v):
        return v is not None and 0.5 < v < 2.5

    def _scarica(self):
        try:
            import yfinance as yf
            df = yf.download("EURUSD=X", period="5d", interval="15m", progress=False,
                             auto_adjust=False)
            chiusure = df["Close"]
            if hasattr(chiusure, "columns"):            # colonne a piu' livelli
                chiusure = chiusure.iloc[:, 0]
            v = float(chiusure.dropna().iloc[-1])
            if self._plausibile(v):
                return v
        except Exception:
            pass
        try:
            chiave = os.getenv("FINNHUB_API_KEY", "").strip()
            r = requests.get("https://finnhub.io/api/v1/forex/rates",
                             params={"base": "EUR", "token": chiave}, timeout=15).json()
            v = float(r["quote"]["USD"])
            if self._plausibile(v):
                return v
        except Exception:
            pass
        return None

    def aggiorna(self, forza=False):
        """Aggiorna se scaduto (blocca qualche secondo: da usare nei thread di lavoro)."""
        if not forza and self.valore and time.time() - self.quando < self.VALIDITA:
            return self.valore
        v = self._scarica()
        if v:
            with self._lock:
                self.valore, self.quando = v, time.time()
            try:
                with open(self.FILE, "w", encoding="utf-8") as f:
                    json.dump({"valore": v, "quando": self.quando}, f)
            except OSError:
                pass
        return self.valore

    def ottieni(self):
        """Valore attuale senza attendere; se scaduto lo aggiorna in sottofondo."""
        scaduto = not self.valore or time.time() - self.quando >= self.VALIDITA
        if scaduto and not self._in_corso:
            self._in_corso = True

            def lavora():
                try:
                    self.aggiorna()
                finally:
                    self._in_corso = False

            threading.Thread(target=lavora, daemon=True).start()
        return self.valore


CAMBIO = Cambio()


def e_usa(titolo):
    return motore.MERCATI[motore.mercato_di(titolo)]["valuta"] == "$"


def cambio_per(titolo):
    """Dollari per 1 euro per i titoli USA, 1 per gli europei (None se non disponibile)."""
    return CAMBIO.ottieni() if e_usa(titolo) else 1.0


def in_euro(titolo, valore):
    c = cambio_per(titolo)
    return valore / c if (valore is not None and c) else None


def con_euro(titolo, valore):
    """'479,40 $ (≈ 443,90 €)' per gli USA, '26,57 €' per l'Europa."""
    if not e_usa(titolo):
        return f"{fmt(valore)} €"
    e = in_euro(titolo, valore)
    return f"{fmt(valore)} $" + (f" (≈ {fmt(e)} €)" if e else "")


def orario_di_borsa(mercato):
    m = motore.MERCATI[mercato]
    adesso = datetime.now(m["fuso"])
    return adesso.weekday() < 5 and m["apertura"] <= adesso.time() < m["chiusura"]


SCHEDA_TITOLO = "https://finance.yahoo.com/quote/{}/"
_RE_TITOLI = None


def scheda_titolo(titolo):
    """Pagina con grafico, dati principali e notizie del titolo (Yahoo Finance)."""
    return SCHEDA_TITOLO.format(titolo)


def _regex_titoli():
    """Riconosce nei messaggi i simboli dei titoli seguiti (es. DHL.DE, ENI.MI, AAPL)."""
    global _RE_TITOLI
    if _RE_TITOLI is None:
        simboli = sorted(motore.ANAGRAFICA, key=len, reverse=True)
        _RE_TITOLI = re.compile(r"(?<![\w.])(" + "|".join(re.escape(x) for x in simboli)
                                + r")(?![\w.])")
    return _RE_TITOLI


def _u16(testo):
    return len(testo.encode("utf-16-le")) // 2


def identita(titolo):
    """Nome, simbolo e ISIN su due righe: 'Banco Santander' / 'SAN.MC - ISIN ES0113900J37'."""
    isin = motore.isin_titolo(titolo)
    return motore.nome_titolo(titolo), f"{titolo}" + (f"  ·  ISIN {isin}" if isin else "")


def raggruppa(livelli):
    """Unisce le righe con stesso titolo e stesso livello (es. RSI2 e Micro-rimbalzo)."""
    gruppi = {}
    for x in livelli:
        chiave = (x["titolo"], x["tipo"], x["livello"])
        if chiave in gruppi:
            gruppi[chiave] = {**gruppi[chiave],
                              "strategia": gruppi[chiave]["strategia"] + " / " + x["strategia"]}
        else:
            gruppi[chiave] = dict(x)
    return list(gruppi.values())


# =====================================================================
# TELEGRAM
# =====================================================================
class Telegram:
    def __init__(self):
        self.token = os.getenv("TELEGRAM_TOKEN", "").strip()
        self.chat = os.getenv("TELEGRAM_CHAT_ID", "").strip()
        self.url = f"https://api.telegram.org/bot{self.token}"

    def _post(self, metodo, **dati):
        try:
            return requests.post(f"{self.url}/{metodo}", data=dati, timeout=20).json()
        except Exception as e:
            print(f"Telegram ({metodo}) non riuscito: {e}")
            return {}

    def invia(self, testo, pulsanti=None, codici=(), collegamenti=()):
        """
        codici:       testi da mostrare "a tocco per copiare" (es. ISIN)
        collegamenti: [(testo, url)] - la prima occorrenza del testo diventa un link
        Ogni simbolo di titolo nel messaggio (es. DHL.DE) diventa un link alla sua scheda,
        cosi' Telegram non lo scambia per un sito internet.
        """
        dati = {"chat_id": self.chat, "text": testo,
                "link_preview_options": json.dumps({"is_disabled": True})}
        entita, occupati = [], []

        def aggiungi(inizio, fine, **extra):
            if any(inizio < b and a < fine for a, b in occupati):
                return                                   # niente sovrapposizioni
            occupati.append((inizio, fine))
            entita.append({"offset": _u16(testo[:inizio]),
                           "length": _u16(testo[inizio:fine]), **extra})

        for codice in codici:
            pos = testo.find(codice) if codice else -1
            if pos >= 0:
                aggiungi(pos, pos + len(codice), type="code")
        for parola, url in collegamenti:
            pos = testo.find(parola) if parola else -1
            if pos >= 0:
                aggiungi(pos, pos + len(parola), type="text_link", url=url)
        for m in _regex_titoli().finditer(testo):
            simbolo, a, b = m.group(1), m.start(), m.end()
            tra_parentesi = testo[a - 1:a] == "(" and testo[b:b + 1] == ")"
            inizio_riga = a == 0 or testo[a - 1] == "\n"
            if "." in simbolo or tra_parentesi or inizio_riga:     # evita parole come "MA"
                aggiungi(a, b, type="text_link", url=scheda_titolo(simbolo))
        if entita:
            entita.sort(key=lambda e: e["offset"])
            dati["entities"] = json.dumps(entita)
        if pulsanti:
            dati["reply_markup"] = json.dumps({"inline_keyboard": [
                [{"text": t, "callback_data": d} for t, d in riga] for riga in pulsanti]})
        return self._post("sendMessage", **dati)

    def togli_pulsanti(self, message_id):
        self._post("editMessageReplyMarkup", chat_id=self.chat, message_id=message_id,
                   reply_markup=json.dumps({"inline_keyboard": []}))

    def rispondi_pulsante(self, callback_id, testo=""):
        self._post("answerCallbackQuery", callback_query_id=callback_id, text=testo)

    def aggiornamenti(self, offset, attesa=25):
        try:
            r = requests.get(f"{self.url}/getUpdates",
                             params={"offset": offset, "timeout": attesa},
                             timeout=attesa + 10).json()
        except Exception:
            time.sleep(5)
            return []
        if not r.get("ok"):
            time.sleep(10)          # token errato o rete assente: non insisto
            return []
        return r.get("result", [])


# =====================================================================
# MONITOR (un thread per mercato)
# =====================================================================
class Monitor(threading.Thread):
    """
    eventi: funzione chiamata con (tipo, dati) per aggiornare la finestra.
    impostazioni: funzione che restituisce il dizionario impostazioni attuale.
    """

    def __init__(self, mercato, archivio, impostazioni, eventi, telegram, fonte=None):
        super().__init__(daemon=True)
        self.mercato = mercato
        self.cfg = motore.MERCATI[mercato]
        self.archivio = archivio
        self.imp = impostazioni
        self.eventi = eventi
        self.tg = telegram
        self.fonte = fonte or (Finnhub() if self.cfg["fonte"] == "finnhub" else Yahoo())
        self.ferma = threading.Event()
        self.giorno = None
        self.dati_ind = {}
        self.livelli = {}                # titolo -> [livelli delle strategie attive]
        self.stat_titolo, self.stat_strategia = {}, {}
        self.fattori, self.trimestrali = {}, {}
        self.migliori, self.classifica = [], None
        self.filtro_blocca = False
        self.avvisati = set()
        self.ultimi_prezzi = {}
        self.ritardo = None
        self.stato = "In avvio..."
        self.file_avvisi = f"avvisi_oggi_{mercato}.json"

    # ---------- utilita' ----------
    @property
    def orizzonte(self):
        """Giorni entro cui misurare il +5% (classifica e statistica negli avvisi)."""
        return int(self.imp().get("orizzonte_classifica", 30))

    @property
    def solo_migliori(self):
        return bool(self.imp().get("solo_migliori"))

    @property
    def strategie(self):
        if self.solo_migliori and self.classifica is not None:
            return list(self.migliori)              # anche vuota: nessun pattern abbastanza buono
        nomi = self.imp()["strategie"][self.mercato]
        nomi = [nomi] if isinstance(nomi, str) else list(nomi)
        return [n for n in nomi if n in motore.NOMI_ATTIVABILI] or [motore.NOMI_ATTIVABILI[0]]

    @property
    def strategia(self):
        """Descrizione breve delle strategie attive (per stato e messaggi)."""
        nomi = self.strategie
        if not nomi:
            return "nessun pattern"
        return nomi[0] if len(nomi) == 1 else f"{len(nomi)} strategie"

    @property
    def v(self):
        return self.cfg["valuta"]

    def _oggi(self):
        return datetime.now(self.cfg["fuso"]).date()

    def _stato(self, testo):
        self.stato = testo
        self.eventi("stato", {"mercato": self.mercato, "testo": testo})

    def _log(self, testo):
        testo = f"[{self.mercato}] {testo}"
        print(testo)
        self.eventi("log", testo)

    def importo(self):
        imp = self.imp()
        return float(imp["capitale"]) / int(imp["max_posizioni"])

    def _carica_avvisati(self):
        """Avvisi gia' mandati oggi (se riapri l'app non te li rimanda)."""
        try:
            with open(self.file_avvisi, encoding="utf-8") as f:
                d = json.load(f)
            if d.get("giorno") == str(self.giorno):
                return {tuple(x) for x in d.get("avvisi", [])}
        except (FileNotFoundError, json.JSONDecodeError):
            pass
        return set()

    def _segna(self, chiave):
        self.avvisati.add(chiave)
        try:
            with open(self.file_avvisi, "w", encoding="utf-8") as f:
                json.dump({"giorno": str(self.giorno), "avvisi": sorted(self.avvisati)}, f)
        except OSError:
            pass

    def _nota_ritardo(self, q):
        r = q.get("ritardo")
        return f"  ⏱ prezzo di {r:.0f} min fa" if r is not None and r > RITARDO_AVVISO_MIN else ""

    # ---------- preparazione giornaliera ----------
    def prepara_giorno(self):
        self._stato("Scarico i dati e calcolo i livelli del giorno...")
        if self.v == "$":
            CAMBIO.aggiorna()
        dati = motore.scarica(self.mercato, forza=True)
        self.dati_ind = motore.prepara(dati, self.mercato)
        testo_classifica = ""
        if self.solo_migliori:
            self._stato("Calcolo la classifica dei pattern sugli ultimi 5 anni...")
            classifica, base = motore.classifica_pattern(self.dati_ind,
                                                         riferimento=self.orizzonte)
            quanti = int(self.imp().get("quanti_migliori", 3))
            self.migliori = motore.migliori_pattern(classifica, quanti)
            self.classifica = (classifica, base)
            self.eventi("classifica", {"mercato": self.mercato, "classifica": classifica,
                                       "base": base, "scelti": self.migliori,
                                       "orizzonte": self.orizzonte})
            testo_classifica = motore.testo_classifica(classifica, base, self.migliori,
                                                       self.mercato)
            if not self.migliori:
                testo_classifica += ("\n⚠️ Oggi nessun pattern supera la base in modo "
                                     "significativo: nessun segnale d'acquisto, controllo solo "
                                     "le posizioni aperte.")
        nomi = self.strategie
        self.livelli = motore.livelli_oggi(self.dati_ind, nomi)
        self.filtro_blocca = False
        if self.imp().get("filtro_mercato") and motore.indice_sopra_media(dati, self.mercato) is False:
            self.filtro_blocca = True
            self.livelli = {}
        self._stato("Calcolo le statistiche storiche dei pattern...")
        self.stat_titolo, self.stat_strategia = motore.statistiche_mercato(
            self.dati_ind, nomi, self.orizzonte)
        self.fattori = {tk: motore.fattori_titolo(ind) for tk, ind in self.dati_ind.items()}
        self.trimestrali = TRIMESTRALI.prossime(sorted(self.dati_ind))
        if not self.trimestrali and TRIMESTRALI.ultimo_errore:
            self._log(f"Date delle trimestrali non disponibili: {TRIMESTRALI.ultimo_errore}")
        try:
            n = self.archivio.aggiorna_esiti(self.mercato, self.dati_ind)
            if n:
                self._log(f"Diario dei segnali: aggiornato l'esito di {n} segnali.")
                self.eventi("diario", None)
        except Exception as e:
            self._log(f"Diario dei segnali: esiti non aggiornati ({e})")
        self.giorno = self._oggi()
        self.avvisati = self._carica_avvisati()
        self.eventi("livelli", {"mercato": self.mercato, "livelli": self.livelli})

        tutti = [x for elenco in self.livelli.values() for x in elenco]
        modo = " (solo i più performanti)" if self.solo_migliori else ""
        testo = [f"{self.cfg['bandiera']} PRO.SEGNALI {self.mercato}",
                 f"Pattern attivi{modo}: " + (", ".join(nomi) or "nessuno")]
        if self.filtro_blocca:
            testo.append(f"\n⛔ Filtro di mercato: l'indice {self.cfg['nome_benchmark']} è sotto "
                         "la sua media a 200 giorni. Oggi nessun nuovo acquisto, controllo solo "
                         "le posizioni aperte.")
        elif nomi:
            testo.append(f"Seduta {self.cfg['orario_italiano']}: {len(tutti)} segnali possibili "
                         f"su {len(self.livelli)} titoli. Ti scrivo appena uno scatta.")
            apertura = [x for x in tutti if x["tipo"] == "apertura"]
            if apertura:
                testo.append("\nDa comprare all'apertura: " +
                             ", ".join(f"{x['titolo']} ({x['strategia']})" for x in apertura))
            vicini = sorted(raggruppa(x for x in tutti if x["tipo"] != "apertura"),
                            key=lambda x: abs(x["distanza_pct"]))[:5]
            if vicini:
                testo.append("\nI più vicini al segnale:")
            for x in vicini:
                verso = "scende sotto" if x["tipo"] == "sotto" else "sale sopra"
                testo.append(f"• {motore.nome_titolo(x['titolo'])} ({x['titolo']}): se {verso} "
                             f"{con_euro(x['titolo'], x['livello'])} "
                             f"({pct(x['distanza_pct'])}) - {x['strategia']}")
        pos = self.archivio.posizioni(self.mercato)
        if pos:
            testo.append(f"\nPosizioni sotto controllo: {', '.join(p['titolo'] for p in pos)}")
        if testo_classifica:
            testo.append("\n" + testo_classifica)
        if datetime.now(self.cfg["fuso"]).weekday() < 5:
            self.tg.invia("\n".join(testo))
        self._log(f"Livelli calcolati: {len(tutti)} segnali possibili su {len(self.livelli)} "
                  f"titoli ({', '.join(nomi)})" + (" - FILTRO MERCATO ATTIVO" if self.filtro_blocca else ""))
        try:
            self._avvisi_trimestrali()
        except Exception as e:
            self._log(f"Avvisi trimestrali non inviati: {e}")

    def _avvisi_trimestrali(self):
        """Avviso per le posizioni aperte con i conti in uscita entro 3 giorni."""
        oggi = self._oggi()
        if oggi.weekday() >= 5:
            return
        for p in self.archivio.posizioni(self.mercato):
            tk = p["titolo"]
            gg = motore.giorni_a_trimestrale(self.trimestrali.get(tk), oggi)
            if gg is None or gg > 3 or (tk, "trimestrale") in self.avvisati:
                continue
            self._segna((tk, "trimestrale"))
            ind = self.dati_ind.get(tk)
            c = float(ind["Close"].iloc[-1]) if ind is not None and len(ind) else float(p["prezzo"])
            var = (c / float(p["prezzo"]) - 1) * 100
            d = self.trimestrali[tk]
            quando = "oggi" if gg == 0 else "domani" if gg == 1 else f"tra {gg} giorni"
            nome_soc, codice = identita(tk)
            self.tg.invia(
                f"📅 TRIMESTRALE IN ARRIVO · {nome_soc}  {self.cfg['bandiera']}\n{codice}\n"
                f"I conti escono il {d[8:10]}/{d[5:7]} ({quando}).\n"
                f"La tua posizione: {pct(var)} dall'acquisto (ultima chiusura {con_euro(tk, c)}).\n"
                "Nei giorni dei conti il prezzo può fare salti forti in tutte e due le direzioni e "
                "aprire già sotto lo stop. Valuta se vendere prima, alzare lo stop se sei in "
                "guadagno, oppure tenere sapendo il rischio.",
                [[("✅ Ho venduto", f"v|{tk}|{c:.2f}")]],
                codici=[motore.isin_titolo(tk)], collegamenti=[(nome_soc, scheda_titolo(tk))])
            self._log(f"AVVISO: trimestrale di {tk} il {d} ({quando})")

    def ricalcola_livelli(self):
        """Da chiamare se cambia la strategia."""
        self.giorno = None

    # ---------- valutazioni ----------
    def _minuti_da_apertura(self):
        adesso = datetime.now(self.cfg["fuso"])
        apertura = datetime.combine(adesso.date(), self.cfg["apertura"], adesso.tzinfo)
        return (adesso - apertura).total_seconds() / 60

    def valuta_entrata(self, tk, q):
        if (tk, "entrata") in self.avvisati:
            return
        c = q["c"]
        scattati = []
        for lv in self.livelli.get(tk, []):
            if lv["tipo"] == "sotto":
                ok = c <= lv["livello"]
            elif lv["tipo"] == "sopra":
                ok = c >= lv["livello"]
            else:                                   # apertura: solo nella prima mezz'ora
                ok = self._minuti_da_apertura() <= 30
            # regola del 5%: con il prezzo reale il pattern deve prevedere almeno +5%
            if ok and motore.ingresso_valido(lv["strategia"], c, lv["obiettivo"]):
                scattati.append(lv)
        if not scattati:
            return
        # tra i pattern scattati scelgo quello con il punteggio storico piu' alto
        gg_trim = motore.giorni_a_trimestrale(self.trimestrali.get(tk), self._oggi())
        for x in scattati:
            x["_punti"] = motore.punteggio_segnale(tk, x["strategia"], self.stat_titolo,
                                                   self.stat_strategia, self.fattori.get(tk),
                                                   gg_trim)[0]
        lv = max(scattati, key=lambda x: (x["_punti"], x["priorita"]))
        nome = lv["strategia"]
        self._segna((tk, "entrata"))
        soglia = int(self.imp().get("punteggio_minimo", motore.PUNTEGGIO_MINIMO))
        adesso = datetime.now(self.cfg["fuso"])
        try:
            self.archivio.registra_segnale({
                "data": adesso.date().isoformat(), "ora": adesso.strftime("%H:%M"),
                "mercato": self.mercato, "titolo": tk, "strategia": nome, "prezzo": float(c),
                "stop_pct": motore.STRATEGIE[nome]["stop_pct"], "punteggio": lv["_punti"],
                "soglia": soglia, "inviato": lv["_punti"] >= soglia})
            self.eventi("diario", None)
        except Exception as e:
            self._log(f"Diario dei segnali: non registrato ({e})")
        if lv["_punti"] < soglia:
            self._log(f"Scartato {tk} ({nome}): punteggio {lv['_punti']}/100 sotto la soglia {soglia}")
            return
        s = motore.STRATEGIE[nome]
        stop = c * (1 - s["stop_pct"])
        titolo_avviso = "🟢 COMPRA ORA (all'apertura)" if lv["tipo"] == "apertura" else "🟢 COMPRA"
        dettaglio = ("" if lv["tipo"] == "apertura"
                     else f"\nLivello: {con_euro(tk, lv['livello'])}")
        nome_soc, codice = identita(tk)
        righe = [f"{titolo_avviso} {nome_soc}  {self.cfg['bandiera']}", codice,
                 "📊 Tocca il nome per grafico, dati e notizie",
                 f"Prezzo ora: {con_euro(tk, c)}" + self._nota_ritardo(q) + dettaglio,
                 f"Stop loss: {con_euro(tk, stop)}  (-{s['stop_pct'] * 100:.0f}%)"]
        ob = motore.obiettivo_effettivo(nome, c, lv["obiettivo"])
        if ob:
            righe.append(f"Obiettivo: {con_euro(tk, ob)}  ({pct((ob / c - 1) * 100)})")
        if lv["trailing"]:
            righe.append(f"Uscita se scende sotto: {con_euro(tk, lv['trailing'])}")
        righe.append(f"Durata massima: {s['max_giorni']} giorni")
        righe.append(f"Importo suggerito: ~{self.importo():.0f} €")
        if e_usa(tk):
            cambio = CAMBIO.ottieni()
            righe.append("Su Trade Republic il prezzo è in euro: stop e obiettivo valgono in %"
                         + (f" (cambio 1 € = {fmt(cambio, 4)} $)." if cambio else "."))
        n, mx = len(self.archivio.posizioni()), int(self.imp()["max_posizioni"])
        if n >= mx:
            righe.append(f"⚠️ Hai già {n} posizioni aperte (massimo {mx}).")
        altri = [x["strategia"] for x in scattati if x is not lv]
        righe.append("")
        righe.append(motore.testo_statistica(tk, nome, self.mercato, self.stat_titolo,
                                             self.stat_strategia, orizzonte=self.orizzonte,
                                             fattori=self.fattori.get(tk),
                                             data_trim=self.trimestrali.get(tk),
                                             oggi=self._oggi()))
        righe.append("(stima sui dati degli ultimi 5 anni, non una garanzia)")
        if altri:
            righe.append("Scattati anche: " + ", ".join(altri))
        indice = motore.NOMI_STRATEGIE.index(nome)
        self.tg.invia("\n".join(righe), [[("✅ Ho comprato", f"c|{tk}|{c:.2f}|{indice}"),
                                           ("❌ Ignoro", f"x|{tk}")]],
                      codici=[motore.isin_titolo(tk)],
                      collegamenti=[(nome_soc, scheda_titolo(tk))])
        self.eventi("allarme", {"titolo": tk, "testo": f"COMPRA {tk} a {fmt(c)} {self.v} - {nome}"})
        self._log(f"AVVISO: COMPRA {tk} a {c:.2f} {self.v} ({nome}, punteggio {lv['_punti']})")

    def valuta_posizione(self, p, q):
        tk, c = p["titolo"], q["c"]
        rif = motore.riferimenti_posizione(self.dati_ind, p)
        if not rif:
            return
        acquisto_eur = motore.prezzo_acquisto_eur(p, cambio_per(tk))
        ora_eur = in_euro(tk, c)
        var = ((ora_eur / acquisto_eur - 1) if (ora_eur and acquisto_eur)
               else (c / p["prezzo"] - 1)) * 100
        motivo, icona, testo = None, "", ""
        if c <= rif["stop"]:
            motivo, icona = "stop", "🔴"
            testo = f"STOP LOSS toccato: {con_euro(tk, rif['stop'])}"
        elif rif["trailing"] and c <= rif["trailing"]:
            motivo, icona = "trailing", "🔴"
            testo = f"sceso sotto il minimo dei 10 giorni: {con_euro(tk, rif['trailing'])}"
        elif rif["obiettivo"] and c >= rif["obiettivo"]:
            motivo, icona = "obiettivo", "💰"
            testo = f"OBIETTIVO raggiunto: {con_euro(tk, rif['obiettivo'])}"
        else:
            adesso = datetime.now(self.cfg["fuso"])
            ultima_ora = (datetime.combine(adesso.date(), self.cfg["chiusura"])
                          - motore.pd.Timedelta(minutes=30)).time()
            if rif["giorni"] >= rif["max_giorni"] and adesso.time() >= ultima_ora:
                motivo, icona = "tempo", "⏰"
                chiusura_it = self.cfg["orario_italiano"].split("-")[1]
                testo = (f"tempo massimo raggiunto ({rif['max_giorni']} giorni): "
                         f"chiudi entro le {chiusura_it}")
        if not motivo or (tk, motivo) in self.avvisati:
            return
        self._segna((tk, motivo))
        guadagno = p["importo"] * var / 100 - 2 * float(self.imp()["commissione"])
        nome_soc, codice = identita(tk)
        acquisto = f"\nAcquisto: {fmt(acquisto_eur)} €" if acquisto_eur else ""
        self.tg.invia(f"{icona} VENDI {nome_soc}  {self.cfg['bandiera']}\n{codice}\n{testo}\n"
                      f"Prezzo ora: {con_euro(tk, c)}{self._nota_ritardo(q)}{acquisto}\n"
                      f"Risultato: {pct(var, 2)}  (~{guadagno:+.2f} € netti di commissioni)",
                      [[("✅ Ho venduto", f"v|{tk}|{c:.2f}")]],
                      codici=[motore.isin_titolo(tk)],
                      collegamenti=[(nome_soc, scheda_titolo(tk))])
        self.eventi("allarme", {"titolo": tk, "testo": f"VENDI {tk} ({motivo}) {pct(var, 2)}"})
        self._log(f"AVVISO: VENDI {tk} ({motivo}) a {c:.2f} {self.v}")

    # ---------- ciclo ----------
    def ciclo_prezzi(self):
        if self.v == "$":
            CAMBIO.aggiorna()
        posizioni = {p["titolo"]: p for p in self.archivio.posizioni(self.mercato)}
        osservati = sorted((t for t in self.livelli if t not in posizioni
                            and (t, "entrata") not in self.avvisati),
                           key=lambda t: min(abs(x["distanza_pct"]) for x in self.livelli[t]))
        elenco = list(posizioni) + osservati
        inizio, ricevuti, ritardi = time.time(), 0, []
        for tk, q in self.fonte.quotazioni(elenco, self.ferma):
            ricevuti += 1
            self.ultimi_prezzi[tk] = q["c"]
            if q.get("ritardo") is not None:
                ritardi.append(q["ritardo"])
            self.eventi("prezzo", {"titolo": tk, "prezzo": q["c"], "ritardo": q.get("ritardo")})
            try:
                posizioni = {p["titolo"]: p for p in self.archivio.posizioni(self.mercato)}
                if tk in posizioni:
                    self.valuta_posizione(posizioni[tk], q)
                elif tk in self.livelli:
                    self.valuta_entrata(tk, q)
            except Exception as e:
                self._log(f"Errore su {tk}: {e}")
        if self.ferma.is_set():
            return ricevuti
        if ricevuti and getattr(self, "_errore_segnalato", ""):
            self._log("Prezzi di nuovo disponibili.")
            self._errore_segnalato = ""
        self.ritardo = sorted(ritardi)[len(ritardi) // 2] if ritardi else None
        info_rit = f", ritardo prezzi ~{self.ritardo:.0f} min" if self.ritardo is not None else ""
        self._stato(f"Aperto - ultimo giro {datetime.now():%H:%M}: {ricevuti}/{len(elenco)} "
                    f"prezzi in {time.time() - inizio:.0f} s{info_rit}")
        return ricevuti

    def run(self):
        self._log("Monitoraggio avviato.")
        while not self.ferma.is_set():
            try:
                if self.giorno != self._oggi() or not self.dati_ind:
                    self.prepara_giorno()
                if self.fonte.mercato_aperto():
                    if not self.ciclo_prezzi():
                        errore = getattr(self.fonte, "ultimo_errore", "")
                        pausa = getattr(self.fonte, "pausa", 60)
                        self._stato(f"Aperto, ma nessun prezzo ricevuto. Riprovo tra "
                                    f"{pausa // 60:.0f} min." + (f" Motivo: {errore}" if errore else ""))
                        if errore and errore != getattr(self, "_errore_segnalato", ""):
                            self._log(f"Prezzi non ricevuti: {errore}")
                            self._errore_segnalato = errore
                        if not hasattr(self.fonte, "pausa"):
                            self.ferma.wait(60)
                else:
                    self._stato(f"Chiuso - seduta {self.cfg['orario_italiano']} "
                                "(ora italiana, giorni feriali)")
                    self.ferma.wait(60)
            except Exception as e:
                self._log(f"Errore nel monitoraggio: {e} - riprovo tra 1 minuto")
                self.ferma.wait(60)
        self._stato("Spento")
        self._log("Monitoraggio fermato.")


# =====================================================================
# ASCOLTO COMANDI TELEGRAM (thread)
# =====================================================================
AIUTO = ("Comandi disponibili:\n"
         "/stato - cosa sta facendo il programma\n"
         "/posizioni - le tue posizioni aperte\n"
         "/livelli - i titoli più vicini al segnale\n"
         "/comprato TITOLO PREZZO [IMPORTO] - registra o corregge un acquisto\n"
         "   es. /comprato AAPL 295,40 1000   oppure   /comprato ENI.MI 15,42\n"
         "/venduto TITOLO PREZZO - registra una vendita (o corregge quella appena fatta)\n"
         "/storico - ultime operazioni chiuse\n"
         "/monitora pc  oppure  /monitora telefono - dove fare il monitoraggio\n"
         "/ferma - ferma il monitoraggio su tutti i dispositivi\n\n"
         "Prezzi e importi sempre in EURO, come su Trade Republic (anche per i titoli USA: "
         "la conversione la faccio io). I titoli europei hanno il suffisso di borsa: "
         ".MI, .DE, .PA... Quando ricevi un avviso puoi usare direttamente i pulsanti.")


class Ascolto(threading.Thread):
    def __init__(self, archivio, impostazioni, eventi, telegram, monitor_attivi):
        super().__init__(daemon=True)
        self.archivio, self.imp, self.eventi, self.tg = archivio, impostazioni, eventi, telegram
        self.monitor_attivi = monitor_attivi        # funzione: {mercato: Monitor}
        self.ferma = threading.Event()
        self.offset = None

    def run(self):
        vecchi = self.tg.aggiornamenti(-1, attesa=0)        # salto i messaggi arrivati a programma chiuso
        self.offset = vecchi[-1]["update_id"] + 1 if vecchi else None
        while not self.ferma.is_set():
            for agg in self.tg.aggiornamenti(self.offset):
                self.offset = agg["update_id"] + 1
                try:
                    self.gestisci(agg)
                except Exception as e:
                    print("Errore comando Telegram:", e)

    def _dal_mio_chat(self, chat_id):
        return str(chat_id) == str(self.tg.chat)

    def _importo(self):
        return float(self.imp()["capitale"]) / int(self.imp()["max_posizioni"])

    def _strategia(self, titolo, indice=None):
        if indice is not None and 0 <= indice < len(motore.NOMI_STRATEGIE):
            return motore.NOMI_STRATEGIE[indice]
        nomi = self.imp()["strategie"][motore.mercato_di(titolo)]
        return nomi if isinstance(nomi, str) else nomi[0]

    @staticmethod
    def _v(titolo):
        return motore.MERCATI[motore.mercato_di(titolo)]["valuta"]

    def gestisci(self, agg):
        post = agg.get("channel_post")
        if post:                                     # canale di sincronia
            testo = (post.get("text") or "").strip().lower()
            if testo.startswith("/collega"):
                canale = post["chat"]["id"]
                self.eventi("collega_canale", str(canale))
                try:
                    requests.post(f"{self.tg.url}/sendMessage", timeout=20, data={
                        "chat_id": canale, "text": f"✅ Canale collegato al PC.\nID del canale: "
                        f"{canale}\nInserisci questo ID nelle impostazioni dell'app sul telefono."})
                except Exception:
                    pass
            return
        if "callback_query" in agg:
            cq = agg["callback_query"]
            msg = cq.get("message", {})
            if not self._dal_mio_chat(msg.get("chat", {}).get("id")):
                return
            parti = cq.get("data", "").split("|")
            self.tg.togli_pulsanti(msg.get("message_id"))
            if parti[0] == "c" and len(parti) in (3, 4):
                indice = int(parti[3]) if len(parti) == 4 else None
                try:
                    p, _ = self.archivio.aggiungi(parti[1], self._strategia(parti[1], indice),
                                                  float(parti[2]), self._importo(),
                                                  cambio=cambio_per(parti[1]))
                except ValueError as e:
                    self.tg.rispondi_pulsante(cq["id"], "Non registrato")
                    self.tg.invia(f"⚠️ Non ho potuto registrare l'acquisto ({e}). Riprova tra "
                                  f"qualche minuto con: /comprato {parti[1]} PREZZO_IN_EURO")
                    return
                self.tg.rispondi_pulsante(cq["id"], "Acquisto registrato")
                self.tg.invia(f"✅ Registrato: {motore.nome_titolo(p['titolo'])} ({p['titolo']}) "
                              f"a {fmt(p['prezzo_eur'])} €, ~{p['importo']:.0f} €.\n"
                              "Ti avviso io per stop e obiettivo.\n"
                              "Se su Trade Republic hai pagato un prezzo diverso, scrivi: "
                              f"/comprato {p['titolo']} PREZZO_IN_EURO")
            elif parti[0] == "v" and len(parti) == 3:
                self._vendita(parti[1], prezzo=float(parti[2]))
                self.tg.rispondi_pulsante(cq["id"], "Vendita registrata")
            else:
                self.tg.rispondi_pulsante(cq["id"], "Ok, ignorato")
            self.eventi("posizioni", None)
            return

        msg = agg.get("message") or {}
        if not self._dal_mio_chat(msg.get("chat", {}).get("id")):
            return
        testo = (msg.get("text") or "").strip()
        if not testo:
            return
        cmd, *arg = testo.split()
        cmd = cmd.lower().split("@")[0]
        if cmd in ("/start", "/aiuto", "/help"):
            self.tg.invia(AIUTO)
        elif cmd == "/monitora" and arg and arg[0].lower() in ("pc", "telefono"):
            dove = arg[0].lower()
            self.eventi("comando_monitor", dove)
            self.tg.invia(f"👍 Monitoraggio spostato su: {dove.upper()}. "
                          + ("Parte entro pochi secondi." if dove == "pc" else
                             "Parte quando il telefono si sincronizza (entro un minuto circa)."))
        elif cmd == "/ferma":
            self.eventi("comando_monitor", "")
            self.tg.invia("⏸ Monitoraggio fermato su tutti i dispositivi.")
        elif cmd == "/stato":
            self._stato()
        elif cmd == "/posizioni":
            self._posizioni()
        elif cmd == "/livelli":
            self._livelli()
        elif cmd == "/storico":
            self._storico()
        elif cmd == "/comprato" and len(arg) >= 2:
            try:
                prezzo = float(arg[1].replace(",", "."))
                importo = float(arg[2].replace(",", ".")) if len(arg) > 2 else None
            except ValueError:
                self.tg.invia("Formato: /comprato AAPL 295,40 1000  (prezzo e importo in euro)")
                return
            titolo = arg[0].upper()
            gia = any(p["titolo"] == titolo for p in self.archivio.posizioni())
            if importo is None and not gia:
                importo = self._importo()
            try:
                p, nuova = self.archivio.aggiungi_eur(titolo, self._strategia(titolo), prezzo,
                                                      importo, cambio=cambio_per(titolo))
            except ValueError as e:
                self.tg.invia(f"⚠️ Non registrato: {e}. Riprova tra qualche minuto.")
                return
            extra = f" (≈ {fmt(p['prezzo'])} $)" if e_usa(titolo) else ""
            self.tg.invia(("✅ Registrato " if nuova else "✏️ Corretto ") +
                          f"{motore.nome_titolo(titolo)} ({p['titolo']}) a "
                          f"{fmt(p['prezzo_eur'])} €{extra}, ~{p['importo']:.0f} €.")
            self.eventi("posizioni", None)
        elif cmd == "/venduto" and len(arg) >= 2:
            try:
                self._vendita(arg[0], prezzo_eur=float(arg[1].replace(",", ".")))
            except ValueError:
                self.tg.invia("Formato: /venduto AAPL 305,10  (prezzo in euro)")
            self.eventi("posizioni", None)
        else:
            self.tg.invia("Non ho capito. " + AIUTO)

    def _vendita(self, titolo, prezzo=None, prezzo_eur=None):
        aperta = any(p["titolo"] == titolo.upper() for p in self.archivio.posizioni())
        if not aperta and prezzo_eur is not None:          # correzione di una vendita gia' fatta
            r = self.archivio.ultima_vendita(titolo)
            if r:
                r = self.archivio.modifica_operazione(r["id"], prezzo_vendita_eur=prezzo_eur)
                self.tg.invia(f"✏️ Vendita corretta: {motore.nome_titolo(r['titolo'])} a "
                              f"{fmt(r['prezzo_vendita_eur'])} € - {pct(r['rend_lordo_pct'], 2)} "
                              f"(~{r['netto_eur_stimato']:+.2f} € netti di commissioni)")
                return
        try:
            rec = self.archivio.chiudi(titolo, prezzo, cambio=cambio_per(titolo),
                                       prezzo_vendita_eur=prezzo_eur)
        except ValueError as e:
            self.tg.invia(f"⚠️ Vendita non registrata: {e}. Riprova tra qualche minuto.")
            return
        if rec:
            self.tg.invia(f"✅ Vendita registrata: {motore.nome_titolo(rec['titolo'])} a "
                          f"{fmt(rec['prezzo_vendita_eur'])} € - {pct(rec['rend_lordo_pct'], 2)} "
                          f"(~{rec['netto_eur_stimato']:+.2f} € netti di commissioni)\n"
                          "Se su Trade Republic il prezzo di vendita era diverso, scrivi: "
                          f"/venduto {rec['titolo']} PREZZO_IN_EURO")
        else:
            self.tg.invia(f"Non trovo {titolo.upper()} tra le posizioni aperte.")

    def _stato(self):
        attivi = self.monitor_attivi()
        if not attivi:
            self.tg.invia("⚪ Monitoraggio spento. Avvialo dall'app sul PC.")
            return
        righe = []
        for nome, m in attivi.items():
            cfg = motore.MERCATI[nome]
            righe.append(f"{cfg['bandiera']} {nome} ({', '.join(m.strategie)}): {m.stato}")
        self.tg.invia("\n".join(righe))

    def _posizioni(self):
        pos = self.archivio.posizioni()
        if not pos:
            self.tg.invia("Nessuna posizione aperta.")
            return
        attivi = self.monitor_attivi()
        righe = ["Posizioni aperte:"]
        for p in pos:
            m = attivi.get(p["mercato"])
            tk = p["titolo"]
            ora = m.ultimi_prezzi.get(tk) if m else None
            rif = motore.riferimenti_posizione(m.dati_ind if m else {}, p)
            acq = motore.prezzo_acquisto_eur(p, cambio_per(tk))

            def eur(x):
                e = in_euro(tk, x) if x else None
                return f"{fmt(e)} €" if e else "n.d."

            r = f"• {motore.nome_titolo(tk)} ({tk}) acq. {fmt(acq) if acq else 'n.d.'} €"
            ora_e = in_euro(tk, ora) if ora else None
            if ora_e and acq:
                r += f" | ora {fmt(ora_e)} € ({pct((ora_e / acq - 1) * 100, 2)})"
            if rif:
                r += f" | stop {eur(rif['stop'])}"
                if rif["obiettivo"]:
                    r += f" | obiettivo {eur(rif['obiettivo'])}"
                r += f" | giorno {rif['giorni']}/{rif['max_giorni']}"
            righe.append(r)
        self.tg.invia("\n".join(righe))

    def _livelli(self):
        tutti = []
        for m in self.monitor_attivi().values():
            for elenco in m.livelli.values():
                for x in elenco:
                    tutti.append((x, m))
        if not tutti:
            self.tg.invia("Livelli non ancora calcolati: avvia il monitoraggio dall'app.")
            return

        def distanza(coppia):
            x, m = coppia
            ora = m.ultimi_prezzi.get(x["titolo"])
            return abs(x["livello"] / ora - 1) if ora else abs(x["distanza_pct"]) / 100

        per_mercato = {}
        for x, m in tutti:
            per_mercato.setdefault(id(m), (m, []))[1].append(x)
        tutti = [(x, m) for m, el in per_mercato.values() for x in raggruppa(el)]
        righe = ["Titoli più vicini al segnale:"]
        for x, m in sorted(tutti, key=distanza)[:12]:
            ora = m.ultimi_prezzi.get(x["titolo"])
            if x["tipo"] == "apertura":
                righe.append(f"• {x['titolo']} da comprare all'apertura - {x['strategia']}")
                continue
            verso = "↓ sotto" if x["tipo"] == "sotto" else "↑ sopra"
            r = f"• {motore.nome_titolo(x['titolo'])} ({x['titolo']}) {verso} {con_euro(x['titolo'], x['livello'])}"
            if ora:
                r += f" - manca {pct((x['livello'] / ora - 1) * 100)}"
            righe.append(r + f" - {x['strategia']}")
        self.tg.invia("\n".join(righe))

    def _storico(self):
        tutto = self.archivio.storico()
        if not tutto:
            self.tg.invia("Nessuna operazione chiusa.")
            return
        tot = sum(r["netto_eur_stimato"] for r in tutto)
        self.tg.invia("Ultime operazioni:\n" + "\n".join(
            f"• {r['titolo']} {r['data_vendita']}: {pct(r['rend_lordo_pct'], 2)} "
            f"({r['netto_eur_stimato']:+.2f} €)" for r in tutto[-10:])
            + f"\n\nTotale stimato: {tot:+.2f} € (prima delle tasse)")
