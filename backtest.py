"""
PRO.BOT - Backtest strategie swing su azioni USA
-------------------------------------------------
Testa 3 strategie classiche su un paniere di azioni USA molto liquide e
calcola i risultati AL NETTO di commissioni, spread e tasse (stima).

Logica di esecuzione (come la useresti tu):
  - il segnale si calcola sui prezzi di chiusura (sera, ora italiana)
  - l'acquisto/vendita avviene il giorno dopo all'apertura USA (15:30 ora italiana)
  - lo stop loss scatta durante la giornata se il prezzo lo tocca

Uso:  python backtest.py
Output: tabella a video, file CSV con tutte le operazioni, riepilogo su Telegram.
"""
import os
import pickle
import sys
from datetime import datetime, timedelta

import numpy as np
import pandas as pd
import requests
import yfinance as yf
from dotenv import load_dotenv

# =====================================================================
# PARAMETRI (modificabili)
# =====================================================================
CAPITALE_EUR = 3000          # capitale dedicato all'attività
MAX_POSIZIONI = 3            # posizioni aperte contemporaneamente
COMMISSIONE_EUR = 1.0        # Trade Republic: 1 euro per ordine
SPREAD_PCT = 0.10            # costo spread+slippage andata e ritorno, in %
ALIQUOTA_TASSE = 0.26        # tassazione plusvalenze Italia

INIZIO_DATI = "2014-01-01"   # serve storico per la media a 200 giorni
INIZIO_TEST_LUNGO = "2016-01-01"
ANNI_TEST_RECENTE = 3

# Paniere: grandi aziende USA molto liquide.
# ATTENZIONE: sono le big di OGGI -> il passato risulta un po' migliore del reale
# (survivorship bias). Va tenuto presente leggendo i risultati.
TITOLI = [
    "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "JPM", "V",
    "MA", "UNH", "XOM", "JNJ", "PG", "HD", "COST", "LLY", "ABBV", "MRK",
    "PEP", "KO", "WMT", "BAC", "CVX", "ORCL", "CRM", "AMD", "ADBE", "NFLX",
    "CSCO", "INTC", "QCOM", "TXN", "IBM", "CAT", "GE", "HON", "BA", "DIS",
    "NKE", "MCD", "SBUX", "LOW", "GS", "MS", "AMGN", "TMO", "DHR", "NEE",
]
BENCHMARK = "SPY"            # indice S&P 500, per confronto "compra e tieni"
CACHE_FILE = "dati_cache.pkl"


# =====================================================================
# DATI
# =====================================================================
def scarica_dati(forza=False, titoli=None, benchmark=None, cache=None):
    """Scarica i prezzi giornalieri (con cache di 12 ore). Senza parametri: mercato USA."""
    titoli = titoli or TITOLI
    benchmark = benchmark or BENCHMARK
    cache = cache or CACHE_FILE
    if not forza and os.path.exists(cache):
        eta = datetime.now() - datetime.fromtimestamp(os.path.getmtime(cache))
        if eta < timedelta(hours=12):
            with open(cache, "rb") as f:
                print("Uso dati in cache.")
                return pickle.load(f)

    tutti = list(titoli) + [benchmark]
    print(f"Scarico i dati di {len(tutti)} titoli (1-2 minuti)...")
    raw = yf.download(tutti, start=INIZIO_DATI, auto_adjust=True,
                      group_by="ticker", progress=False, threads=True)
    dati = {}
    for tk in tutti:
        try:
            df = raw[tk][["Open", "High", "Low", "Close", "Volume"]]
        except KeyError:
            continue
        df = df.dropna(subset=["Open", "High", "Low", "Close"])
        if len(df) > 250:
            dati[tk] = df
    mancanti = set(tutti) - set(dati)
    if mancanti:
        print("Titoli non scaricati:", ", ".join(sorted(mancanti)))
    with open(cache, "wb") as f:
        pickle.dump(dati, f)
    return dati


# =====================================================================
# INDICATORI
# =====================================================================
def rsi(close, n):
    delta = close.diff()
    guadagni = delta.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
    perdite = (-delta.clip(upper=0)).ewm(alpha=1 / n, adjust=False).mean()
    rs = guadagni / perdite.replace(0, np.nan)
    return 100 - 100 / (1 + rs)


def aggiungi_indicatori(df):
    df = df.copy()
    c = df["Close"]
    df["SMA5"] = c.rolling(5).mean()
    df["SMA20"] = c.rolling(20).mean()
    df["SMA200"] = c.rolling(200).mean()
    df["RSI2"] = rsi(c, 2)
    dev = c.rolling(20).std()
    df["BB_LOW"] = df["SMA20"] - 2 * dev
    df["MAX20"] = df["High"].rolling(20).max().shift(1)   # massimo dei 20 gg PRECEDENTI
    df["MIN10"] = df["Low"].rolling(10).min().shift(1)
    df["VOL20"] = df["Volume"].rolling(20).mean()
    return df


# =====================================================================
# STRATEGIE
# Ogni strategia definisce:
#   entrata(r)       -> True se a fine giornata c'e' segnale d'acquisto
#   priorita(r)      -> per scegliere tra piu' segnali (piu' alto = meglio)
#   uscita(r, giorni)-> True se a fine giornata bisogna vendere
#   stop_pct         -> stop loss sotto il prezzo d'acquisto
# r e' la riga del giorno (Series) con prezzi e indicatori.
# =====================================================================
STRATEGIE = {
    "RSI2 rimbalzo": {
        "descrizione": "Titolo in trend rialzista (sopra media 200gg) con forte "
                       "ipervenduto di breve (RSI a 2 gg sotto 10). Vende al recupero "
                       "sopra la media a 5 gg o dopo 7 giorni.",
        "entrata": lambda r: r.Close > r.SMA200 and r.RSI2 < 10,
        "priorita": lambda r: -r.RSI2,
        "uscita": lambda r, g: r.Close > r.SMA5 or g >= 7,
        "stop_pct": 0.08,
    },
    "Breakout 20gg": {
        "descrizione": "Chiusura sopra il massimo degli ultimi 20 gg, in trend "
                       "rialzista e con volumi 1,5x la media. Vende sotto il minimo "
                       "di 10 gg o dopo 20 giorni.",
        "entrata": lambda r: (r.Close > r.MAX20 and r.Close > r.SMA200
                              and r.Volume > 1.5 * r.VOL20),
        "priorita": lambda r: r.Volume / r.VOL20,
        "uscita": lambda r, g: r.Close < r.MIN10 or g >= 20,
        "stop_pct": 0.07,
    },
    "Bollinger in trend": {
        "descrizione": "Titolo sopra la media 200gg che chiude sotto la banda di "
                       "Bollinger inferiore. Vende al ritorno sulla media 20 gg o "
                       "dopo 10 giorni.",
        "entrata": lambda r: r.Close > r.SMA200 and r.Close < r.BB_LOW,
        "priorita": lambda r: (r.BB_LOW - r.Close) / r.Close,
        "uscita": lambda r, g: r.Close > r.SMA20 or g >= 10,
        "stop_pct": 0.08,
    },
}


# =====================================================================
# SIMULAZIONE
# =====================================================================
def simula(dati_ind, strategia, inizio):
    """Simula giorno per giorno con max MAX_POSIZIONI posizioni a importo fisso."""
    importo = CAPITALE_EUR / MAX_POSIZIONI
    date = sorted(set().union(*[df.index for df in dati_ind.values()]))
    date = [d for d in date if d >= pd.Timestamp(inizio)]

    posizioni = {}          # tk -> dict(prezzo, data, idx, stop)
    uscite_pendenti = set()
    candidati = []          # [(priorita, tk)] calcolati alla chiusura precedente
    operazioni = []

    def chiudi(tk, prezzo, data, motivo):
        p = posizioni.pop(tk)
        lordo = prezzo / p["prezzo"] - 1
        netto_eur = (importo * lordo - 2 * COMMISSIONE_EUR
                     - importo * SPREAD_PCT / 100)
        operazioni.append({
            "titolo": tk, "data_acquisto": p["data"].date(), "data_vendita": data.date(),
            "prezzo_acquisto": round(p["prezzo"], 2), "prezzo_vendita": round(prezzo, 2),
            "giorni": p["giorni"], "rend_lordo_pct": round(lordo * 100, 2),
            "netto_eur": round(netto_eur, 2), "motivo": motivo,
        })

    for data in date:
        # 1) vendite decise ieri sera -> all'apertura di oggi
        for tk in list(uscite_pendenti):
            df = dati_ind[tk]
            if data in df.index and tk in posizioni:
                chiudi(tk, df.at[data, "Open"], data, "segnale")
                uscite_pendenti.discard(tk)

        # 2) acquisti decisi ieri sera -> all'apertura di oggi
        for _, tk in sorted(candidati, reverse=True):
            if len(posizioni) >= MAX_POSIZIONI:
                break
            df = dati_ind[tk]
            if tk in posizioni or data not in df.index:
                continue
            prezzo = df.at[data, "Open"]
            posizioni[tk] = {"prezzo": prezzo, "data": data, "giorni": 0,
                             "stop": prezzo * (1 - strategia["stop_pct"])}
        candidati = []

        # 3) stop loss durante la giornata
        for tk in list(posizioni):
            df = dati_ind[tk]
            if data not in df.index:
                continue
            p = posizioni[tk]
            p["giorni"] += 1
            if df.at[data, "Low"] <= p["stop"]:
                prezzo = min(df.at[data, "Open"], p["stop"])  # se apre gia' sotto: prezzo d'apertura
                chiudi(tk, prezzo, data, "stop loss")
                uscite_pendenti.discard(tk)

        # 4) a fine giornata: segnali di uscita e di entrata per domani
        for tk, p in posizioni.items():
            df = dati_ind[tk]
            if data in df.index and strategia["uscita"](df.loc[data], p["giorni"]):
                uscite_pendenti.add(tk)
        for tk, df in dati_ind.items():
            if tk in posizioni or data not in df.index:
                continue
            r = df.loc[data]
            if r.isna().any():
                continue
            if strategia["entrata"](r):
                candidati.append((strategia["priorita"](r), tk))

    return pd.DataFrame(operazioni)


# =====================================================================
# METRICHE
# =====================================================================
def metriche(ops, inizio, fine):
    anni = max((fine - pd.Timestamp(inizio)).days / 365.25, 0.1)
    if ops.empty:
        return None
    ops = ops[pd.to_datetime(ops["data_acquisto"]) >= pd.Timestamp(inizio)]
    if ops.empty:
        return None
    netto = ops["netto_eur"]
    vinti, persi = netto[netto > 0], netto[netto <= 0]
    totale = netto.sum()
    tasse = max(totale, 0) * ALIQUOTA_TASSE          # stima semplificata
    equity = netto.cumsum()
    drawdown = (equity - equity.cummax().clip(lower=0)).min()
    return {
        "operazioni": len(ops),
        "operazioni_anno": round(len(ops) / anni, 1),
        "vincenti_pct": round(len(vinti) / len(ops) * 100, 1),
        "media_op_eur": round(netto.mean(), 2),
        "profit_factor": round(vinti.sum() / abs(persi.sum()), 2) if persi.sum() else float("inf"),
        "netto_eur": round(totale, 0),
        "dopo_tasse_eur": round(totale - tasse, 0),
        "annuo_pct": round((totale - tasse) / CAPITALE_EUR / anni * 100, 1),
        "max_perdita_eur": round(drawdown, 0),
        "giorni_medi": round(ops["giorni"].mean(), 1),
    }


def benchmark(dati, inizio, fine, simbolo=None):
    spy = dati[simbolo or BENCHMARK]["Close"]
    spy = spy[spy.index >= pd.Timestamp(inizio)]
    anni = max((fine - pd.Timestamp(inizio)).days / 365.25, 0.1)
    rend = spy.iloc[-1] / spy.iloc[0] - 1
    lordo = CAPITALE_EUR * rend - 2 * COMMISSIONE_EUR
    netto = lordo - max(lordo, 0) * ALIQUOTA_TASSE
    return round(netto, 0), round(netto / CAPITALE_EUR / anni * 100, 1)


def invia_telegram(testo):
    load_dotenv()
    token, chat = os.getenv("TELEGRAM_TOKEN"), os.getenv("TELEGRAM_CHAT_ID")
    if not token or not chat:
        return
    try:
        requests.post(f"https://api.telegram.org/bot{token}/sendMessage",
                      data={"chat_id": chat, "text": testo}, timeout=15)
    except Exception as e:
        print("Invio Telegram non riuscito:", e)


# =====================================================================
# MAIN
# =====================================================================
def main(dati=None):
    if dati is None:
        dati = scarica_dati(forza="--aggiorna" in sys.argv)
    if BENCHMARK not in dati:
        sys.exit("Dati del benchmark non disponibili.")
    titoli = {tk: aggiungi_indicatori(df) for tk, df in dati.items() if tk != BENCHMARK}
    fine = max(df.index[-1] for df in titoli.values())
    inizio_recente = (fine - pd.DateOffset(years=ANNI_TEST_RECENTE)).strftime("%Y-%m-%d")

    periodi = {f"Ultimi {ANNI_TEST_RECENTE} anni": inizio_recente,
               f"Dal {INIZIO_TEST_LUNGO[:4]}": INIZIO_TEST_LUNGO}
    righe, tutte_ops = [], []

    for nome, s in STRATEGIE.items():
        print(f"Simulo: {nome}...")
        ops = simula(titoli, s, INIZIO_TEST_LUNGO)
        if not ops.empty:
            ops.insert(0, "strategia", nome)
            tutte_ops.append(ops)
        for periodo, inizio in periodi.items():
            m = metriche(ops, inizio, fine)
            if m:
                righe.append({"strategia": nome, "periodo": periodo, **m})

    for periodo, inizio in periodi.items():
        netto, annuo = benchmark(dati, inizio, fine)
        righe.append({"strategia": "Compra e tieni S&P500", "periodo": periodo,
                      "dopo_tasse_eur": netto, "annuo_pct": annuo})

    ris = pd.DataFrame(righe)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 20)
    print("\n" + "=" * 100)
    print(f"RISULTATI - capitale {CAPITALE_EUR} EUR, max {MAX_POSIZIONI} posizioni, "
          f"{COMMISSIONE_EUR} EUR/ordine, spread {SPREAD_PCT}%")
    print("=" * 100)
    for periodo in periodi:
        print(f"\n--- {periodo} ---")
        print(ris[ris["periodo"] == periodo].drop(columns="periodo").to_string(index=False))

    stamp = datetime.now().strftime("%Y%m%d_%H%M")
    ris.to_csv(f"risultati_{stamp}.csv", index=False, sep=";", decimal=",")
    if tutte_ops:
        pd.concat(tutte_ops).to_csv(f"operazioni_{stamp}.csv", index=False, sep=";", decimal=",")
    print(f"\nSalvati: risultati_{stamp}.csv e operazioni_{stamp}.csv (si aprono con Excel)")

    # riepilogo Telegram
    rec = ris[ris["periodo"] == f"Ultimi {ANNI_TEST_RECENTE} anni"]
    msg = [f"Backtest ultimi {ANNI_TEST_RECENTE} anni (capitale {CAPITALE_EUR} EUR, dopo tasse):"]
    for _, r in rec.iterrows():
        extra = ""
        if pd.notna(r.get("operazioni")):
            extra = f" | {int(r['operazioni'])} op, {r['vincenti_pct']}% vincenti"
        msg.append(f"- {r['strategia']}: {r['dopo_tasse_eur']:+.0f} EUR ({r['annuo_pct']}%/anno){extra}")
    invia_telegram("\n".join(msg))
    return ris


if __name__ == "__main__":
    main()
