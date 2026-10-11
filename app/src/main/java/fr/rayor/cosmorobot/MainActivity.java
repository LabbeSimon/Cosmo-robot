package fr.rayor.cosmorobot;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Ébauche provisoire : sert à valider la chaîne de build, l'interface arrive ensuite. */
public class MainActivity extends Activity {
    private Py py;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        py = new Py(this);
        TextView t = Ui.text(this, getString(R.string.app_name) + "\n" + py.state(), 16, Ui.TEXT);
        t.setBackgroundColor(Ui.BG);
        setContentView(t);
    }

    @Override
    protected void onDestroy() {
        py.shutdown();
        super.onDestroy();
    }
}
