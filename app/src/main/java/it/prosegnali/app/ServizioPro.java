package it.prosegnali.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.Map;

import it.prosegnali.motore.Mercati;

/**
 * Servizio in primo piano: resta acceso solo quando serve (monitoraggio su questo telefono con una
 * borsa aperta, oppure app aperta). Negli altri momenti si ferma e ci pensa la Sveglia.
 */
public class ServizioPro extends Service {
    private volatile boolean attivo;
    private Thread lavoro;
    private PowerManager.WakeLock wakeLock;
    private final Object sveglia = new Object();

    public static void avvia(Context c) {
        Intent i = new Intent(c, ServizioPro.class);
        c.startForegroundService(i);
    }

    public static void ferma(Context c) {
        c.stopService(new Intent(c, ServizioPro.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notifiche.creaCanali(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifiche.ID_SERVIZIO, Notifiche.servizio(this, "Avvio..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(Notifiche.ID_SERVIZIO, Notifiche.servizio(this, "Avvio..."));
        }
        final Nucleo n = Nucleo.get(this);
        n.dopoModificaLocale = () -> { synchronized (sveglia) { sveglia.notifyAll(); } };
        if (lavoro == null || !lavoro.isAlive()) {
            attivo = true;
            lavoro = new Thread(this::ciclo, "servizio-pro");
            lavoro.start();
        } else {
            synchronized (sveglia) { sveglia.notifyAll(); }
        }
        return START_STICKY;
    }

    private void tieniSveglio(boolean si) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (si && wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "prosegnali:monitoraggio");
            wakeLock.acquire();
        } else if (!si && wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
    }

    private void ciclo() {
        Nucleo n = Nucleo.get(this);
        while (attivo) {
            try {
                if (!n.canale().isEmpty()) {
                    try {
                        n.sincronizza();
                    } catch (Exception e) {
                        n.statoSincronia = "errore: " + e.getMessage();
                    }
                }
                n.allineaMonitor();
                boolean qui = "telefono".equals(n.monitorSu());
                tieniSveglio(qui);
                StringBuilder t = new StringBuilder();
                t.append(qui ? "Monitoraggio su QUESTO TELEFONO" : "Monitoraggio su: "
                        + ("pc".equals(n.monitorSu()) ? "PC" : "nessun dispositivo"));
                if (qui) {
                    for (Map.Entry<String, String> e : n.statoMercati.entrySet())
                        if (n.mercatiAttivi().contains(e.getKey()))
                            t.append("\n").append(Mercati.get(e.getKey()).bandiera).append(' ').append(e.getValue());
                }
                t.append("\nSincronia: ").append(n.canale().isEmpty() ? "non collegata" : n.statoSincronia);
                Notifiche.aggiornaServizio(this, t.toString());
            } catch (Exception e) {
                n.log("Errore del servizio: " + e.getMessage());
            }
            // risparmio batteria: se il telefono non deve monitorare (o le borse sono chiuse) e l'app
            // non e' aperta, mi fermo; una sveglia leggera fara' la sincronia e mi riaccendera'
            if (!Sveglia.serveAcceso(n) && !MainActivity.visibile) {
                n.log("Servizio in pausa: " + ("telefono".equals(n.monitorSu())
                        ? "borse chiuse, riparto prima della prossima apertura." : "il monitoraggio non è su questo telefono."));
                Sveglia.programma(this);
                attivo = false;
                stopSelf();
                break;
            }
            synchronized (sveglia) {
                try { sveglia.wait(30_000); } catch (InterruptedException e) { break; }
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }   // raccolgo modifiche ravvicinate
        }
    }

    @Override
    public void onDestroy() {
        attivo = false;
        if (Nucleo.get(this).servizioAttivo()) Sveglia.programma(this);
        if (lavoro != null) lavoro.interrupt();
        Nucleo.get(this).fermaTutto();
        tieniSveglio(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
