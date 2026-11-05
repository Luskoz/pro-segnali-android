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
 * Servizio in primo piano: tiene viva la sincronia con il PC (ogni 30 secondi) e, quando il
 * monitoraggio e' assegnato al telefono, fa girare i monitor dei mercati e l'ascolto di Telegram.
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
            synchronized (sveglia) {
                try { sveglia.wait(30_000); } catch (InterruptedException e) { break; }
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }   // raccolgo modifiche ravvicinate
        }
    }

    @Override
    public void onDestroy() {
        attivo = false;
        if (lavoro != null) lavoro.interrupt();
        Nucleo.get(this).fermaTutto();
        tieniSveglio(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
