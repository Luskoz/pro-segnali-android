# prova del metodo cookie+crumb per il calendario trimestrali (da usare nell'app)
J=/tmp/cj.txt; rm -f $J
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
curl -s -c $J -A "$UA" -o /dev/null -w "fc %{http_code}\n" https://fc.yahoo.com/
CRUMB=$(curl -s -b $J -A "$UA" https://query1.finance.yahoo.com/v1/test/getcrumb)
echo "crumb: ${CRUMB:0:4}... (${#CRUMB})"
for T in AAPL ENI.MI SAP.DE; do
  echo "== $T"; curl -s -b $J -A "$UA" "https://query2.finance.yahoo.com/v10/finance/quoteSummary/$T?modules=calendarEvents&crumb=$CRUMB" | head -c 600; echo
done
echo "== v7 quote"; curl -s -b $J -A "$UA" "https://query1.finance.yahoo.com/v7/finance/quote?symbols=AAPL,ENI.MI&fields=earningsTimestamp,earningsTimestampStart,earningsTimestampEnd&crumb=$CRUMB" | head -c 1200; echo
echo "== senza crumb"; curl -s -A "$UA" "https://query2.finance.yahoo.com/v10/finance/quoteSummary/AAPL?modules=calendarEvents" | head -c 300; echo
