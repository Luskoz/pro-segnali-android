package it.prosegnali.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Piccoli aiuti per costruire le schermate da codice. */
final class Ui {
    private Ui() {}

    static final int VERDE = Color.parseColor("#1E8E3E");
    static final int ROSSO = Color.parseColor("#C5221F");
    static final int GRIGIO = Color.parseColor("#5F6368");
    static final int BLU = Color.parseColor("#1A56DB");
    static final int SFONDO = Color.parseColor("#F1F4F9");
    static final int TESTO = Color.parseColor("#1F2328");
    static final int NOTTE = Color.parseColor("#13284B");

    static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    static TextView testo(Activity a, CharSequence t, float sp, boolean grassetto, int colore) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(colore);
        if (grassetto) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    static TextView testo(Activity a, CharSequence t) { return testo(a, t, 14, false, TESTO); }

    static TextView titolo(Activity a, CharSequence t) {
        TextView v = testo(a, t, 16, true, NOTTE);
        v.setPadding(0, dp(a, 14), 0, dp(a, 6));
        return v;
    }

    static Button pulsante(Activity a, CharSequence t, int colore, View.OnClickListener azione) {
        Button b = new Button(a);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable g = new GradientDrawable();
        g.setColor(colore);
        g.setCornerRadius(dp(a, 10));
        b.setBackground(g);
        b.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
        b.setOnClickListener(azione);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(a, 4), dp(a, 8), dp(a, 4));
        b.setLayoutParams(lp);
        return b;
    }

    static LinearLayout scheda(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(a, 12));
        l.setBackground(g);
        l.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(a, 6), 0, dp(a, 6));
        l.setLayoutParams(lp);
        return l;
    }

    static LinearLayout riga(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout colonna(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static EditText campo(Activity a, String valore, String suggerimento, int tipo) {
        EditText e = new EditText(a);
        e.setText(valore);
        e.setHint(suggerimento);
        e.setInputType(tipo);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return e;
    }

    static EditText campoNumero(Activity a, String valore, String suggerimento) {
        return campo(a, valore, suggerimento, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    }

    static EditText campoTesto(Activity a, String valore, String suggerimento) {
        return campo(a, valore, suggerimento, InputType.TYPE_CLASS_TEXT);
    }

    /** Etichetta + campo in colonna, per i dialoghi. */
    static void etichettato(Activity a, LinearLayout dove, String etichetta, View campo) {
        TextView t = testo(a, etichetta, 13, false, GRIGIO);
        t.setPadding(0, dp(a, 8), 0, 0);
        dove.addView(t);
        dove.addView(campo);
    }

    static double numero(String t) {
        t = t.trim();
        if (t.contains(",")) t = t.replace(".", "").replace(",", ".");
        return Double.parseDouble(t);
    }
}
