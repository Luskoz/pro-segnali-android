package it.prosegnali.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.List;

import it.prosegnali.motore.Mercati;

/**
 * Risparmio batteria: quando il telefono non deve monitorare (monitoraggio sul PC, spento, o borse
 * chiuse) il servizio si ferma e una sveglia leggera ogni 15 minuti fa solo la sincronia e controlla
 * se serve riaccenderlo (es. monitoraggio spostato sul telefono o borsa in apertura).
 */
public class Sveglia extends BroadcastReceiver {
    static final long INTERVALLO_MS = 15 * 60_000L;
    /** Minuti prima dell'apertura e dopo la chiusura in cui il servizio resta acceso. */
    static final int ANTICIPO_MIN = 20, CODA_MIN = 10;

    @Override
    public void onReceive(Context c, Intent i) {
        final PendingResult pr = goAsync();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Nucleo n = Nucleo.get(app);
                if (!n.servizioAttivo()) return;
                if (!n.canale().isEmpty()) {
                    try { n.sincronizza(); } catch (Exception e) { n.statoSincronia = "errore: " + e.getMessage(); }
                }
                if (serveAcceso(n)) {
                    try { ServizioPro.avvia(app); } catch (Exception e) { n.log("Servizio non avviabile ora: " + e.getMessage()); }
                }
                programma(app);
            } finally {
                pr.finish();
            }
        }, "sveglia").start();
    }

    /** Il servizio deve restare acceso: monitoraggio su questo telefono e una borsa attiva aperta (o quasi). */
    static boolean serveAcceso(Nucleo n) {
        if (!n.servizioAttivo() || !"telefono".equals(n.monitorSu())) return false;
        List<String> attivi = n.mercatiAttivi();
        for (String nome : attivi) if (borsaVicina(Mercati.get(nome))) return true;
        return false;
    }

    static boolean borsaVicina(Mercati.Mercato m) {
        ZonedDateTime ora = ZonedDateTime.now(m.fuso);
        DayOfWeek g = ora.getDayOfWeek();
        if (g == DayOfWeek.SATURDAY || g == DayOfWeek.SUNDAY) return false;
        java.time.LocalTime t = ora.toLocalTime();
        return !t.isBefore(m.apertura.minusMinutes(ANTICIPO_MIN)) && t.isBefore(m.chiusura.plusMinutes(CODA_MIN));
    }

    /** Prossima sveglia: tra 15 minuti (o prima, se una borsa attiva apre prima). */
    static void programma(Context c) {
        Nucleo n = Nucleo.get(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = intento(c);
        if (!n.servizioAttivo()) { am.cancel(pi); return; }
        long quando = System.currentTimeMillis() + INTERVALLO_MS;
        if ("telefono".equals(n.monitorSu())) {
            for (String nome : n.mercatiAttivi()) {
                Mercati.Mercato m = Mercati.get(nome);
                ZonedDateTime ora = ZonedDateTime.now(m.fuso);
                ZonedDateTime apre = ora.with(m.apertura.minusMinutes(ANTICIPO_MIN));
                if (!apre.isAfter(ora)) apre = apre.plusDays(1);
                while (apre.getDayOfWeek() == DayOfWeek.SATURDAY || apre.getDayOfWeek() == DayOfWeek.SUNDAY) apre = apre.plusDays(1);
                quando = Math.min(quando, apre.toInstant().toEpochMilli());
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi);
    }

    static void annulla(Context c) {
        ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(intento(c));
    }

    private static PendingIntent intento(Context c) {
        return PendingIntent.getBroadcast(c, 7, new Intent(c, Sveglia.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
