package it.prosegnali.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Riavvia il servizio quando il telefono si accende (se era attivo). */
public class AvvioTelefono extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && Nucleo.get(c).servizioAttivo()) {
            try { ServizioPro.avvia(c); } catch (Exception ignorata) { /* il sistema non lo consente ora */ }
        }
    }
}
