import json, time, traceback
import pandas as pd, yfinance as yf
titoli = json.load(open("titoli.json")) + ["SPY", "^STOXX50E", "^VIX", "^GSPC"]
righe = []
for i in range(0, len(titoli), 20):
    gruppo = titoli[i:i + 20]
    for tentativo in range(4):
        try:
            raw = yf.download(gruppo, start="2014-01-01", auto_adjust=True, group_by="ticker",
                              progress=False, threads=True)
            break
        except Exception:
            traceback.print_exc(); time.sleep(30)
    for tk in gruppo:
        try:
            df = raw[tk][["Open", "High", "Low", "Close", "Volume"]].dropna(subset=["Close"])
        except KeyError:
            print("manca", tk); continue
        df = df.reset_index().rename(columns={"Date": "data"})
        df.insert(0, "titolo", tk)
        righe.append(df)
    time.sleep(3)
tutto = pd.concat(righe)
tutto.to_csv("esito/prezzi.csv.gz", index=False, float_format="%.5f")
print("titoli scaricati", tutto.titolo.nunique(), "righe", len(tutto))
ear = []
for tk in json.load(open("titoli.json")):
    try:
        e = yf.Ticker(tk).get_earnings_dates(limit=60)
        if e is not None and len(e):
            e = e.reset_index()
            ear.append(pd.DataFrame({"titolo": tk, "data": e.iloc[:, 0].astype(str)}))
    except Exception as ex:
        print("trimestrali", tk, type(ex).__name__, str(ex)[:100])
    time.sleep(0.5)
if ear:
    pd.concat(ear).to_csv("esito/trimestrali.csv", index=False)
    print("trimestrali per", len(ear), "titoli")
