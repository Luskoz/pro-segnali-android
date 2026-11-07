import servizi
t = servizi.Trimestrali(); t.FILE = "trim_py.json"
print("python:", t.prossime(["AAPL", "NVDA", "ENI.MI", "SAP.DE", "ISP.MI", "MC.PA"]), "errore=", t.ultimo_errore)
