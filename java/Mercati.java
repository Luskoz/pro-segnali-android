package it.prosegnali.motore;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mercati seguiti, orari, valute e anagrafica dei titoli (nome e ISIN). */
public final class Mercati {
    private Mercati() {}

    public static final ZoneId NY = ZoneId.of("America/New_York");
    public static final ZoneId ROMA = ZoneId.of("Europe/Rome");
    public static final List<String> NOMI = Collections.unmodifiableList(Arrays.asList("USA", "Europa"));

    public static final class Mercato {
        public final String nome, benchmark, nomeBenchmark, valuta, bandiera, orarioItaliano, fonte;
        public final ZoneId fuso;
        public final LocalTime apertura, chiusura;
        public final List<String> titoli;

        Mercato(String nome, List<String> titoli, String benchmark, String nomeBenchmark, ZoneId fuso,
                LocalTime apertura, LocalTime chiusura, String valuta, String bandiera,
                String orarioItaliano, String fonte) {
            this.nome = nome; this.titoli = titoli; this.benchmark = benchmark;
            this.nomeBenchmark = nomeBenchmark; this.fuso = fuso; this.apertura = apertura;
            this.chiusura = chiusura; this.valuta = valuta; this.bandiera = bandiera;
            this.orarioItaliano = orarioItaliano; this.fonte = fonte;
        }
    }

    public static final List<String> TITOLI_USA = Collections.unmodifiableList(Arrays.asList(
        "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "JPM", "V", "MA", "UNH", "XOM", "JNJ", "PG", "HD", "COST", "LLY", "ABBV", "MRK", "PEP", "KO", "WMT", "BAC", "CVX", "ORCL", "CRM", "AMD", "ADBE", "NFLX", "CSCO", "INTC", "QCOM", "TXN", "IBM", "CAT", "GE", "HON", "BA", "DIS", "NKE", "MCD", "SBUX", "LOW", "GS", "MS", "AMGN", "TMO", "DHR", "NEE"));
    public static final List<String> TITOLI_EUROPA = Collections.unmodifiableList(Arrays.asList(
        "ASML.AS", "PRX.AS", "INGA.AS", "AD.AS", "ADYEN.AS", "WKL.AS", "ABI.BR", "NOKIA.HE", "SAP.DE", "SIE.DE", "ALV.DE", "DTE.DE", "MUV2.DE", "DB1.DE", "ADS.DE", "BAS.DE", "BAYN.DE", "MBG.DE", "BMW.DE", "IFX.DE", "DHL.DE", "RHM.DE", "VOW3.DE", "ENR.DE", "MC.PA", "TTE.PA", "SAN.PA", "OR.PA", "AIR.PA", "SU.PA", "BNP.PA", "AI.PA", "EL.PA", "CS.PA", "SAF.PA", "RMS.PA", "DG.PA", "BN.PA", "SGO.PA", "IBE.MC", "ITX.MC", "SAN.MC", "BBVA.MC", "UCG.MI", "ISP.MI", "ENEL.MI", "RACE.MI", "ENI.MI", "STLAM.MI", "G.MI", "PST.MI", "MB.MI", "BAMI.MI", "MONC.MI", "PRY.MI", "LDO.MI", "TIT.MI", "SRG.MI", "TRN.MI", "FBK.MI", "BMPS.MI", "CPR.MI", "TEN.MI", "STMMI.MI"));

    public static final Mercato USA = new Mercato("USA", TITOLI_USA, "SPY", "S&P500", NY,
            LocalTime.of(9, 30), LocalTime.of(16, 0), "$", "\uD83C\uDDFA\uD83C\uDDF8", "15:30-22:00", "finnhub");
    public static final Mercato EUROPA = new Mercato("Europa", TITOLI_EUROPA, "^STOXX50E", "Euro Stoxx 50",
            ROMA, LocalTime.of(9, 0), LocalTime.of(17, 30), "\u20AC", "\uD83C\uDDEA\uD83C\uDDFA", "9:00-17:30", "yahoo");

    public static Mercato get(String nome) {
        return "Europa".equals(nome) ? EUROPA : USA;
    }

    /** I simboli europei hanno il suffisso di borsa (.MI, .DE, .PA...). */
    public static String mercatoDi(String titolo) {
        return titolo.contains(".") ? "Europa" : "USA";
    }

    public static boolean eUsa(String titolo) {
        return "USA".equals(mercatoDi(titolo));
    }

    private static final Map<String, String[]> A = new LinkedHashMap<>();
    static {
        A.put("AAPL", new String[]{"Apple", "US0378331005"});
        A.put("MSFT", new String[]{"Microsoft", "US5949181045"});
        A.put("NVDA", new String[]{"NVIDIA", "US67066G1040"});
        A.put("AMZN", new String[]{"Amazon", "US0231351067"});
        A.put("GOOGL", new String[]{"Alphabet (classe A)", "US02079K3059"});
        A.put("META", new String[]{"Meta Platforms", "US30303M1027"});
        A.put("TSLA", new String[]{"Tesla", "US88160R1014"});
        A.put("AVGO", new String[]{"Broadcom", "US11135F1012"});
        A.put("JPM", new String[]{"JPMorgan Chase", "US46625H1005"});
        A.put("V", new String[]{"Visa", "US92826C8394"});
        A.put("MA", new String[]{"Mastercard", "US57636Q1040"});
        A.put("UNH", new String[]{"UnitedHealth", "US91324P1021"});
        A.put("XOM", new String[]{"Exxon Mobil", "US30231G1022"});
        A.put("JNJ", new String[]{"Johnson & Johnson", "US4781601046"});
        A.put("PG", new String[]{"Procter & Gamble", "US7427181091"});
        A.put("HD", new String[]{"Home Depot", "US4370761029"});
        A.put("COST", new String[]{"Costco", "US22160K1051"});
        A.put("LLY", new String[]{"Eli Lilly", "US5324571083"});
        A.put("ABBV", new String[]{"AbbVie", "US00287Y1091"});
        A.put("MRK", new String[]{"Merck & Co.", "US58933Y1055"});
        A.put("PEP", new String[]{"PepsiCo", "US7134481081"});
        A.put("KO", new String[]{"Coca-Cola", "US1912161007"});
        A.put("WMT", new String[]{"Walmart", "US9311421039"});
        A.put("BAC", new String[]{"Bank of America", "US0605051046"});
        A.put("CVX", new String[]{"Chevron", "US1667641005"});
        A.put("ORCL", new String[]{"Oracle", "US68389X1054"});
        A.put("CRM", new String[]{"Salesforce", "US79466L3024"});
        A.put("AMD", new String[]{"Advanced Micro Devices", "US0079031078"});
        A.put("ADBE", new String[]{"Adobe", "US00724F1012"});
        A.put("NFLX", new String[]{"Netflix", "US64110L1061"});
        A.put("CSCO", new String[]{"Cisco Systems", "US17275R1023"});
        A.put("INTC", new String[]{"Intel", "US4581401001"});
        A.put("QCOM", new String[]{"Qualcomm", "US7475251036"});
        A.put("TXN", new String[]{"Texas Instruments", "US8825081040"});
        A.put("IBM", new String[]{"IBM", "US4592001014"});
        A.put("CAT", new String[]{"Caterpillar", "US1491231015"});
        A.put("GE", new String[]{"GE Aerospace", "US3696043013"});
        A.put("HON", new String[]{"Honeywell", "US4385161066"});
        A.put("BA", new String[]{"Boeing", "US0970231058"});
        A.put("DIS", new String[]{"Walt Disney", "US2546871060"});
        A.put("NKE", new String[]{"Nike", "US6541061031"});
        A.put("MCD", new String[]{"McDonald's", "US5801351017"});
        A.put("SBUX", new String[]{"Starbucks", "US8552441094"});
        A.put("LOW", new String[]{"Lowe's", "US5486611073"});
        A.put("GS", new String[]{"Goldman Sachs", "US38141G1040"});
        A.put("MS", new String[]{"Morgan Stanley", "US6174464486"});
        A.put("AMGN", new String[]{"Amgen", "US0311621009"});
        A.put("TMO", new String[]{"Thermo Fisher Scientific", "US8835561023"});
        A.put("DHR", new String[]{"Danaher", "US2358511028"});
        A.put("NEE", new String[]{"NextEra Energy", "US65339F1012"});
        A.put("ASML.AS", new String[]{"ASML Holding", "NL0010273215"});
        A.put("PRX.AS", new String[]{"Prosus", "NL0013654783"});
        A.put("INGA.AS", new String[]{"ING Groep", "NL0011821202"});
        A.put("AD.AS", new String[]{"Ahold Delhaize", "NL0011794037"});
        A.put("ADYEN.AS", new String[]{"Adyen", "NL0012969182"});
        A.put("WKL.AS", new String[]{"Wolters Kluwer", "NL0000395903"});
        A.put("ABI.BR", new String[]{"Anheuser-Busch InBev", "BE0974293251"});
        A.put("NOKIA.HE", new String[]{"Nokia", "FI0009000681"});
        A.put("SAP.DE", new String[]{"SAP", "DE0007164600"});
        A.put("SIE.DE", new String[]{"Siemens", "DE0007236101"});
        A.put("ALV.DE", new String[]{"Allianz", "DE0008404005"});
        A.put("DTE.DE", new String[]{"Deutsche Telekom", "DE0005557508"});
        A.put("MUV2.DE", new String[]{"Munich Re", "DE0008430026"});
        A.put("DB1.DE", new String[]{"Deutsche Börse", "DE0005810055"});
        A.put("ADS.DE", new String[]{"adidas", "DE000A1EWWW0"});
        A.put("BAS.DE", new String[]{"BASF", "DE000BASF111"});
        A.put("BAYN.DE", new String[]{"Bayer", "DE000BAY0017"});
        A.put("MBG.DE", new String[]{"Mercedes-Benz Group", "DE0007100000"});
        A.put("BMW.DE", new String[]{"BMW", "DE0005190003"});
        A.put("IFX.DE", new String[]{"Infineon Technologies", "DE0006231004"});
        A.put("DHL.DE", new String[]{"DHL Group", "DE0005552004"});
        A.put("RHM.DE", new String[]{"Rheinmetall", "DE0007030009"});
        A.put("VOW3.DE", new String[]{"Volkswagen (privilegiate)", "DE0007664039"});
        A.put("ENR.DE", new String[]{"Siemens Energy", "DE000ENER6Y0"});
        A.put("MC.PA", new String[]{"LVMH", "FR0000121014"});
        A.put("TTE.PA", new String[]{"TotalEnergies", "FR0000120271"});
        A.put("SAN.PA", new String[]{"Sanofi", "FR0000120578"});
        A.put("OR.PA", new String[]{"L'Oréal", "FR0000120321"});
        A.put("AIR.PA", new String[]{"Airbus", "NL0000235190"});
        A.put("SU.PA", new String[]{"Schneider Electric", "FR0000121972"});
        A.put("BNP.PA", new String[]{"BNP Paribas", "FR0000131104"});
        A.put("AI.PA", new String[]{"Air Liquide", "FR0000120073"});
        A.put("EL.PA", new String[]{"EssilorLuxottica", "FR0000121667"});
        A.put("CS.PA", new String[]{"AXA", "FR0000120628"});
        A.put("SAF.PA", new String[]{"Safran", "FR0000073272"});
        A.put("RMS.PA", new String[]{"Hermès", "FR0000052292"});
        A.put("DG.PA", new String[]{"Vinci", "FR0000125486"});
        A.put("BN.PA", new String[]{"Danone", "FR0000120644"});
        A.put("SGO.PA", new String[]{"Saint-Gobain", "FR0000125007"});
        A.put("IBE.MC", new String[]{"Iberdrola", "ES0144580Y14"});
        A.put("ITX.MC", new String[]{"Inditex", "ES0148396007"});
        A.put("SAN.MC", new String[]{"Banco Santander", "ES0113900J37"});
        A.put("BBVA.MC", new String[]{"BBVA", "ES0113211835"});
        A.put("UCG.MI", new String[]{"UniCredit", "IT0005239360"});
        A.put("ISP.MI", new String[]{"Intesa Sanpaolo", "IT0000072618"});
        A.put("ENEL.MI", new String[]{"Enel", "IT0003128367"});
        A.put("RACE.MI", new String[]{"Ferrari", "NL0011585146"});
        A.put("ENI.MI", new String[]{"Eni", "IT0003132476"});
        A.put("STLAM.MI", new String[]{"Stellantis", "NL00150001Q9"});
        A.put("G.MI", new String[]{"Generali", "IT0000062072"});
        A.put("PST.MI", new String[]{"Poste Italiane", "IT0003796171"});
        A.put("MB.MI", new String[]{"Mediobanca", "IT0000062957"});
        A.put("BAMI.MI", new String[]{"Banco BPM", "IT0005218380"});
        A.put("MONC.MI", new String[]{"Moncler", "IT0004965148"});
        A.put("PRY.MI", new String[]{"Prysmian", "IT0004176001"});
        A.put("LDO.MI", new String[]{"Leonardo", "IT0003856405"});
        A.put("TIT.MI", new String[]{"Telecom Italia", "IT0003497168"});
        A.put("SRG.MI", new String[]{"Snam", "IT0003153415"});
        A.put("TRN.MI", new String[]{"Terna", "IT0003242622"});
        A.put("FBK.MI", new String[]{"FinecoBank", "IT0000072170"});
        A.put("BMPS.MI", new String[]{"Banca Monte dei Paschi", "IT0005508921"});
        A.put("CPR.MI", new String[]{"Campari", "NL0015435975"});
        A.put("TEN.MI", new String[]{"Tenaris", "LU2598331598"});
        A.put("STMMI.MI", new String[]{"STMicroelectronics", "NL0000226223"});
    }

    public static Map<String, String[]> anagrafica() { return Collections.unmodifiableMap(A); }

    public static String nome(String titolo) {
        String[] v = A.get(titolo);
        return v == null ? titolo : v[0];
    }

    public static String isin(String titolo) {
        String[] v = A.get(titolo);
        return v == null ? "" : v[1];
    }

    public static String scheda(String titolo) {
        return "https://finance.yahoo.com/quote/" + titolo + "/";
    }
}
