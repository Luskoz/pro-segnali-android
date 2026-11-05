package it.prosegnali.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView t = new TextView(this);
        t.setText("PRO.SEGNALI - prova di compilazione");
        t.setPadding(40, 80, 40, 40);
        setContentView(t);
    }
}
