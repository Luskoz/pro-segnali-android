import it.prosegnali.motore.*;
import java.util.*;
public class ProvaTrim {
    public static void main(String[] a) throws Exception {
        Map<String, String> d = Prezzi.trimestrali(Arrays.asList("AAPL", "NVDA", "ENI.MI", "SAP.DE", "ISP.MI", "MC.PA"), new java.io.File("."));
        System.out.println("java: " + d + " errore=" + Prezzi.erroreTrimestrali);
    }
}
