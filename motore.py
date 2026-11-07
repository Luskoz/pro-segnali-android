"""
PRO.SEGNALI - motore a livelli
-------------------------------
Per ogni titolo calcola, a partire dalle chiusure fino a ieri, il PREZZO DI
ATTIVAZIONE: il livello che, se toccato durante la giornata, fa scattare il
segnale. Durante la seduta basta confrontare il prezzo in tempo reale con il
livello. Lo stesso calcolo e' usato nel backtest, quindi i test sul passato
rispecchiano quello che succede dal vivo.
"""
import json
import os
import threading
from datetime import date, datetime, time as ora_t
from zoneinfo import ZoneInfo

import math
import numpy as np
import pandas as pd

import backtest as bt

NY = ZoneInfo("America/New_York")
ROMA = ZoneInfo("Europe/Rome")
GUADAGNO_MINIMO = 0.05       # obiettivo minimo +5%: sotto non vale la pena (costi e rischio)

# Grandi societa' europee (simboli Yahoo) + principali italiane del FTSE MIB.
# Se qualche simbolo non viene scaricato, il programma lo segnala e lo salta.
TITOLI_EUROPA = [
    # Paesi Bassi / Belgio / Finlandia
    "ASML.AS", "PRX.AS", "INGA.AS", "AD.AS", "ADYEN.AS", "WKL.AS", "ABI.BR", "NOKIA.HE",
    # Germania
    "SAP.DE", "SIE.DE", "ALV.DE", "DTE.DE", "MUV2.DE", "DB1.DE", "ADS.DE", "BAS.DE",
    "BAYN.DE", "MBG.DE", "BMW.DE", "IFX.DE", "DHL.DE", "RHM.DE", "VOW3.DE", "ENR.DE",
    # Francia
    "MC.PA", "TTE.PA", "SAN.PA", "OR.PA", "AIR.PA", "SU.PA", "BNP.PA", "AI.PA", "EL.PA",
    "CS.PA", "SAF.PA", "RMS.PA", "DG.PA", "BN.PA", "SGO.PA",
    # Spagna
    "IBE.MC", "ITX.MC", "SAN.MC", "BBVA.MC",
    # Italia
    "UCG.MI", "ISP.MI", "ENEL.MI", "RACE.MI", "ENI.MI", "STLAM.MI", "G.MI", "PST.MI",
    "MB.MI", "BAMI.MI", "MONC.MI", "PRY.MI", "LDO.MI", "TIT.MI", "SRG.MI", "TRN.MI",
    "FBK.MI", "BMPS.MI", "CPR.MI", "TEN.MI", "STMMI.MI",
]

# Nome della societa' e ISIN di ogni titolo (per trovarlo su Trade Republic).
ANAGRAFICA = {
    # ---- USA ----
    "AAPL": ("Apple", "US0378331005"), "MSFT": ("Microsoft", "US5949181045"),
    "NVDA": ("NVIDIA", "US67066G1040"), "AMZN": ("Amazon", "US0231351067"),
    "GOOGL": ("Alphabet (classe A)", "US02079K3059"), "META": ("Meta Platforms", "US30303M1027"),
    "TSLA": ("Tesla", "US88160R1014"), "AVGO": ("Broadcom", "US11135F1012"),
    "JPM": ("JPMorgan Chase", "US46625H1005"), "V": ("Visa", "US92826C8394"),
    "MA": ("Mastercard", "US57636Q1040"), "UNH": ("UnitedHealth", "US91324P1021"),
    "XOM": ("Exxon Mobil", "US30231G1022"), "JNJ": ("Johnson & Johnson", "US4781601046"),
    "PG": ("Procter & Gamble", "US7427181091"), "HD": ("Home Depot", "US4370761029"),
    "COST": ("Costco", "US22160K1051"), "LLY": ("Eli Lilly", "US5324571083"),
    "ABBV": ("AbbVie", "US00287Y1091"), "MRK": ("Merck & Co.", "US58933Y1055"),
    "PEP": ("PepsiCo", "US7134481081"), "KO": ("Coca-Cola", "US1912161007"),
    "WMT": ("Walmart", "US9311421039"), "BAC": ("Bank of America", "US0605051046"),
    "CVX": ("Chevron", "US1667641005"), "ORCL": ("Oracle", "US68389X1054"),
    "CRM": ("Salesforce", "US79466L3024"), "AMD": ("Advanced Micro Devices", "US0079031078"),
    "ADBE": ("Adobe", "US00724F1012"), "NFLX": ("Netflix", "US64110L1061"),
    "CSCO": ("Cisco Systems", "US17275R1023"), "INTC": ("Intel", "US4581401001"),
    "QCOM": ("Qualcomm", "US7475251036"), "TXN": ("Texas Instruments", "US8825081040"),
    "IBM": ("IBM", "US4592001014"), "CAT": ("Caterpillar", "US1491231015"),
    "GE": ("GE Aerospace", "US3696043013"), "HON": ("Honeywell", "US4385161066"),
    "BA": ("Boeing", "US0970231058"), "DIS": ("Walt Disney", "US2546871060"),
    "NKE": ("Nike", "US6541061031"), "MCD": ("McDonald's", "US5801351017"),
    "SBUX": ("Starbucks", "US8552441094"), "LOW": ("Lowe's", "US5486611073"),
    "GS": ("Goldman Sachs", "US38141G1040"), "MS": ("Morgan Stanley", "US6174464486"),
    "AMGN": ("Amgen", "US0311621009"), "TMO": ("Thermo Fisher Scientific", "US8835561023"),
    "DHR": ("Danaher", "US2358511028"), "NEE": ("NextEra Energy", "US65339F1012"),
    # ---- Europa ----
    "ASML.AS": ("ASML Holding", "NL0010273215"), "PRX.AS": ("Prosus", "NL0013654783"),
    "INGA.AS": ("ING Groep", "NL0011821202"), "AD.AS": ("Ahold Delhaize", "NL0011794037"),
    "ADYEN.AS": ("Adyen", "NL0012969182"), "WKL.AS": ("Wolters Kluwer", "NL0000395903"),
    "ABI.BR": ("Anheuser-Busch InBev", "BE0974293251"), "NOKIA.HE": ("Nokia", "FI0009000681"),
    "SAP.DE": ("SAP", "DE0007164600"), "SIE.DE": ("Siemens", "DE0007236101"),
    "ALV.DE": ("Allianz", "DE0008404005"), "DTE.DE": ("Deutsche Telekom", "DE0005557508"),
    "MUV2.DE": ("Munich Re", "DE0008430026"), "DB1.DE": ("Deutsche Börse", "DE0005810055"),
    "ADS.DE": ("adidas", "DE000A1EWWW0"), "BAS.DE": ("BASF", "DE000BASF111"),
    "BAYN.DE": ("Bayer", "DE000BAY0017"), "MBG.DE": ("Mercedes-Benz Group", "DE0007100000"),
    "BMW.DE": ("BMW", "DE0005190003"), "IFX.DE": ("Infineon Technologies", "DE0006231004"),
    "DHL.DE": ("DHL Group", "DE0005552004"), "RHM.DE": ("Rheinmetall", "DE0007030009"),
    "VOW3.DE": ("Volkswagen (privilegiate)", "DE0007664039"),
    "ENR.DE": ("Siemens Energy", "DE000ENER6Y0"),
    "MC.PA": ("LVMH", "FR0000121014"), "TTE.PA": ("TotalEnergies", "FR0000120271"),
    "SAN.PA": ("Sanofi", "FR0000120578"), "OR.PA": ("L'Oréal", "FR0000120321"),
    "AIR.PA": ("Airbus", "NL0000235190"), "SU.PA": ("Schneider Electric", "FR0000121972"),
    "BNP.PA": ("BNP Paribas", "FR0000131104"), "AI.PA": ("Air Liquide", "FR0000120073"),
    "EL.PA": ("EssilorLuxottica", "FR0000121667"), "CS.PA": ("AXA", "FR0000120628"),
    "SAF.PA": ("Safran", "FR0000073272"), "RMS.PA": ("Hermès", "FR0000052292"),
    "DG.PA": ("Vinci", "FR0000125486"), "BN.PA": ("Danone", "FR0000120644"),
    "SGO.PA": ("Saint-Gobain", "FR0000125007"),
    "IBE.MC": ("Iberdrola", "ES0144580Y14"), "ITX.MC": ("Inditex", "ES0148396007"),
    "SAN.MC": ("Banco Santander", "ES0113900J37"), "BBVA.MC": ("BBVA", "ES0113211835"),
    "UCG.MI": ("UniCredit", "IT0005239360"), "ISP.MI": ("Intesa Sanpaolo", "IT0000072618"),
    "ENEL.MI": ("Enel", "IT0003128367"), "RACE.MI": ("Ferrari", "NL0011585146"),
    "ENI.MI": ("Eni", "IT0003132476"), "STLAM.MI": ("Stellantis", "NL00150001Q9"),
    "G.MI": ("Generali", "IT0000062072"), "PST.MI": ("Poste Italiane", "IT0003796171"),
    "MB.MI": ("Mediobanca", "IT0000062957"), "BAMI.MI": ("Banco BPM", "IT0005218380"),
    "MONC.MI": ("Moncler", "IT0004965148"), "PRY.MI": ("Prysmian", "IT0004176001"),
    "LDO.MI": ("Leonardo", "IT0003856405"), "TIT.MI": ("Telecom Italia", "IT0003497168"),
    "SRG.MI": ("Snam", "IT0003153415"), "TRN.MI": ("Terna", "IT0003242622"),
    "FBK.MI": ("FinecoBank", "IT0000072170"), "BMPS.MI": ("Banca Monte dei Paschi", "IT0005508921"),
    "CPR.MI": ("Campari", "NL0015435975"), "TEN.MI": ("Tenaris", "LU2598331598"),
    "STMMI.MI": ("STMicroelectronics", "NL0000226223"),
}


def nome_titolo(titolo):
    return ANAGRAFICA.get(titolo, (titolo, ""))[0]


def isin_titolo(titolo):
    return ANAGRAFICA.get(titolo, ("", ""))[1]


MERCATI = {
    "USA": {"titoli": bt.TITOLI, "benchmark": "SPY", "nome_benchmark": "S&P500",
            "fuso": NY, "apertura": ora_t(9, 30), "chiusura": ora_t(16, 0),
            "valuta": "$", "bandiera": "🇺🇸", "cache": "dati_cache.pkl", "fonte": "finnhub",
            "orario_italiano": "15:30-22:00"},
    "Europa": {"titoli": TITOLI_EUROPA, "benchmark": "^STOXX50E", "nome_benchmark": "Euro Stoxx 50",
               "fuso": ROMA, "apertura": ora_t(9, 0), "chiusura": ora_t(17, 30),
               "valuta": "€", "bandiera": "🇪🇺", "cache": "dati_cache_europa.pkl", "fonte": "yahoo",
               "orario_italiano": "9:00-17:30"},
}


def mercato_di(titolo):
    """I simboli europei hanno il suffisso di borsa (.MI, .DE, .PA...)."""
    return "Europa" if "." in titolo else "USA"


def scarica(mercato, forza=False):
    m = MERCATI[mercato]
    return bt.scarica_dati(forza=forza, titoli=m["titoli"], benchmark=m["benchmark"],
                           cache=m["cache"])


# =====================================================================
# INDICATORI
# =====================================================================
def indicatori(df):
    df = df.copy()
    c, h, l = df["Close"], df["High"], df["Low"]
    df["SMA5"] = c.rolling(5).mean()
    df["SMA20"] = c.rolling(20).mean()
    df["SMA50"] = c.rolling(50).mean()
    df["SMA200"] = c.rolling(200).mean()
    d = c.diff()
    df["AVG_G"] = d.clip(lower=0).ewm(alpha=0.5, adjust=False).mean()     # RSI a 2 periodi
    df["AVG_L"] = (-d.clip(upper=0)).ewm(alpha=0.5, adjust=False).mean()
    df["RSI2"] = 100 - 100 / (1 + df["AVG_G"] / df["AVG_L"].replace(0, np.nan))
    df["MAX20"] = h.rolling(20).max()
    df["MAX252"] = h.rolling(252).max()
    df["MIN10"] = l.rolling(10).min()
    df["MIN20"] = l.rolling(20).min()
    escursione = h - l
    df["NR7"] = escursione <= escursione.rolling(7).min()
    df["IBS"] = (c - l) / escursione.replace(0, np.nan)
    return df


# =====================================================================
# LIVELLI DELLE STRATEGIE
# Ogni funzione riceve il DataFrame con indicatori e restituisce, per ogni
# riga t, i livelli da usare il GIORNO DOPO (t+1):
#   livello  -> prezzo di attivazione dell'acquisto (NaN = nessun segnale)
#               per il tipo "apertura" e' solo un segnale: si compra all'apertura
#   obiettivo-> prezzo di vendita in guadagno (NaN = non previsto)
#   trailing -> vendi se il prezzo scende sotto (NaN = non previsto)
#   priorita -> per scegliere tra piu' segnali (piu' alto = meglio)
# =====================================================================
def _uscita(df, livello, obiettivo=np.nan, trailing=np.nan, priorita=0.0):
    out = pd.DataFrame(index=df.index)
    out["livello"] = livello
    out["obiettivo"] = obiettivo
    out["trailing"] = trailing
    out["priorita"] = priorita
    return out


def livello_rsi2(df):
    c, g, l = df["Close"], df["AVG_G"], df["AVG_L"]
    # prezzo P per cui l'RSI a 2 giorni scende sotto 10:  P < C + L - 9G
    livello = np.minimum(c, c + l - 9 * g)
    return livello.where((c > df["SMA200"]) & (livello > 0))


def livelli_rsi2(df):
    liv = livello_rsi2(df)
    return _uscita(df, liv, obiettivo=df["SMA5"], priorita=df["SMA5"] / liv - 1)


def livelli_micro(df):
    liv = livello_rsi2(df)
    return _uscita(df, liv, priorita=df["SMA5"] / liv - 1)     # obiettivo fisso +1%


def livelli_bollinger(df):
    c = df["Close"].to_numpy(dtype=float)
    n = len(c)
    livello = np.full(n, np.nan)
    if n >= 19:
        finestre = np.lib.stride_tricks.sliding_window_view(c, 19)   # ultime 19 chiusure
        s, q = finestre.sum(axis=1), (finestre ** 2).sum(axis=1)
        chiusura = c[18:]

        def distanza(p):   # p - banda inferiore calcolata includendo p come 20a chiusura
            m = (s + p) / 20
            var = np.maximum((q + p ** 2 - 20 * m ** 2) / 19, 0)
            return p - (m - 2 * np.sqrt(var))

        lo, hi = chiusura * 0.5, chiusura.copy()
        gia_sotto = distanza(hi) <= 0
        for _ in range(45):                       # bisezione vettoriale
            mid = (lo + hi) / 2
            sotto = distanza(mid) <= 0
            lo = np.where(sotto, mid, lo)
            hi = np.where(sotto, hi, mid)
        livello[18:] = np.where(gia_sotto, chiusura, lo)
    liv = pd.Series(livello, index=df.index).where(df["Close"] > df["SMA200"])
    return _uscita(df, liv, obiettivo=df["SMA20"], priorita=df["SMA20"] / liv - 1)


def livelli_breakout(df):
    liv = df["MAX20"].where(df["Close"] > df["SMA200"])
    return _uscita(df, liv, trailing=df["MIN10"], priorita=-(liv / df["Close"] - 1))


def livelli_calo(df):
    c = df["Close"]
    liv = (c * 0.97).where((c > df["SMA200"]) & (c > df["SMA50"]))
    return _uscita(df, liv, obiettivo=c, priorita=c / df["SMA200"] - 1)


def livelli_ibs(df):
    c = df["Close"]
    segnale = (df["IBS"] < 0.15) & (c > df["SMA200"]) & (c < df["SMA5"])
    return _uscita(df, c.where(segnale), obiettivo=df["High"], priorita=-df["IBS"])


def livelli_nr7(df):
    c = df["Close"]
    segnale = df["NR7"] & (c > df["SMA50"]) & (df["SMA50"] > df["SMA200"])
    liv = df["High"].where(segnale)
    return _uscita(df, liv, trailing=df["Low"], priorita=-(liv / c - 1))


def livelli_massimi(df):
    c = df["Close"]
    liv = df["MAX252"].where((c > df["SMA50"]) & (df["SMA50"] > df["SMA200"]))
    return _uscita(df, liv, trailing=df["MIN20"], priorita=-(liv / c - 1))


def livelli_pullback(df):
    c, m20 = df["Close"], df["SMA20"]
    segnale = (c > m20) & (m20 > df["SMA50"]) & (df["SMA50"] > df["SMA200"])
    liv = m20.where(segnale)
    return _uscita(df, liv, obiettivo=df["MAX20"], priorita=df["MAX20"] / liv - 1)


STRATEGIE = {
    "RSI2 rimbalzo": {
        "tipo": "sotto", "stop_pct": 0.08, "max_giorni": 7, "livelli": livelli_rsi2,
        "descrizione": "titolo in trend (sopra media 200 gg) che scende al prezzo che porta l'RSI "
                       "a 2 giorni sotto 10; vende sulla media a 5 gg, stop -8%, max 7 giorni.",
    },
    "Micro-rimbalzo +1%": {
        "tipo": "sotto", "stop_pct": 0.03, "max_giorni": 3, "livelli": livelli_micro,
        "obiettivo_pct": 0.01, "disattivato": True,
        "descrizione": "DISATTIVATO (obiettivo +1%, sotto il minimo del 5%). Stesso ingresso "
                       "di RSI2 ma vende a +1%, stop -3%, max 3 giorni.",
    },
    "Bollinger in trend": {
        "tipo": "sotto", "stop_pct": 0.08, "max_giorni": 10, "livelli": livelli_bollinger,
        "descrizione": "titolo in trend che scende sotto la banda di Bollinger inferiore; vende "
                       "sulla media a 20 gg, stop -8%, max 10 giorni.",
    },
    "Calo improvviso -3%": {
        "tipo": "sotto", "stop_pct": 0.05, "max_giorni": 3, "livelli": livelli_calo,
        "disattivato": True,
        "descrizione": "DISATTIVATO (punta a recuperare solo il 3%, sotto il minimo del 5%). "
                       "Titolo in trend che in giornata perde il 3%; vende alla chiusura di "
                       "ieri, stop -5%, max 3 giorni.",
    },
    "IBS rimbalzo": {
        "tipo": "apertura", "stop_pct": 0.05, "max_giorni": 4, "livelli": livelli_ibs,
        "descrizione": "titolo in trend che ieri ha chiuso sui minimi della giornata: compra "
                       "all'apertura, vende sopra il massimo di ieri, stop -5%, max 4 giorni.",
    },
    "Pullback media 20": {
        "tipo": "sotto", "stop_pct": 0.06, "max_giorni": 10, "livelli": livelli_pullback,
        "descrizione": "titolo in forte trend (medie 20>50>200) che ritorna sulla media a 20 gg; "
                       "vende sul massimo di 20 gg, stop -6%, max 10 giorni.",
    },
    "Breakout 20gg": {
        "tipo": "sopra", "stop_pct": 0.07, "max_giorni": 20, "livelli": livelli_breakout,
        "descrizione": "titolo in trend che supera il massimo di 20 giorni; vende sotto il minimo "
                       "di 10 gg, stop -7%, max 20 giorni.",
    },
    "NR7 breakout": {
        "tipo": "sopra", "stop_pct": 0.05, "max_giorni": 5, "livelli": livelli_nr7,
        "descrizione": "dopo la giornata più 'stretta' degli ultimi 7, compra alla rottura del suo "
                       "massimo; vende sotto il minimo del giorno prima, stop -5%, max 5 giorni.",
    },
    "Massimi 52 settimane": {
        "tipo": "sopra", "stop_pct": 0.08, "max_giorni": 30, "livelli": livelli_massimi,
        "descrizione": "titolo in trend che rompe il massimo dell'ultimo anno (momentum); vende "
                       "sotto il minimo di 20 gg, stop -8%, max 30 giorni.",
    },
}
NOMI_STRATEGIE = list(STRATEGIE)                 # elenco completo (non cambiare l'ordine)
NOMI_ATTIVABILI = [n for n, s in STRATEGIE.items() if not s.get("disattivato")]


def rialzo_atteso(nome, prezzo, obiettivo_livello):
    """Rialzo che il pattern si aspetta da quel prezzo (None se il pattern non ha un obiettivo,
    come i breakout che escono sul minimo)."""
    s = STRATEGIE[nome]
    if s.get("obiettivo_pct"):
        return s["obiettivo_pct"]
    if obiettivo_livello is None or pd.isna(obiettivo_livello) or not prezzo:
        return None
    return float(obiettivo_livello) / float(prezzo) - 1


def ingresso_valido(nome, prezzo, obiettivo_livello):
    """Regola del 5%: scarta i segnali per cui il pattern prevede meno di +5%
    e i pattern disattivati."""
    if STRATEGIE[nome].get("disattivato"):
        return False
    r = rialzo_atteso(nome, prezzo, obiettivo_livello)
    return r is None or r >= GUADAGNO_MINIMO - 1e-9


def obiettivo_effettivo(nome, prezzo_acquisto, obiettivo_livello):
    """Prezzo di vendita in guadagno per una posizione (None se la strategia non lo prevede)."""
    s = STRATEGIE[nome]
    minimo = prezzo_acquisto * (1 + GUADAGNO_MINIMO)          # mai sotto +5%
    if s.get("obiettivo_pct"):
        return max(prezzo_acquisto * (1 + s["obiettivo_pct"]), minimo)
    if obiettivo_livello is None or pd.isna(obiettivo_livello):
        return None
    return max(float(obiettivo_livello), minimo)


def filtro_indice(df_indice):
    """True nei giorni in cui l'indice e' sopra la sua media a 200 giorni."""
    c = df_indice["Close"]
    return c > c.rolling(200).mean()


# =====================================================================
# BACKTEST CON INGRESSI INTRADAY
# Ipotesi prudenti (non sappiamo l'ordine dei movimenti nella giornata):
#  - acquisto al livello (o all'apertura se il titolo apre gia' oltre)
#  - per gli acquisti "sotto" e "apertura", se nello stesso giorno il minimo
#    tocca lo stop, si conta la perdita
#  - lo stop si controlla prima dell'obiettivo; l'obiettivo non vale il giorno d'acquisto
# =====================================================================
def simula(dati, nomi, inizio, capitale, max_pos, commissione, spread_pct, filtro=None):
    if isinstance(nomi, str):
        nomi = [nomi]
    importo = capitale / max_pos
    idx = pd.DatetimeIndex(sorted(set().union(*[df.index for df in dati.values()])))
    A = {}
    for tk, df in dati.items():
        ind = indicatori(df)
        base = ind[["Open", "High", "Low", "Close"]].reindex(idx)
        a = {k: base[k].to_numpy(dtype=float) for k in base.columns}
        for nome in nomi:
            lv = STRATEGIE[nome]["livelli"](ind)
            if filtro is not None:
                ok = filtro.reindex(lv.index).fillna(False).astype(bool)
                lv.loc[~ok, "livello"] = np.nan
            lv = lv.shift(1).reindex(idx)                     # livelli di ieri valgono oggi
            a[nome] = {k: lv[k].to_numpy(dtype=float) for k in lv.columns}
        A[tk] = a
    partenza = int(np.searchsorted(idx.values, np.datetime64(pd.Timestamp(inizio))))
    posizioni, operazioni = {}, []

    def chiudi(tk, prezzo, i, motivo):
        p = posizioni.pop(tk)
        lordo = prezzo / p["prezzo"] - 1
        operazioni.append({
            "titolo": tk, "strategia": p["nome"], "data_acquisto": idx[p["i"]].date(),
            "data_vendita": idx[i].date(), "prezzo_acquisto": round(p["prezzo"], 2),
            "prezzo_vendita": round(prezzo, 2), "giorni": p["giorni"],
            "rend_lordo_pct": round(lordo * 100, 2),
            "netto_eur": round(importo * lordo - 2 * commissione - importo * spread_pct / 100, 2),
            "motivo": motivo})

    for i in range(partenza, len(idx)):
        usciti = set()
        # ---- uscite ----
        for tk in list(posizioni):
            a, p = A[tk], posizioni[tk]
            o, h, l, c = a["Open"][i], a["High"][i], a["Low"][i], a["Close"][i]
            if np.isnan(o):
                continue
            p["giorni"] += 1
            s, lv = STRATEGIE[p["nome"]], a[p["nome"]]
            obiettivo = obiettivo_effettivo(p["nome"], p["prezzo"], lv["obiettivo"][i])
            trailing = lv["trailing"][i]
            if o <= p["stop"]:
                chiudi(tk, o, i, "stop loss")
            elif l <= p["stop"]:
                chiudi(tk, p["stop"], i, "stop loss")
            elif not np.isnan(trailing) and l <= trailing:
                chiudi(tk, min(o, trailing), i, "uscita sul minimo")
            elif obiettivo is not None and h >= obiettivo:
                chiudi(tk, max(o, obiettivo), i, "obiettivo")
            elif p["giorni"] >= s["max_giorni"]:
                chiudi(tk, c, i, "tempo massimo")
            else:
                continue
            usciti.add(tk)
        # ---- entrate ----
        if len(posizioni) >= max_pos:
            continue
        candidati = {}
        for nome in nomi:
            tipo = STRATEGIE[nome]["tipo"]
            for tk, a in A.items():
                if tk in posizioni or tk in usciti:
                    continue
                livello, o = a[nome]["livello"][i], a["Open"][i]
                if np.isnan(livello) or np.isnan(o):
                    continue
                if tipo == "sotto" and a["Low"][i] <= livello:
                    prezzo = min(o, livello)
                elif tipo == "sopra" and a["High"][i] >= livello:
                    prezzo = max(o, livello)
                elif tipo == "apertura":
                    prezzo = o
                else:
                    continue
                if not ingresso_valido(nome, prezzo, a[nome]["obiettivo"][i]):
                    continue
                pr = np.nan_to_num(a[nome]["priorita"][i], nan=-1e9)
                if tk not in candidati or pr > candidati[tk][0]:
                    candidati[tk] = (pr, prezzo, nome)
        for tk, (_, prezzo, nome) in sorted(candidati.items(), key=lambda x: -x[1][0]):
            if len(posizioni) >= max_pos:
                break
            s = STRATEGIE[nome]
            stop = prezzo * (1 - s["stop_pct"])
            posizioni[tk] = {"prezzo": prezzo, "i": i, "giorni": 1, "stop": stop, "nome": nome}
            if s["tipo"] in ("sotto", "apertura") and A[tk]["Low"][i] <= stop:
                chiudi(tk, stop, i, "stop loss")
    return pd.DataFrame(operazioni)


def giudizio(m_rec, m_prima):
    if not m_rec or not m_prima:
        return "dati insufficienti"
    a, b = m_rec["annuo_pct"], m_prima["annuo_pct"]
    pf_a, pf_b = m_rec["profit_factor"], m_prima["profit_factor"]
    if a > 0 and b > 0 and pf_a >= 1.1 and pf_b >= 1.1:
        return "✓ solida"
    if a > 0 and b > 0:
        return "~ debole"
    if a > 0:
        return "⚠ solo recente"
    return "✗ da scartare"


def esegui_backtest(dati, imp, mercato="USA", combinazione=None):
    """
    Una riga per strategia con i risultati degli ultimi 3 anni e del periodo
    precedente (2016 -> 3 anni fa), piu' la combinazione scelta e l'indice.
    Restituisce (tabella, {strategia: operazioni}).
    """
    bt.CAPITALE_EUR = float(imp["capitale"])
    bench = MERCATI[mercato]["benchmark"]
    titoli = {tk: df for tk, df in dati.items() if tk != bench}
    filtro = filtro_indice(dati[bench]) if imp.get("filtro_mercato") and bench in dati else None
    fine = max(df.index[-1] for df in titoli.values())
    inizio_rec = fine - pd.DateOffset(years=bt.ANNI_TEST_RECENTE)
    inizio_lungo = pd.Timestamp(bt.INIZIO_TEST_LUNGO)

    def misura(ops, da, a):
        if ops.empty:
            return None
        sotto = ops[pd.to_datetime(ops["data_acquisto"]) < a]
        return bt.metriche(sotto, da, a)

    prove = [(n, [n]) for n in STRATEGIE]
    if combinazione and len(combinazione) > 1:
        prove.append((f"COMBINAZIONE ({len(combinazione)} strategie)", list(combinazione)))
    righe, operazioni = [], {}
    for etichetta, nomi in prove:
        print(f"Simulo: {etichetta}...")
        ops = simula(titoli, nomi, bt.INIZIO_TEST_LUNGO, float(imp["capitale"]),
                     int(imp["max_posizioni"]), float(imp["commissione"]), float(imp["spread"]),
                     filtro)
        operazioni[etichetta] = ops
        rec = misura(ops, inizio_rec, fine + pd.Timedelta(days=1))
        prima = misura(ops, inizio_lungo, inizio_rec)
        riga = {"strategia": etichetta, "giudizio": giudizio(rec, prima)}
        if rec:
            riga.update(op_anno=rec["operazioni_anno"], vincenti_pct=rec["vincenti_pct"],
                        media_op_eur=rec["media_op_eur"], pf_3a=rec["profit_factor"],
                        netto_3a=rec["dopo_tasse_eur"], annuo_3a=rec["annuo_pct"],
                        max_perdita=rec["max_perdita_eur"], giorni_medi=rec["giorni_medi"])
        if prima:
            riga.update(pf_prima=prima["profit_factor"], annuo_prima=prima["annuo_pct"])
        righe.append(riga)
    if bench in dati:
        netto, annuo = bt.benchmark(dati, inizio_rec.strftime("%Y-%m-%d"), fine, bench)
        _, annuo_prima = bt.benchmark({bench: dati[bench][dati[bench].index < inizio_rec]},
                                      bt.INIZIO_TEST_LUNGO, inizio_rec, bench)
        righe.append({"strategia": f"Compra e tieni {MERCATI[mercato]['nome_benchmark']}",
                      "netto_3a": netto, "annuo_3a": annuo, "annuo_prima": annuo_prima,
                      "giudizio": "riferimento"})
    print("Backtest completato.")
    return pd.DataFrame(righe), operazioni


# =====================================================================
# LIVELLI DAL VIVO
# =====================================================================
def solo_barre_complete(df, mercato="USA", adesso=None):
    """Durante la seduta Yahoo restituisce anche la barra di oggi, ancora parziale: la tolgo."""
    m = MERCATI[mercato]
    adesso = adesso or datetime.now(m["fuso"])
    fine_seduta = (datetime.combine(adesso.date(), m["chiusura"]) + pd.Timedelta(minutes=30)).time()
    if len(df) and df.index[-1].date() >= adesso.date() and adesso.time() < fine_seduta:
        return df.iloc[:-1]
    return df


def prepara(dati, mercato="USA", adesso=None):
    """Indicatori calcolati solo su barre complete, per tutti i titoli del mercato."""
    bench = MERCATI[mercato]["benchmark"]
    return {tk: indicatori(solo_barre_complete(df, mercato, adesso))
            for tk, df in dati.items() if tk != bench}


def indice_sopra_media(dati, mercato="USA", adesso=None):
    """Stato del filtro di mercato sull'ultima barra completa (None = indice non disponibile)."""
    bench = MERCATI[mercato]["benchmark"]
    if bench not in dati:
        return None
    serie = filtro_indice(solo_barre_complete(dati[bench], mercato, adesso))
    return bool(serie.iloc[-1]) if len(serie) else None


def livelli_oggi(dati_ind, nomi):
    """{titolo: [livello per ciascuna strategia attiva che ha un segnale]}"""
    if isinstance(nomi, str):
        nomi = [nomi]
    elenco = {}
    for tk, ind in dati_ind.items():
        if len(ind) < 260:
            continue
        chiusura = float(ind["Close"].iloc[-1])
        for nome in nomi:
            s = STRATEGIE[nome]
            r = s["livelli"](ind).iloc[-1]
            if pd.isna(r["livello"]):
                continue
            livello = float(r["livello"])
            prezzo_ingresso = chiusura if s["tipo"] == "apertura" else livello
            if not ingresso_valido(nome, prezzo_ingresso, r["obiettivo"]):
                continue
            elenco.setdefault(tk, []).append({
                "titolo": tk, "strategia": nome, "tipo": s["tipo"], "livello": round(livello, 2),
                "obiettivo": None if pd.isna(r["obiettivo"]) else float(r["obiettivo"]),
                "trailing": None if pd.isna(r["trailing"]) else float(r["trailing"]),
                "priorita": float(r["priorita"]) if pd.notna(r["priorita"]) else 0.0,
                "chiusura": round(chiusura, 2),
                "distanza_pct": 0.0 if s["tipo"] == "apertura"
                else round((livello / chiusura - 1) * 100, 2),
                "data": ind.index[-1].date().isoformat(),
            })
    return elenco


def riferimenti_posizione(dati_ind, p, oggi=None):
    """Stop, obiettivo, uscita sul minimo e giorni trascorsi per una posizione aperta."""
    s = STRATEGIE.get(p["strategia"])
    if s is None:
        return None
    oggi = oggi or datetime.now(MERCATI[mercato_di(p["titolo"])]["fuso"]).date()
    rif = {"stop": round(p["prezzo"] * (1 - s["stop_pct"]), 2), "obiettivo": None,
           "trailing": None, "max_giorni": s["max_giorni"],
           "giorni": int(np.busday_count(date.fromisoformat(p["data"]), oggi)) + 1}
    ind = dati_ind.get(p["titolo"])
    obiettivo_livello = None
    if ind is not None and len(ind) > 20:
        r = s["livelli"](ind).iloc[-1]
        obiettivo_livello = r["obiettivo"]
        if pd.notna(r["trailing"]):
            rif["trailing"] = round(float(r["trailing"]), 2)
    elif s["tipo"] != "sopra":
        obiettivo_livello = p["prezzo"] * (1 + GUADAGNO_MINIMO)
    ob = obiettivo_effettivo(p["strategia"], p["prezzo"], obiettivo_livello)
    rif["obiettivo"] = round(ob, 2) if ob else None
    return rif


# =====================================================================
# STATISTICHE STORICHE DEI PATTERN (per gli avvisi)
# =====================================================================
ANNI_STATISTICA = 5
SOGLIA_STATISTICA = 0.05       # +5%
ORIZZONTE_STATISTICA = 10      # giorni di borsa dopo il segnale


def statistica_pattern(ind, nome, anni=ANNI_STATISTICA, soglia=SOGLIA_STATISTICA,
                       orizzonte=ORIZZONTE_STATISTICA):
    """
    Negli ultimi `anni`: quante volte il pattern e' scattato su questo titolo e
    in quante il massimo dei `orizzonte` giorni successivi ha superato
    il prezzo d'ingresso di almeno `soglia`. Gli episodi ravvicinati
    (entro `orizzonte` giorni) contano una volta sola.
    Restituisce (casi, casi_oltre_soglia).
    """
    s = STRATEGIE[nome]
    lv = s["livelli"](ind).shift(1)
    o, h, l = (ind[k].to_numpy(dtype=float) for k in ("Open", "High", "Low"))
    liv = lv["livello"].to_numpy(dtype=float)
    obi = lv["obiettivo"].to_numpy(dtype=float)
    inizio = ind.index[-1] - pd.DateOffset(years=anni)
    i0 = int(np.searchsorted(ind.index.values, np.datetime64(inizio)))
    n = len(ind)
    casi = oltre = 0
    i = max(i0, 1)
    while i < n - orizzonte:
        prezzo = None
        if not np.isnan(liv[i]) and not np.isnan(o[i]):
            if s["tipo"] == "sotto" and l[i] <= liv[i]:
                prezzo = min(o[i], liv[i])
            elif s["tipo"] == "sopra" and h[i] >= liv[i]:
                prezzo = max(o[i], liv[i])
            elif s["tipo"] == "apertura":
                prezzo = o[i]
        if prezzo is None or not ingresso_valido(nome, prezzo, obi[i]):
            i += 1
            continue
        casi += 1
        if np.nanmax(h[i + 1:i + 1 + orizzonte]) >= prezzo * (1 + soglia):
            oltre += 1
        i += orizzonte                       # salto il resto dell'episodio
    return casi, oltre


# ---------------------------------------------------------------------
# CLASSIFICA DEI PATTERN (per il flag "solo pattern altamente performanti")
# Un caso e' un successo se il titolo sale di almeno +5% entro N giorni
# PRIMA di toccare lo stop del pattern (se nello stesso giorno tocca entrambi,
# conta come insuccesso: ipotesi prudente).
# ---------------------------------------------------------------------
ESCLUSI_DALLA_CLASSIFICA = {"Micro-rimbalzo +1%"}      # vende a +1%: non cerca rialzi del 5%
CASI_MINIMI_CLASSIFICA = 30
VANTAGGIO_MINIMO = 3.0          # punti percentuali sopra la base (sotto e' rumore statistico)


def _successi(h, l, i, prezzo, stop_pct, soglia, orizzonti):
    """Per ogni orizzonte: True se +soglia arriva prima dello stop entro quei giorni."""
    obiettivo, stop = prezzo * (1 + soglia), prezzo * (1 - stop_pct)
    esito = {n: False for n in orizzonti}
    if l[i] <= stop:                                   # stop gia' il giorno d'ingresso
        return esito
    for k in range(1, max(orizzonti) + 1):
        if i + k >= len(h):
            break
        if l[i + k] <= stop:
            break
        if h[i + k] >= obiettivo:
            for n in orizzonti:
                if k <= n:
                    esito[n] = True
            break
    return esito


ORIZZONTI_CLASSIFICA = (10, 20, 30)


def classifica_pattern(dati_ind, anni=ANNI_STATISTICA, soglia=SOGLIA_STATISTICA,
                       orizzonti=ORIZZONTI_CLASSIFICA, riferimento=30):
    """
    Per ogni pattern sul mercato: numero di casi negli ultimi `anni` e % di casi
    con +5% prima dello stop entro 10 e 20 giorni. Ogni pattern e' confrontato con
    la sua "base": la stessa misura comprando in giorni qualsiasi CON LO STESSO STOP.
    Restituisce (lista ordinata dal migliore al peggiore, base con stop -8% per riferimento).
    """
    lungo = max(orizzonti)
    stop_usati = sorted({s["stop_pct"] for s in STRATEGIE.values()} | {0.08})
    conteggi = {nome: {"casi": 0, **{n: 0 for n in orizzonti}} for nome in STRATEGIE}
    basi = {sp: {"casi": 0, **{n: 0 for n in orizzonti}} for sp in stop_usati}
    for tk, ind in dati_ind.items():
        if len(ind) < 300:
            continue
        o, h, l = (ind[k].to_numpy(dtype=float) for k in ("Open", "High", "Low"))
        inizio = ind.index[-1] - pd.DateOffset(years=anni)
        i0 = max(int(np.searchsorted(ind.index.values, np.datetime64(inizio))), 1)
        n = len(ind)
        for i in range(i0, n - lungo, 5):              # base: un ingresso ogni 5 giorni
            if np.isnan(o[i]):
                continue
            for sp, b in basi.items():
                es = _successi(h, l, i, o[i], sp, soglia, orizzonti)
                b["casi"] += 1
                for k in orizzonti:
                    b[k] += es[k]
        for nome, s in STRATEGIE.items():
            lv = s["livelli"](ind).shift(1)
            liv = lv["livello"].to_numpy(dtype=float)
            obi = lv["obiettivo"].to_numpy(dtype=float)
            c = conteggi[nome]
            i = i0
            while i < n - lungo:
                prezzo = None
                if not np.isnan(liv[i]) and not np.isnan(o[i]):
                    if s["tipo"] == "sotto" and l[i] <= liv[i]:
                        prezzo = min(o[i], liv[i])
                    elif s["tipo"] == "sopra" and h[i] >= liv[i]:
                        prezzo = max(o[i], liv[i])
                    elif s["tipo"] == "apertura":
                        prezzo = o[i]
                if prezzo is None or not ingresso_valido(nome, prezzo, obi[i]):
                    i += 1
                    continue
                es = _successi(h, l, i, prezzo, s["stop_pct"], soglia, orizzonti)
                c["casi"] += 1
                for k in orizzonti:
                    c[k] += es[k]
                i += 10                                # episodi ravvicinati contano una volta

    def perc(d, k):
        return round(d[k] / d["casi"] * 100, 1) if d["casi"] else 0.0

    righe = []
    for nome, d in conteggi.items():
        sp = STRATEGIE[nome]["stop_pct"]
        riga = {"pattern": nome, "casi": d["casi"], "stop_pct": sp,
                **{f"pct_{k}": perc(d, k) for k in orizzonti},
                **{f"base_{k}": perc(basi[sp], k) for k in orizzonti}}
        riga["orizzonte"] = riferimento
        riga["pct"], riga["base"] = riga[f"pct_{riferimento}"], riga[f"base_{riferimento}"]
        riga["vantaggio"] = round(riga["pct"] - riga["base"], 1)
        motivo = ""
        if STRATEGIE[nome].get("disattivato") or nome in ESCLUSI_DALLA_CLASSIFICA:
            motivo = "disattivato (obiettivo sotto il 5%)"
        elif d["casi"] < CASI_MINIMI_CLASSIFICA:
            motivo = f"pochi casi (<{CASI_MINIMI_CLASSIFICA})"
        elif riga["vantaggio"] < VANTAGGIO_MINIMO:
            motivo = ("non batte la base" if riga["vantaggio"] <= 0
                      else f"vantaggio troppo piccolo (<{VANTAGGIO_MINIMO:.0f} punti)")
        riga["idoneo"] = not motivo
        riga["nota"] = motivo
        righe.append(riga)
    righe.sort(key=lambda r: (r["idoneo"], r["vantaggio"], r["pct"]), reverse=True)
    return righe, {k: perc(basi[0.08], k) for k in orizzonti}


def migliori_pattern(classifica, quanti=3):
    """I `quanti` pattern idonei con la percentuale piu' alta (lista eventualmente vuota)."""
    return [r["pattern"] for r in classifica if r["idoneo"]][:quanti]


def testo_classifica(classifica, base_pct, scelti, mercato):
    righe = [f"🏆 Pattern più affidabili su {mercato} (ultimi {ANNI_STATISTICA} anni):",
             f"% di casi con +5% prima dello stop entro {classifica[0]['orizzonte'] if classifica else 30} "
             "giorni, contro la base con lo stesso stop (giorno qualsiasi)"]
    for r in classifica:
        segno = "✅" if r["pattern"] in scelti else "▫️"
        nota = f"  [{r['nota']}]" if r["nota"] else ""
        righe.append(f"{segno} {r['pattern']}: {r['pct']:.0f}% contro {r['base']:.0f}% "
                     f"({r['vantaggio']:+.0f} punti, {r['casi']} casi){nota}")
    return "\n".join(righe)


GIORNI_RIALZO = (7, 15, 30)          # rialzo medio a 7, 15 e 30 giorni di borsa
PESO_MERCATO = 10                    # "casi virtuali" del mercato aggiunti a quelli del titolo
PUNTEGGIO_MINIMO = 60                # soglia predefinita per ricevere un avviso


def statistica_completa(ind, nome, anni=ANNI_STATISTICA, soglia=SOGLIA_STATISTICA,
                        orizzonte=ORIZZONTE_STATISTICA):
    """
    Su un titolo, negli ultimi `anni`, ogni volta che il pattern e' scattato (episodi ravvicinati
    contati una volta): quante volte e' salito oltre +5% entro `orizzonte` giorni e la variazione
    del prezzo dopo 7, 15 e 30 giorni di borsa rispetto al prezzo d'ingresso.
    Restituisce le somme (si possono sommare tra titoli):
    {"casi", "oltre", "r7", "r15", "r30" (somme delle variazioni), "pos30" (casi positivi a 30 gg)}
    """
    s = STRATEGIE[nome]
    lv = s["livelli"](ind).shift(1)
    o, h, l, c = (ind[k].to_numpy(dtype=float) for k in ("Open", "High", "Low", "Close"))
    liv = lv["livello"].to_numpy(dtype=float)
    obi = lv["obiettivo"].to_numpy(dtype=float)
    inizio = ind.index[-1] - pd.DateOffset(years=anni)
    i0 = int(np.searchsorted(ind.index.values, np.datetime64(inizio)))
    n = len(ind)
    lungo = max(orizzonte, max(GIORNI_RIALZO))
    st = {"casi": 0, "oltre": 0, "r7": 0.0, "r15": 0.0, "r30": 0.0, "pos30": 0}
    i = max(i0, 1)
    while i < n - lungo:
        prezzo = None
        if not np.isnan(liv[i]) and not np.isnan(o[i]):
            if s["tipo"] == "sotto" and l[i] <= liv[i]:
                prezzo = min(o[i], liv[i])
            elif s["tipo"] == "sopra" and h[i] >= liv[i]:
                prezzo = max(o[i], liv[i])
            elif s["tipo"] == "apertura":
                prezzo = o[i]
        if prezzo is None or not ingresso_valido(nome, prezzo, obi[i]):
            i += 1
            continue
        st["casi"] += 1
        if np.nanmax(h[i + 1:i + 1 + orizzonte]) >= prezzo * (1 + soglia):
            st["oltre"] += 1
        for g in GIORNI_RIALZO:
            st[f"r{g}"] += c[i + g] / prezzo - 1
        st["pos30"] += int(c[i + 30] > prezzo)
        i += orizzonte                       # salto il resto dell'episodio
    return st


def somma_statistiche(elenco):
    tot = {"casi": 0, "oltre": 0, "r7": 0.0, "r15": 0.0, "r30": 0.0, "pos30": 0}
    for st in elenco:
        for k in tot:
            tot[k] += st[k]
    return tot


def statistiche_mercato(dati_ind, nomi, orizzonte=ORIZZONTE_STATISTICA):
    """{(titolo, strategia): statistica} e {strategia: statistica di tutto il mercato}."""
    per_titolo, per_strategia = {}, {}
    for nome in nomi:
        elenco = []
        for tk, ind in dati_ind.items():
            if len(ind) < 300:
                continue
            st = statistica_completa(ind, nome, orizzonte=orizzonte)
            per_titolo[(tk, nome)] = st
            elenco.append(st)
        per_strategia[nome] = somma_statistiche(elenco)
    return per_titolo, per_strategia


def _scala(x, zero, pieno):
    return min(max((x - zero) / (pieno - zero), 0.0), 1.0)


def punteggio(st_titolo, st_mercato):
    """
    Punteggio 0-100: quanto i dati passati sono favorevoli a un rialzo dopo questo pattern.
    I dati del titolo sono "mescolati" con quelli di tutto il mercato in proporzione ai casi
    (con pochi casi conta di piu' il mercato). Restituisce (punteggio, valori usati).
    """
    n = st_titolo["casi"] if st_titolo else 0
    nm = st_mercato["casi"] if st_mercato else 0

    def media(chiave):
        base = st_mercato[chiave] / nm if nm else 0.0
        if not n:
            return base
        return (st_titolo[chiave] + PESO_MERCATO * base) / (n + PESO_MERCATO)

    if not n and not nm:
        return 0, {}
    v = {k: media(k) for k in ("oltre", "r7", "r15", "r30", "pos30")}
    p = (0.40 * _scala(v["oltre"], 0.30, 0.80) + 0.25 * _scala(v["r30"], -0.02, 0.08)
         + 0.15 * _scala(v["r15"], -0.02, 0.06) + 0.10 * _scala(v["r7"], -0.02, 0.04)
         + 0.10 * _scala(v["pos30"], 0.40, 0.80))
    return int(math.floor(p * 100 + 0.5)), v


def punteggio_titolo(titolo, nome, per_titolo, per_strategia):
    st = per_titolo.get((titolo, nome))
    tot = per_strategia.get(nome)
    return punteggio(st if st and st["casi"] else None, tot if tot and tot["casi"] else None)[0]


# ---------------------------------------------------------------------
# PUNTEGGIO 2 (novembre 2026): storico del pattern + volatilita' + distanza dai
# massimi + trimestrale in arrivo. Coefficienti stimati su ~14.000 segnali
# storici dei 113 titoli (2019-2026) e verificati fuori campione (2022-2026).
# ---------------------------------------------------------------------
MODELLO_P5 = (1.9062, 0.1999, 0.5007, -0.8193, 0.1848)    # +5% prima dello stop
MODELLO_P10 = (2.28, 0.687, 0.9928, -1.6832, 0.2752)      # +10% prima dello stop
GIORNI_TRIM = 42                                          # ~30 giorni di borsa


def fattori_titolo(ind):
    """Volatilita' (ATR 14 giorni in % del prezzo) e distanza dal massimo a 52 settimane,
    calcolate sull'ultima barra completa. None se i dati non bastano."""
    h, l, c = (ind[k].to_numpy(dtype=float) for k in ("High", "Low", "Close"))
    n = len(c)
    if n < 253:
        return None
    somma = 0.0
    for i in range(n - 14, n):
        somma += max(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))
    massimo = -math.inf
    for i in range(n - 252, n):
        if not math.isnan(h[i]):
            massimo = max(massimo, h[i])
    atr = somma / 14 / c[-1]
    if math.isnan(atr) or massimo <= 0 or math.isnan(c[-1]):
        return None
    return {"atr": atr, "dmax": c[-1] / massimo - 1}


def _logistica(coef, storico, atr, dmax, trim):
    z = (coef[0] + coef[1] * storico / 100 + coef[2] * math.log(min(max(atr, 0.005), 0.15))
         + coef[3] * min(max(dmax, -0.5), 0.0) + coef[4] * trim)
    return 1 / (1 + math.exp(-z))


def punteggio_segnale(titolo, nome, per_titolo, per_strategia, fattori=None, gg_trim=None):
    """(punteggio 0-100, prob. +5%, prob. +10%, punteggio storico del pattern)."""
    storico = punteggio_titolo(titolo, nome, per_titolo, per_strategia)
    if not fattori:
        return storico, None, None, storico
    trim = 1.0 if gg_trim is not None and 0 <= gg_trim <= GIORNI_TRIM else 0.0
    p5 = _logistica(MODELLO_P5, storico, fattori["atr"], fattori["dmax"], trim)
    p10 = _logistica(MODELLO_P10, storico, fattori["atr"], fattori["dmax"], trim)
    p = 0.5 * _scala(p5, 0.45, 0.70) + 0.5 * _scala(p10, 0.15, 0.50)
    return int(math.floor(p * 100 + 0.5)), p5, p10, storico


def giudizio_punteggio(p):
    if p >= 80:
        return "molto favorevole"
    if p >= 60:
        return "favorevole"
    if p >= 40:
        return "incerto"
    if p >= 20:
        return "sfavorevole"
    return "molto sfavorevole"


def _pct(x):
    return f"{x:+.1f}%".replace(".", ",")


def testo_statistica(titolo, nome, mercato, per_titolo, per_strategia,
                     orizzonte=ORIZZONTE_STATISTICA, fattori=None, data_trim=None, oggi=None):
    st = per_titolo.get((titolo, nome)) or {"casi": 0}
    tot = per_strategia.get(nome) or {"casi": 0}
    righe = [f"📈 Pattern identificato: {nome}"]
    c = st["casi"]
    if c:
        r = (f"Negli ultimi {ANNI_STATISTICA} anni su {titolo} si è verificato {c} volte: in "
             f"{st['oltre']} ({math.floor(st['oltre'] * 100 / c + 0.5)}%) è salito oltre +5% "
             f"entro {orizzonte} giorni.")
        if c < 10:
            r += " (pochi casi: dato poco affidabile)"
        righe.append(r)
        righe.append("Rialzo medio: " + " · ".join(
            f"a {g} giorni {_pct(st[f'r{g}'] / c * 100)}" for g in GIORNI_RIALZO))
    else:
        righe.append(f"Su {titolo} il pattern non si era mai verificato negli ultimi "
                     f"{ANNI_STATISTICA} anni.")
    if tot["casi"]:
        righe.append(f"Su tutti i titoli {mercato}: {tot['casi']} volte, "
                     f"{math.floor(tot['oltre'] * 100 / tot['casi'] + 0.5)}% oltre +5%, "
                     f"rialzo medio a 30 giorni {_pct(tot['r30'] / tot['casi'] * 100)}.")
    gg = giorni_a_trimestrale(data_trim, oggi)
    punti, p5, p10, storico = punteggio_segnale(titolo, nome, per_titolo, per_strategia,
                                                fattori, gg)
    if fattori:
        righe.append(f"Volatilità giornaliera media: {_num1(fattori['atr'] * 100)}% · dal massimo "
                     f"a 52 settimane: {_pct(fattori['dmax'] * 100)}")
    if gg is not None and 0 <= gg <= GIORNI_TRIM:
        righe.append(f"📅 Trimestrale il {data_trim[8:10]}/{data_trim[5:7]} "
                     f"({'oggi' if gg == 0 else 'domani' if gg == 1 else f'tra {gg} giorni'}): "
                     "possibili salti di prezzo.")
    if p5 is not None:
        righe.append(f"Probabilità stimata: +5% prima dello stop {math.floor(p5 * 100 + 0.5)}% · "
                     f"+10% prima dello stop {math.floor(p10 * 100 + 0.5)}%")
    righe.append(f"🎯 PUNTEGGIO: {punti}/100 - {giudizio_punteggio(punti)}"
                 + (f" (storico del pattern {storico}/100)" if p5 is not None else ""))
    return "\n".join(righe)


def _num1(x):
    return f"{x:.1f}".replace(".", ",")


def giorni_a_trimestrale(data_trim, oggi=None):
    """Giorni di calendario da oggi alla prossima trimestrale (None se sconosciuta o passata)."""
    if not data_trim:
        return None
    try:
        gg = (date.fromisoformat(data_trim) - (oggi or date.today())).days
    except ValueError:
        return None
    return gg if gg >= 0 else None


# =====================================================================
# DIARIO DEI SEGNALI: cosa e' successo davvero dopo ogni segnale
# =====================================================================
GIORNI_DIARIO = 30
CONSERVA_DIARIO = 400            # giorni di diario conservati


def esito_segnale(prezzo, stop_pct, date, h, l, c, data_segnale):
    """
    Esito di un segnale dato il prezzo d'ingresso e le barre giornaliere complete
    (date in formato "AAAA-MM-GG", liste h/l/c). Si guardano i 30 giorni di borsa
    successivi al giorno del segnale. Se nello stesso giorno si toccano sia il +5% sia
    lo stop, conta lo stop (ipotesi prudente).
    """
    i = 0
    while i < len(date) and date[i] < data_segnale:
        i += 1
    if i >= len(date) or date[i] != data_segnale:
        i -= 1                                   # giorno del segnale non presente: uso il precedente
    dopo = len(date) - 1 - i
    giorni = min(dopo, GIORNI_DIARIO)
    es = {"giorni": giorni, "completo": giorni >= GIORNI_DIARIO}
    for g in (7, 15, 30):
        ok = dopo >= g and not math.isnan(c[i + g])
        es[f"r{g}"] = c[i + g] / prezzo - 1 if ok else None
    stop = prezzo * (1 - stop_pct)
    mx, mn, primo = 0.0, 0.0, None
    g5 = g10 = gstop = None
    for k in range(1, giorni + 1):
        if math.isnan(h[i + k]) or math.isnan(l[i + k]):
            continue
        if l[i + k] <= stop and gstop is None:
            gstop = k
        if h[i + k] >= prezzo * 1.05 and g5 is None:
            g5 = k
        if h[i + k] >= prezzo * 1.10 and g10 is None:
            g10 = k
        mx = max(mx, h[i + k] / prezzo - 1)
        mn = min(mn, l[i + k] / prezzo - 1)
    es.update(max=mx, min=mn, g5=g5, g10=g10, gstop=gstop)
    if g5 is not None and (gstop is None or g5 < gstop):
        primo = "+5%"
    elif gstop is not None:
        primo = "stop"
    elif es["completo"]:
        primo = "nessuno"
    es["primo"] = primo
    return es


def riepilogo_diario(segnali, posizioni, storico):
    """Gruppi di confronto per la scheda Diario: [(nome, statistiche)]."""
    comprati = comprati_da_segnali(segnali, posizioni, storico)
    gruppi = [("Tutti i segnali", lambda s: True),
              ("Inviati (sopra la soglia)", lambda s: s.get("inviato")),
              ("Scartati (sotto la soglia)", lambda s: not s.get("inviato")),
              ("Comprati da te", lambda s: s["id"] in comprati),
              ("Inviati ma non comprati", lambda s: s.get("inviato") and s["id"] not in comprati),
              ("Punteggio 80-100", lambda s: s.get("punteggio", 0) >= 80),
              ("Punteggio 60-79", lambda s: 60 <= s.get("punteggio", 0) < 80),
              ("Punteggio 40-59", lambda s: 40 <= s.get("punteggio", 0) < 60),
              ("Punteggio 0-39", lambda s: s.get("punteggio", 0) < 40)]
    out = []
    for nome, filtro in gruppi:
        el = [s for s in segnali if filtro(s)]
        chiusi = [s for s in el if (s.get("esito") or {}).get("primo")]
        n = len(chiusi)
        r30 = [s["esito"]["r30"] for s in chiusi if s["esito"].get("r30") is not None]
        out.append((nome, {
            "segnali": len(el), "conclusi": n,
            "p5": sum(s["esito"]["primo"] == "+5%" for s in chiusi) / n if n else None,
            "p10": sum(s["esito"]["g10"] is not None and (s["esito"]["gstop"] is None or
                                                      s["esito"]["g10"] < s["esito"]["gstop"])
                       for s in chiusi) / n if n else None,
            "pstop": sum(s["esito"]["primo"] == "stop" for s in chiusi) / n if n else None,
            "r30": sum(r30) / len(r30) if r30 else None}))
    return out


def comprati_da_segnali(segnali, posizioni, storico):
    """Id dei segnali seguiti da un acquisto dello stesso titolo entro 3 giorni."""
    acquisti = [(r["titolo"], r.get("data", "")) for r in list(posizioni) + list(storico)]
    out = set()
    for s in segnali:
        d0 = date.fromisoformat(s["data"])
        for tk, d in acquisti:
            if tk == s["titolo"] and d:
                try:
                    diff = (date.fromisoformat(d[:10]) - d0).days
                except ValueError:
                    continue
                if 0 <= diff <= 3:
                    out.add(s["id"])
                    break
    return out


# =====================================================================
def prezzo_acquisto_eur(p, cambio_ora=None):
    """Prezzo d'acquisto in euro (per i titoli USA: dollari / cambio del giorno d'acquisto)."""
    if p.get("prezzo_eur"):
        return float(p["prezzo_eur"])
    if mercato_di(p["titolo"]) == "Europa":
        return float(p["prezzo"])
    c = p.get("cambio") or cambio_ora
    return float(p["prezzo"]) / c if c else None


class Archivio:
    """
    Posizioni aperte e storico. Per ogni posizione:
      prezzo      -> prezzo d'acquisto nella valuta della borsa ($ per USA): serve al monitoraggio
      prezzo_eur  -> prezzo d'acquisto in euro (quello che vedi su Trade Republic)
      cambio      -> dollari per 1 euro al momento dell'acquisto (1 per i titoli europei)
      importo     -> controvalore in euro;  quantita -> numero di azioni (facoltativo)
    """
    FILE_POS = "posizioni.json"
    FILE_STORICO = "storico_operazioni.json"
    FILE_SEGNALI = "segnali.json"

    def __init__(self, commissione=1.0):
        self.lock = threading.RLock()
        self.commissione = commissione

    @staticmethod
    def _leggi(nome):
        try:
            with open(nome, encoding="utf-8") as f:
                return json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            return []

    @staticmethod
    def _scrivi(nome, dati):
        tmp = nome + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(dati, f, ensure_ascii=False, indent=2)
        os.replace(tmp, nome)

    @staticmethod
    def _cambio_valido(titolo, cambio):
        if mercato_di(titolo) == "Europa":
            return 1.0
        if not cambio:
            raise ValueError("cambio EUR/USD non disponibile")
        return float(cambio)

    def posizioni(self, mercato=None):
        with self.lock:
            pos = self._leggi(self.FILE_POS)
        for p in pos:
            p.setdefault("mercato", mercato_di(p["titolo"]))
        return [p for p in pos if mercato is None or p["mercato"] == mercato]

    def aggiungi(self, titolo, strategia, prezzo, importo, data_acq=None, cambio=None):
        """
        Aggiunge una posizione (prezzo nella valuta della borsa, importo in euro).
        Se il titolo e' gia' presente ne corregge il prezzo (e l'importo, se indicato).
        """
        titolo = titolo.upper().strip()
        cambio = self._cambio_valido(titolo, cambio)
        prezzo = float(prezzo)
        with self.lock:
            pos = self._leggi(self.FILE_POS)
            for p in pos:
                if p["titolo"] == titolo:
                    p.update(prezzo=prezzo, cambio=cambio, prezzo_eur=round(prezzo / cambio, 4))
                    if importo is not None:
                        p["importo"] = float(importo)
                    if p.get("quantita"):
                        p["importo"] = round(p["quantita"] * p["prezzo_eur"], 2)
                    self._scrivi(self.FILE_POS, pos)
                    return p, False
            p = {"titolo": titolo, "mercato": mercato_di(titolo), "strategia": strategia,
                 "data": data_acq or date.today().isoformat(), "prezzo": prezzo,
                 "prezzo_eur": round(prezzo / cambio, 4), "cambio": cambio,
                 "importo": float(importo or 0)}
            pos.append(p)
            self._scrivi(self.FILE_POS, pos)
            return p, True

    def aggiungi_eur(self, titolo, strategia, prezzo_eur, importo, data_acq=None, cambio=None):
        """Come aggiungi, ma con il prezzo in euro (convertito in dollari per i titoli USA)."""
        c = self._cambio_valido(titolo, cambio)
        return self.aggiungi(titolo, strategia, float(prezzo_eur) * c, importo, data_acq, c)

    def risultato(self, p, prezzo_vendita_eur, cambio=None):
        """Guadagno di una posizione se venduta a `prezzo_vendita_eur`:
        (azioni, lordo in euro, commissioni di acquisto + vendita, netto, variazione %)."""
        acquisto_eur = prezzo_acquisto_eur(p, cambio)
        if not acquisto_eur:
            return None
        azioni = p.get("quantita") or (p["importo"] / acquisto_eur)
        lordo = azioni * (float(prezzo_vendita_eur) - acquisto_eur)
        commissioni = 2 * float(self.commissione)
        return {"azioni": azioni, "lordo": lordo, "commissioni": commissioni,
                "netto": lordo - commissioni,
                "var_pct": (float(prezzo_vendita_eur) / acquisto_eur - 1) * 100,
                "acquisto_eur": acquisto_eur}

    def chiudi(self, titolo, prezzo_vendita=None, cambio=None, prezzo_vendita_eur=None,
               data_vendita=None):
        """Chiude una posizione. Prezzo di vendita nella valuta della borsa oppure in euro.
        Il risultato e' calcolato in euro, al netto delle commissioni di acquisto e vendita."""
        titolo = titolo.upper().strip()
        c = self._cambio_valido(titolo, cambio)
        if prezzo_vendita_eur is None:
            prezzo_vendita_eur = float(prezzo_vendita) / c
        if prezzo_vendita is None:
            prezzo_vendita = float(prezzo_vendita_eur) * c
        with self.lock:
            pos = self._leggi(self.FILE_POS)
            for i, p in enumerate(pos):
                if p["titolo"] != titolo:
                    continue
                r = self.risultato(p, prezzo_vendita_eur, c)
                if r is None:
                    raise ValueError("prezzo d'acquisto in euro mancante")
                pos.pop(i)
                rec = {**p, "prezzo_eur": round(r["acquisto_eur"], 4),
                       "quantita": round(r["azioni"], 6),
                       "data_vendita": data_vendita or date.today().isoformat(),
                       "prezzo_vendita": round(float(prezzo_vendita), 4),
                       "prezzo_vendita_eur": round(float(prezzo_vendita_eur), 4),
                       "cambio_vendita": c,
                       "rend_lordo_pct": round(r["var_pct"], 2),
                       "guadagno_lordo_eur": round(r["lordo"], 2),
                       "commissioni_eur": round(r["commissioni"], 2),
                       "netto_eur_stimato": round(r["netto"], 2),
                       "id": f"{titolo}-{datetime.now():%Y%m%d%H%M%S%f}"}
                storico = self._leggi(self.FILE_STORICO)
                storico.append(rec)
                self._scrivi(self.FILE_STORICO, storico)
                self._scrivi(self.FILE_POS, pos)
                return rec
            return None

    def modifica_operazione(self, rec_id, prezzo_vendita_eur=None, data_vendita=None,
                            prezzo_eur=None, quantita=None):
        """Corregge un'operazione chiusa (prezzi in euro) e ricalcola lordo, netto e variazione."""
        with self.lock:
            storico = self._leggi(self.FILE_STORICO)
            for i, r in enumerate(storico):
                if r.get("id", f"vecchia-{i}") != rec_id:
                    continue
                if prezzo_vendita_eur is not None:
                    r["prezzo_vendita_eur"] = round(float(prezzo_vendita_eur), 4)
                    c = float(r.get("cambio_vendita") or (1.0 if mercato_di(r["titolo"]) == "Europa"
                                                         else r.get("cambio") or 1.0))
                    r["prezzo_vendita"] = round(float(prezzo_vendita_eur) * c, 4)
                if data_vendita:
                    r["data_vendita"] = data_vendita
                if prezzo_eur is not None:
                    r["prezzo_eur"] = round(float(prezzo_eur), 4)
                if quantita is not None:
                    r["quantita"] = float(quantita)
                acq = float(r.get("prezzo_eur") or r.get("prezzo"))
                ven = float(r.get("prezzo_vendita_eur") or r.get("prezzo_vendita"))
                azioni = float(r.get("quantita") or (float(r.get("importo") or 0) / acq))
                comm = float(r.get("commissioni_eur", 2 * self.commissione))
                lordo = azioni * (ven - acq)
                r.update(quantita=round(azioni, 6), rend_lordo_pct=round((ven / acq - 1) * 100, 2),
                         guadagno_lordo_eur=round(lordo, 2), commissioni_eur=round(comm, 2),
                         netto_eur_stimato=round(lordo - comm, 2))
                r.setdefault("id", rec_id)
                self._scrivi(self.FILE_STORICO, storico)
                return r
            return None

    def ultima_vendita(self, titolo, giorni=3):
        """Operazione chiusa piu' recente di quel titolo negli ultimi giorni (per correggerla)."""
        titolo = titolo.upper().strip()
        limite = (date.today() - pd.Timedelta(days=giorni)).isoformat()
        candidati = [r for r in self.storico() if r["titolo"] == titolo
                     and str(r.get("data_vendita", "")) >= limite]
        return candidati[-1] if candidati else None

    def elimina_operazione(self, rec_id):
        """Toglie dallo storico un'operazione chiusa (per correggere un errore)."""
        with self.lock:
            storico = self._leggi(self.FILE_STORICO)
            nuovo = [r for i, r in enumerate(storico) if r.get("id", f"vecchia-{i}") != rec_id]
            self._scrivi(self.FILE_STORICO, nuovo)
            return len(nuovo) < len(storico)

    def modifica(self, titolo, prezzo_eur=None, quantita=None, data_acq=None, strategia=None,
                 cambio=None):
        """Correzione manuale (prezzo in euro). Il prezzo in dollari usato dal monitoraggio
        viene ricalcolato con il cambio del giorno d'acquisto. Controvalore = azioni x prezzo."""
        titolo = titolo.upper().strip()
        with self.lock:
            pos = self._leggi(self.FILE_POS)
            for p in pos:
                if p["titolo"] != titolo:
                    continue
                if prezzo_eur is not None:
                    c = p.get("cambio") or self._cambio_valido(titolo, cambio)
                    p.update(prezzo_eur=float(prezzo_eur), cambio=c,
                             prezzo=round(float(prezzo_eur) * c, 4))
                if data_acq:
                    p["data"] = data_acq
                if strategia:
                    p["strategia"] = strategia
                if quantita is not None:
                    p["quantita"] = float(quantita)
                if p.get("quantita"):
                    pe = prezzo_acquisto_eur(p, cambio)
                    if pe:
                        p["importo"] = round(p["quantita"] * pe, 2)
                self._scrivi(self.FILE_POS, pos)
                return p
            return None

    def elimina(self, titolo):
        with self.lock:
            pos = [p for p in self._leggi(self.FILE_POS) if p["titolo"] != titolo.upper()]
            self._scrivi(self.FILE_POS, pos)

    # ---------- diario dei segnali ----------
    def segnali(self):
        with self.lock:
            return self._leggi(self.FILE_SEGNALI)

    def registra_segnale(self, rec):
        """rec: data, ora, mercato, titolo, strategia, prezzo, stop_pct, punteggio, soglia, inviato."""
        rec = dict(rec)
        rec["id"] = f"{rec['data']}|{rec['titolo']}|{rec['strategia']}"
        with self.lock:
            el = [s for s in self._leggi(self.FILE_SEGNALI) if s["id"] != rec["id"]]
            limite = (date.fromisoformat(rec["data"]) -
                      pd.Timedelta(days=CONSERVA_DIARIO)).isoformat()
            el = [s for s in el if s["data"] >= limite] + [rec]
            self._scrivi(self.FILE_SEGNALI, el)
        return rec

    def aggiorna_esiti(self, mercato, dati_ind):
        """Calcola l'esito dei segnali non ancora conclusi con le barre complete del mercato."""
        with self.lock:
            el = self._leggi(self.FILE_SEGNALI)
            cambiati = 0
            for s in el:
                if s.get("mercato") != mercato or (s.get("esito") or {}).get("completo"):
                    continue
                ind = dati_ind.get(s["titolo"])
                if ind is None or not len(ind):
                    continue
                date_ = [d.date().isoformat() for d in ind.index]
                if date_[-1] < s["data"]:
                    continue
                es = esito_segnale(float(s["prezzo"]), float(s["stop_pct"]), date_,
                                   ind["High"].tolist(), ind["Low"].tolist(),
                                   ind["Close"].tolist(), s["data"])
                if es != s.get("esito"):
                    s["esito"] = es
                    cambiati += 1
            if cambiati:
                self._scrivi(self.FILE_SEGNALI, el)
        return cambiati

    def storico(self):
        with self.lock:
            storico = self._leggi(self.FILE_STORICO)
        for i, r in enumerate(storico):
            r.setdefault("id", f"vecchia-{i}")
        return storico
