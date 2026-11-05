package it.prosegnali.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import it.prosegnali.motore.Mercati;

/** Canali e notifiche dell'app. */
public final class Notifiche {
    private Notifiche() {}

    public static final String CANALE_SERVIZIO = "servizio";
    public static final String CANALE_AVVISI = "avvisi";
    public static final int ID_SERVIZIO = 1;

    public static void creaCanali(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel s = new NotificationChannel(CANALE_SERVIZIO, "Servizio attivo",
                NotificationManager.IMPORTANCE_LOW);
        s.setDescription("Notifica fissa mentre PRO.SEGNALI lavora in background");
        nm.createNotificationChannel(s);
        NotificationChannel a = new NotificationChannel(CANALE_AVVISI, "Segnali di acquisto e vendita",
                NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Avvisi COMPRA / VENDI");
        a.enableVibration(true);
        nm.createNotificationChannel(a);
    }

    static PendingIntent apriApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static Notification servizio(Context c, String testo) {
        return new Notification.Builder(c, CANALE_SERVIZIO)
                .setSmallIcon(R.drawable.ic_notifica)
                .setContentTitle("PRO.SEGNALI attivo")
                .setContentText(testo)
                .setStyle(new Notification.BigTextStyle().bigText(testo))
                .setOngoing(true)
                .setContentIntent(apriApp(c))
                .build();
    }

    public static void aggiornaServizio(Context c, String testo) {
        c.getSystemService(NotificationManager.class).notify(ID_SERVIZIO, servizio(c, testo));
    }

    /** Avviso COMPRA/VENDI: toccandolo si apre la scheda del titolo (grafico e dati). */
    public static void avviso(Context c, String titolo, String testo, boolean acquisto) {
        Intent scheda = new Intent(Intent.ACTION_VIEW, Uri.parse(Mercati.scheda(titolo)));
        PendingIntent pi = PendingIntent.getActivity(c, titolo.hashCode(), scheda,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CANALE_AVVISI)
                .setSmallIcon(R.drawable.ic_notifica)
                .setContentTitle((acquisto ? "🟢 " : "🔴 ") + testo)
                .setContentText(Mercati.nome(titolo) + " · ISIN " + Mercati.isin(titolo) + " · tocca per il grafico")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Apri PRO.SEGNALI", apriApp(c)).build())
                .build();
        c.getSystemService(NotificationManager.class).notify((int) (System.currentTimeMillis() % 100000) + 10, n);
    }
}
