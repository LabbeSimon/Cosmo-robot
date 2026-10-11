package fr.rayor.cosmorobot;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;

/**
 * L'écran unique de l'appli : connexion, puis pilotage.
 *
 * Tout ce qui parle à Python ou au réseau quitte le fil de l'interface : la
 * connexion passe par les files de {@link Py}, et l'état comme les images sont
 * lus par un fil de sondage dédié ({@link Poller}).
 */
public class MainActivity extends Activity {
    private static final String TAG = "CosmoUi";
    private static final String REPO = "https://github.com/LabbeSimon/Cosmo-robot";
    private static final String REPO_LABEL = "github.com/LabbeSimon/Cosmo-robot";
    /** Écran de Cozmo : 2 lignes de 21 caractères. */
    private static final int SCREEN_CHARS = 42;

    private enum Phase { OFFLINE, PREPARING, WIFI, CONNECTING, CONNECTED, RECONNECTING }

    // LEDs du dos : la teinte montrée à l'écran (couleurs système, assorties à la
    // palette), et la valeur envoyée au robot, en RGB pur.
    private static final int[] LIGHT_SHOWN = {0, 0xFFF5F5F7, 0xFFFF453A, 0xFFFF9F0A,
            0xFFFFD60A, 0xFF30D158, 0xFF0A84FF, 0xFFBF5AF2};
    private static final int[][] LIGHT_RGB = {{0, 0, 0}, {255, 255, 255}, {255, 0, 0}, {255, 110, 0},
            {255, 210, 0}, {0, 255, 0}, {0, 80, 255}, {170, 0, 255}};
    private static final int[] LIGHT_NAMES = {R.string.light_off, R.string.light_white, R.string.light_red,
            R.string.light_orange, R.string.light_yellow, R.string.light_green, R.string.light_blue,
            R.string.light_purple};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private volatile Py py;
    private CozmoWifi wifi;
    private Speech speech;
    private Poller poller;

    // --- Intention de l'utilisateur et avancement de la connexion -----------
    /** L'utilisateur veut être connecté (lu aussi par le fil de sondage). */
    private volatile boolean wantLink;
    /** La liaison a abouti au moins une fois depuis le dernier « Connecter ». */
    private volatile boolean linkedOnce;
    private boolean joining, linking, resumeOnStart, fromSettings;
    private boolean started, destroyed, wifiLostShown, keepOn, bootFailShown;
    /** Python a fini de démarrer (lu dans l'état sondé). */
    private boolean pyReady;
    /** Numéro de tentative : les rappels d'une tentative abandonnée sont ignorés. */
    private int session;
    private Robot st = Robot.EMPTY;
    private Phase phase = Phase.OFFLINE;
    private volatile boolean camActive;
    private boolean camOverride;
    private long camOverrideUntil, sayBusyUntil;
    private int light;

    // --- Conduite ------------------------------------------------------------
    private float jx, jy;
    private boolean driving;
    private final Runnable driveTick = new Runnable() {
        @Override public void run() {
            if (!driving) return;
            Py p = py;
            // Renvoyé toutes les 100 ms tant que le doigt est posé : côté Python,
            // l'homme-mort arrête les roues au bout de 0,35 s sans nouvel ordre.
            if (p != null && phase == Phase.CONNECTED) p.drive(jx, jy);
            ui.postDelayed(this, 100);
        }
    };

    // --- Vues ------------------------------------------------------------------
    private FrameLayout root;
    private LinearLayout leftPane;
    private ScrollView scroll;
    private Ui.Column content;
    private boolean twoPane, arranged, wasLive;

    private LinearLayout header;
    private StatusDot dot;
    private TextView statusText;

    private LinearLayout faceHero;
    private TextView faceText;
    private LinearLayout connectCard;
    private EditText password;
    private LinearLayout errorBox;
    private TextView errorTitle, errorDetail;
    private LinearLayout connectBtn;
    private ProgressBar connectSpin;
    private TextView connectLabel;
    private TextView settingsBtn;

    private LinearLayout hero;
    private LensView lens;
    private Ui.Flow badges;
    private TextView badgeDocked, badgeCharging, badgePicked, badgeCliff;
    private BatteryGauge gauge;
    private TextView batteryPct, batteryVolts;
    private TextView lowNotice;

    private LinearLayout controls;
    private JoystickView joystick;
    private TextView driveHint;
    private ValueTile head, lift, speed, volume;
    private EditText sayField, screenField;
    private TextView sayBtn, showBtn, screenCount;
    private Swatch[] swatches;
    private TextView lightName;
    private TextView disconnectBtn;

    private LinearLayout about;
    private LinearLayout aboutMore;
    private TextView aboutVersion;

    private TextView snack;
    private final Runnable hideSnack = this::hideSnack;
    private FrameLayout licencesPanel;
    private TextView licencesText;

    // =====================================================================
    // Cycle de vie
    // =====================================================================

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("cosmo", MODE_PRIVATE);
        if (saved != null) resumeOnStart = saved.getBoolean("resume", false);
        // Py démarre Python en arrière-plan (plusieurs secondes au premier
        // lancement) ; en attendant, state() répond « ready: false ».
        py = new Py(this);
        wifi = new CozmoWifi(this);
        speech = new Speech(this);
        edgeToEdge();
        build();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        poller = new Poller();
        poller.start();
        if (resumeOnStart) {
            // L'utilisateur était connecté avant de quitter l'appli : on reprend.
            resumeOnStart = false;
            beginConnect();
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (fromSettings) {
            // Retour des réglages Wi-Fi : si le réseau de Cozmo est maintenant
            // rejoint, on enchaîne sans redemander de toucher Connecter.
            fromSettings = false;
            if (!wantLink && py != null && wifi.bindExisting()) beginConnect();
        }
    }

    @Override
    protected void onStop() {
        started = false;
        if (poller != null) {
            poller.running = false;
            poller = null;
        }
        stopDriving();
        if (joining) {
            // La fenêtre système de choix du réseau peut masquer l'activité :
            // la demande reste en cours, onJoined décidera (voir beginConnect).
            super.onStop();
            return;
        }
        if (wantLink) resumeOnStart = true;
        // Toujours couper en partant : une session laissée ouverte devient
        // fantôme sur le robot, qui refuse ensuite toute nouvelle connexion.
        teardown(true);
        render();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("resume", resumeOnStart || wantLink);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        if (poller != null) poller.running = false;
        Py p = py;
        py = null;
        if (p != null) {
            p.stop();
            p.disconnect();
            p.shutdown();
        }
        wifi.release();
        speech.shutdown();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        arrange();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (licencesPanel.getVisibility() == View.VISIBLE) {
            closeLicences();
            return;
        }
        super.onBackPressed();
    }

    // =====================================================================
    // Connexion
    // =====================================================================

    private void beginConnect() {
        // Une tentative est déjà en route, ou la liaison est établie.
        if (wantLink && (joining || linking || linkedOnce)) return;
        hideError();
        hideKeyboard();
        String pw = password.getText().toString().trim();
        prefs.edit().putString("password", pw).apply();
        if (py == null) return;
        wantLink = true;
        linkedOnce = false;
        wifiLostShown = false;
        final int gen = ++session;
        // 1. Réseau de Cozmo déjà rejoint (par nous, ou dans les réglages).
        if (wifi.stillThere() || wifi.bindExisting()) {
            linkRobot(gen);
            return;
        }
        // 2. Sinon, le rejoindre nous-mêmes (Android 10+).
        if (!CozmoWifi.canJoin()) {
            fail(R.string.wifi_old_android, null, true);
            return;
        }
        if (pw.isEmpty()) {
            fail(R.string.wifi_needed, null, true);
            return;
        }
        if (pw.length() < 8) {
            fail(R.string.wifi_short, null, true);
            return;
        }
        joining = true;
        render();
        try {
            wifi.join(pw, new CozmoWifi.Listener() {
                @Override public void onJoined() {
                    ui.post(() -> {
                        if (gen != session || destroyed) return;
                        joining = false;
                        if (!started) {
                            // Rejoint pendant que l'appli était en arrière-plan :
                            // on ne garde rien, la reprise se fera au retour.
                            teardown(true);
                            resumeOnStart = true;
                            return;
                        }
                        linkRobot(gen);
                    });
                }

                @Override public void onFailed() {
                    ui.post(() -> {
                        if (gen != session || destroyed) return;
                        joining = false;
                        fail(R.string.wifi_failed, null, true);
                    });
                }

                @Override public void onLost() {
                    ui.post(() -> {
                        if (gen == session && !destroyed) onWifiLost();
                    });
                }
            });
        } catch (IllegalArgumentException e) {
            // WifiNetworkSpecifier refuse les mots de passe non conformes.
            joining = false;
            fail(R.string.wifi_failed, e.getMessage(), true);
        }
    }

    private void linkRobot(int gen) {
        Py p = py;
        if (p == null || gen != session) return;
        linking = true;
        render();
        p.connect(err -> ui.post(() -> {
            // "cancelled" : une déconnexion est passée entre-temps.
            if (gen != session || destroyed || "cancelled".equals(err)) return;
            linking = false;
            if (err == null) {
                linkedOnce = true;
                wifiLostShown = false;
                st = st.connectedCopy();
                camActive = st.cameraWanted;
                applySettings();
                hideError();
                render();
            } else if (!linkedOnce) {
                // Le Wi-Fi reste acquis : un nouvel essai n'aura pas à le rejoindre.
                fail(R.string.robot_failed, err, false);
            }
            // Après une première réussite, Python retente seul : rien à faire.
        }));
    }

    /** Rejoue les réglages de l'utilisateur sur un robot fraîchement connecté. */
    private void applySettings() {
        send("set_speed", speed.value());
        send("set_volume", volume.value());
        send("set_head", head.value());
        send("set_lift", lift.value());
        int[] rgb = LIGHT_RGB[light];
        send("set_backpack", rgb[0], rgb[1], rgb[2]);
        boolean cam = prefs.getBoolean("camera", false);
        send("set_camera", cam);
        overrideCamera(cam);
        camActive = cam;
    }

    private void fail(int res, String detail, boolean releaseWifi) {
        teardown(releaseWifi);
        showError(res, detail);
        render();
    }

    /** Abandonne la tentative ou la session en cours, proprement. */
    private void teardown(boolean releaseWifi) {
        session++;
        wantLink = false;
        linkedOnce = false;
        joining = false;
        linking = false;
        camActive = false;
        stopDriving();
        Py p = py;
        if (p != null) {
            p.stop();
            p.disconnect();
        }
        if (releaseWifi) wifi.release();
        lens.clear();
        st = Robot.EMPTY;
    }

    private void disconnect() {
        resumeOnStart = false;
        teardown(true);
        render();
    }

    private void onWifiLost() {
        if (!wantLink) return;
        if (!linkedOnce) {
            if (!joining) fail(R.string.wifi_lost, null, true);
            return;
        }
        // Python retente la liaison ; le sondage, lui, guette le retour du réseau.
        if (!wifiLostShown) {
            wifiLostShown = true;
            notice(getString(R.string.wifi_lost));
        }
        render();
    }

    private void send(String fn, Object... args) {
        Py p = py;
        if (p == null) return;
        try {
            p.send(fn, args);
        } catch (RejectedExecutionException ignored) {
            // appli en cours de fermeture
        }
    }

    private void stopDriving() {
        if (!driving) return;
        driving = false;
        ui.removeCallbacks(driveTick);
        Py p = py;
        if (p != null) p.stop();
    }

    // =====================================================================
    // État du robot
    // =====================================================================

    /** Dernier état connu du robot, lu depuis le JSON de cosmo_core.state(). */
    static final class Robot {
        static final Robot EMPTY = new Robot();
        float volts;
        int pct = -1;
        boolean docked, charging, pickedUp, cliff, low;
        boolean connected, wanted, cameraWanted, cameraOn, speaking;
        /** false tant que Python démarre (champ ajouté par Py). */
        boolean ready = true;
        String error = "";

        static Robot parse(String json) throws JSONException {
            JSONObject o = new JSONObject(json);
            Robot r = new Robot();
            r.volts = (float) o.optDouble("battery_v", 0);
            // Pas encore de RobotState reçu : niveau inconnu, pas « vide ».
            r.pct = r.volts > 0 ? o.optInt("battery_pct", -1) : -1;
            r.docked = o.optBoolean("docked");
            r.charging = o.optBoolean("charging");
            r.pickedUp = o.optBoolean("picked_up");
            r.cliff = o.optBoolean("cliff");
            r.low = o.optBoolean("low");
            r.connected = o.optBoolean("connected");
            r.wanted = o.optBoolean("wanted");
            r.cameraWanted = o.optBoolean("camera_wanted");
            r.cameraOn = o.optBoolean("camera_on");
            r.speaking = o.optBoolean("speaking");
            r.error = o.optString("error", "");
            r.ready = o.optBoolean("ready", true);
            return r;
        }

        Robot connectedCopy() {
            Robot r = new Robot();
            r.volts = volts;
            r.pct = pct;
            r.docked = docked;
            r.charging = charging;
            r.pickedUp = pickedUp;
            r.cliff = cliff;
            r.low = low;
            r.connected = true;
            r.wanted = true;
            r.cameraWanted = cameraWanted;
            r.cameraOn = cameraOn;
            r.speaking = speaking;
            r.ready = true;
            r.error = error;
            return r;
        }
    }

    private void onRobot(Robot r) {
        if (destroyed) return;
        boolean wasReady = pyReady;
        pyReady = r.ready;
        if (!r.ready && r.error.startsWith("Python") && !bootFailShown) {
            // Python n'a pas pu démarrer : rien ne marchera, autant le dire.
            bootFailShown = true;
            if (wantLink) teardown(true);
            showError(R.string.boot_failed, r.error);
            render();
            return;
        }
        if (!wantLink) {
            if (wasReady != pyReady) render();
            return;
        }
        st = r;
        camActive = r.connected && r.cameraWanted;
        if (r.connected) wifiLostShown = false;
        render();
    }

    private Phase computePhase() {
        // Python démarre encore (plusieurs secondes au premier lancement).
        if (!wantLink) return pyReady || bootFailShown ? Phase.OFFLINE : Phase.PREPARING;
        if (linkedOnce) return st.connected ? Phase.CONNECTED : Phase.RECONNECTING;
        if (joining) return Phase.WIFI;
        // La connexion attend que Python ait démarré : on le dit.
        return pyReady ? Phase.CONNECTING : Phase.PREPARING;
    }

    private static boolean isLive(Phase p) {
        return p == Phase.CONNECTED || p == Phase.RECONNECTING;
    }

    private String statusLabel(Phase p) {
        switch (p) {
            case PREPARING: return getString(R.string.status_preparing);
            case WIFI: return getString(R.string.status_wifi);
            case CONNECTING: return getString(R.string.status_connecting);
            case CONNECTED: return getString(R.string.status_connected);
            case RECONNECTING: return getString(R.string.status_reconnecting);
            default: return getString(R.string.status_offline);
        }
    }

    private String shortLabel(Phase p) {
        switch (p) {
            case CONNECTED: return getString(R.string.state_online);
            case RECONNECTING: return getString(R.string.state_lost);
            case OFFLINE: return getString(R.string.state_offline);
            case PREPARING: return getString(wantLink ? R.string.state_busy : R.string.state_offline);
            default: return getString(R.string.state_busy);
        }
    }

    private boolean cameraWanted() {
        return SystemClock.uptimeMillis() < camOverrideUntil ? camOverride : st.cameraWanted;
    }

    /** L'état de la caméra suit le geste tout de suite, sans attendre le sondage. */
    private void overrideCamera(boolean on) {
        camOverride = on;
        camOverrideUntil = SystemClock.uptimeMillis() + 1500;
    }

    /** Met toute l'interface en accord avec l'état courant. */
    private void render() {
        if (destroyed) return;
        Phase p = computePhase();
        phase = p;
        boolean live = isLive(p);
        boolean busy = p == Phase.PREPARING || p == Phase.WIFI || p == Phase.CONNECTING;

        // En-tête et visage
        dot.set(p == Phase.CONNECTED ? Ui.INK : p == Phase.OFFLINE ? Ui.INK_FAINT : Ui.INK_MUTED,
                busy || p == Phase.RECONNECTING);
        set(statusText, shortLabel(p).toUpperCase(Locale.getDefault()));
        set(faceText, statusLabel(p).toUpperCase(Locale.getDefault()));
        faceText.setTextColor(p == Phase.OFFLINE ? Ui.INK_MUTED : Ui.INK);

        // Bouton Connecter : indicateur et libellé d'étape pendant la tentative
        connectSpin.setVisibility(busy ? View.VISIBLE : View.GONE);
        set(connectLabel, busy ? statusLabel(p) : getString(R.string.connect));
        // Tant que Python démarre, Connecter attend ; le mot de passe, lui, peut
        // déjà être saisi.
        boolean canConnect = !busy && pyReady;
        connectBtn.setEnabled(canConnect);
        connectLabel.setTextColor(canConnect ? Ui.INK : Ui.INK_MUTED);
        boolean typing = !busy || (p == Phase.PREPARING && !wantLink);
        password.setEnabled(typing);
        password.setAlpha(typing ? 1f : Ui.DISABLED_ALPHA);

        if (live != wasLive || !arranged) {
            wasLive = live;
            arrange();
            View[] in = live ? new View[]{hero, controls} : new View[]{faceHero, connectCard};
            for (View v : in) {
                v.setAlpha(0f);
                v.animate().alpha(1f).setDuration(220).start();
            }
            scroll.scrollTo(0, 0);
        }
        int liveVis = live ? View.VISIBLE : View.GONE;
        faceHero.setVisibility(live ? View.GONE : View.VISIBLE);
        connectCard.setVisibility(live ? View.GONE : View.VISIBLE);
        hero.setVisibility(liveVis);
        controls.setVisibility(liveVis);
        lowNotice.setVisibility(live && st.low ? View.VISIBLE : View.GONE);

        // Caméra, batterie, pastilles
        boolean ok = p == Phase.CONNECTED;
        lens.render(ok, cameraWanted(), st.cameraOn, statusLabel(Phase.RECONNECTING));
        gauge.set(st.pct, st.charging);
        set(batteryPct, st.pct >= 0 ? getString(R.string.pct_fmt, st.pct) : "--");
        set(batteryVolts, st.volts > 0 ? String.format(Locale.getDefault(), "%.2f V", st.volts) : "");
        batteryPct.setContentDescription(st.pct >= 0 ? getString(R.string.battery_fmt, st.pct, st.volts) : null);
        badge(badgeDocked, st.docked);
        badge(badgeCharging, st.charging);
        badge(badgePicked, st.pickedUp);
        badge(badgeCliff, st.cliff);

        // Commandes : actives seulement sur une liaison établie
        Ui.enable(joystick, ok);
        set(driveHint, getString(ok && st.docked ? R.string.docked_hint : R.string.drive_hint));
        driveHint.setTextColor(ok && st.docked ? Ui.INK : Ui.INK_MUTED);
        for (ValueTile t : new ValueTile[]{head, lift, speed, volume}) t.setEnabled(ok);
        boolean speaking = st.speaking || SystemClock.uptimeMillis() < sayBusyUntil;
        Ui.enable(sayBtn, ok && !speaking);
        set(sayBtn, getString(speaking && ok ? R.string.speaking : R.string.speak));
        Ui.enable(showBtn, ok);
        for (Swatch s : swatches) s.setEnabled(ok);

        // Écran allumé tant qu'une liaison est voulue
        if (wantLink != keepOn) {
            keepOn = wantLink;
            if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * N'écrit que si le texte change : render() tourne à chaque état reçu, et
     * un setText, même identique, relance la mise en page.
     */
    private static void set(TextView t, CharSequence text) {
        if (!TextUtils.equals(t.getText(), text)) t.setText(text);
    }

    private static void badge(TextView b, boolean on) {
        b.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    // =====================================================================
    // Fil de sondage : état toutes les 500 ms, images à ~15 i/s, Wi-Fi à 1 Hz
    // =====================================================================

    private final class Poller extends Thread {
        volatile boolean running = true;

        Poller() {
            super("cosmo-poll");
            setDaemon(true);
        }

        @Override
        public void run() {
            int seq = -1;
            long nextState = 0, nextWifi = 0, nextBind = 0;
            boolean wasBound = wifi.isBound();
            while (running) {
                long now = SystemClock.uptimeMillis();
                Py p = py;
                if (p != null && now >= nextState) {
                    nextState = now + 500;
                    try {
                        Robot r = Robot.parse(p.state());
                        ui.post(() -> onRobot(r));
                    } catch (Exception e) {
                        Log.w(TAG, "state: " + e.getMessage());
                    }
                }
                if (p != null && camActive) {
                    try {
                        Object[] f = p.frame(seq);
                        if (f != null) {
                            seq = (Integer) f[0];
                            byte[] jpeg = (byte[]) f[1];
                            Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                            if (b != null) ui.post(() -> {
                                if (started && camActive) lens.showFrame(b);
                            });
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "frame: " + e.getMessage());
                    }
                }
                if (now >= nextWifi) {
                    nextWifi = now + 1000;
                    boolean bound = wifi.stillThere();
                    if (wasBound && !bound) ui.post(MainActivity.this::onWifiLost);
                    // Liaison voulue mais réseau perdu : on guette son retour
                    // (le téléphone rejoint souvent seul un réseau enregistré).
                    if (!bound && wantLink && linkedOnce && now >= nextBind) {
                        nextBind = now + 2000;
                        bound = wifi.bindExisting();
                    }
                    wasBound = bound;
                }
                SystemClock.sleep(66);
            }
        }
    }

    // =====================================================================
    // Actions
    // =====================================================================

    private void toggleCamera() {
        if (phase != Phase.CONNECTED) return;
        boolean on = !cameraWanted();
        prefs.edit().putBoolean("camera", on).apply();
        overrideCamera(on);
        camActive = on;
        send("set_camera", on);
        render();
    }

    private void speak() {
        String text = sayField.getText().toString().trim();
        if (text.isEmpty() || phase != Phase.CONNECTED) return;
        if (!speech.isReady()) {
            notice(getString(R.string.tts_unavailable));
            return;
        }
        // Le temps que la synthèse produise le fichier, le bouton reste grisé.
        sayBusyUntil = SystemClock.uptimeMillis() + 1500;
        boolean ok = speech.synth(text, path -> send("say", path), why -> ui.post(() -> {
            sayBusyUntil = 0;
            notice(getString(R.string.tts_unavailable));
            render();
        }));
        if (!ok) {
            sayBusyUntil = 0;
            notice(getString(R.string.tts_unavailable));
        }
        render();
        ui.postDelayed(this::render, 1600);
    }

    private void showOnScreen() {
        String text = screenField.getText().toString().trim();
        if (text.isEmpty() || phase != Phase.CONNECTED) return;
        send("show_text", text);
        notice(getString(R.string.shown));
    }

    private void pickLight(int i) {
        light = i;
        prefs.edit().putInt("light", i).apply();
        for (int k = 0; k < swatches.length; k++) swatches[k].setChosen(k == i);
        lightName.setText(getString(LIGHT_NAMES[i]).toUpperCase(Locale.getDefault()));
        int[] rgb = LIGHT_RGB[i];
        if (phase == Phase.CONNECTED) send("set_backpack", rgb[0], rgb[1], rgb[2]);
    }

    private void openWifiSettings() {
        try {
            fromSettings = true;
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (ActivityNotFoundException e) {
            fromSettings = false;
            notice(getString(R.string.wifi_settings_missing));
        }
    }

    private void openRepo() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO)));
        } catch (ActivityNotFoundException e) {
            notice(getString(R.string.no_browser));
        }
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f == null) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        f.clearFocus();
    }

    // =====================================================================
    // Messages
    // =====================================================================

    private void showError(int res, String detail) {
        errorTitle.setText(res);
        boolean hasDetail = detail != null && !detail.trim().isEmpty();
        errorDetail.setText(hasDetail ? getString(R.string.detail_fmt, detail.trim()) : "");
        errorDetail.setVisibility(hasDetail ? View.VISIBLE : View.GONE);
        errorBox.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorBox.setVisibility(View.GONE);
    }

    /** Message passager, en bas de l'écran (le SnackBar flottant de G1 Extended). */
    private void notice(CharSequence text) {
        snack.setText(text);
        ui.removeCallbacks(hideSnack);
        if (snack.getVisibility() != View.VISIBLE) {
            snack.setVisibility(View.VISIBLE);
            snack.setAlpha(0f);
            snack.setTranslationY(Ui.dp(this, 16));
            snack.animate().alpha(1f).translationY(0f).setDuration(180).start();
        }
        ui.postDelayed(hideSnack, 3800);
    }

    private void hideSnack() {
        snack.animate().alpha(0f).translationY(Ui.dp(this, 16)).setDuration(160)
                .withEndAction(() -> snack.setVisibility(View.GONE)).start();
    }

    // =====================================================================
    // Fenêtre : bord à bord et marges système
    // =====================================================================

    /**
     * Android 15 impose le bord à bord à targetSdk 35 ; on l'adopte partout pour
     * n'avoir qu'un seul chemin, et chaque marge système est rendue en padding.
     */
    @SuppressWarnings("deprecation")
    private void edgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        w.setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
    }

    @SuppressWarnings("deprecation")
    private WindowInsets applyInsets(View v, WindowInsets in) {
        int l, t, r, b;
        boolean ime = false;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets k = in.getInsets(WindowInsets.Type.ime());
            l = bars.left;
            t = bars.top;
            r = bars.right;
            b = Math.max(bars.bottom, k.bottom);
            ime = k.bottom > bars.bottom;
        } else {
            // Avant Android 11, ce bas-là inclut déjà le clavier.
            l = in.getSystemWindowInsetLeft();
            t = in.getSystemWindowInsetTop();
            r = in.getSystemWindowInsetRight();
            b = in.getSystemWindowInsetBottom();
            ime = b > Ui.dp(this, 100);
        }
        root.setPadding(l, t, r, b);
        if (ime) ui.post(this::revealFocus);
        return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : in.consumeSystemWindowInsets();
    }

    /** Clavier ouvert : le champ actif remonte au-dessus. */
    private void revealFocus() {
        View f = getCurrentFocus();
        if (f == null) return;
        Rect r = new Rect(0, 0, f.getWidth(), f.getHeight() + Ui.dp(this, 24));
        f.requestRectangleOnScreen(r, false);
    }

    // =====================================================================
    // Construction de l'interface
    // =====================================================================

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.SURFACE);
        root.setOnApplyWindowInsetsListener(this::applyInsets);

        LinearLayout main = Ui.row(this);
        main.setGravity(Gravity.TOP);
        root.addView(main, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        int pad = Ui.dp(this, Ui.PAGE_PAD);
        leftPane = Ui.col(this);
        leftPane.setGravity(Gravity.CENTER_HORIZONTAL);
        leftPane.setPadding(pad, 0, pad / 2, pad);
        leftPane.setVisibility(View.GONE);
        main.addView(leftPane, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.25f));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        main.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        content = new Ui.Column(this, 600);
        content.setPadding(pad, 0, pad, Ui.dp(this, 28));
        scroll.addView(content, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        buildHeader();
        buildConnect();
        buildLive();
        buildControls();
        buildAbout();

        content.addView(header);
        content.addView(faceHero);
        content.addView(connectCard);
        content.addView(hero);
        content.addView(lowNotice);
        content.addView(controls);
        content.addView(about);

        buildSnack();
        buildLicences();
        setContentView(root);

        light = Math.max(0, Math.min(LIGHT_RGB.length - 1, prefs.getInt("light", 0)));
        pickLight(light);
    }

    /** Portrait : une colonne. Paysage connecté : caméra à gauche, commandes à droite. */
    private void arrange() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean two = land && isLive(phase);
        if (arranged && two == twoPane) return;
        arranged = true;
        twoPane = two;
        detach(header);
        detach(hero);
        detach(lowNotice);
        int pad = Ui.dp(this, Ui.PAGE_PAD);
        if (two) {
            leftPane.addView(header, Ui.matchWrap());
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            leftPane.addView(hero, hp);
            leftPane.addView(lowNotice, Ui.topMargin(this, Ui.GUTTER));
            setLensWeighted(true);
            leftPane.setVisibility(View.VISIBLE);
            content.setPadding(pad / 2, pad, pad, Ui.dp(this, 28));
        } else {
            leftPane.setVisibility(View.GONE);
            content.addView(header, 0);
            content.addView(hero, content.indexOfChild(connectCard) + 1, Ui.matchWrap());
            content.addView(lowNotice, content.indexOfChild(hero) + 1, Ui.topMargin(this, Ui.GUTTER));
            setLensWeighted(false);
            content.setPadding(pad, 0, pad, Ui.dp(this, 28));
        }
    }

    private void setLensWeighted(boolean weighted) {
        LinearLayout.LayoutParams lp = weighted
                ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                : Ui.matchWrap();
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lens.setLayoutParams(lp);
        hero.setGravity(weighted ? Gravity.CENTER_VERTICAL : Gravity.TOP);
    }

    private static void detach(View v) {
        ViewGroup parent = (ViewGroup) v.getParent();
        if (parent != null) parent.removeView(v);
    }

    private void buildHeader() {
        header = Ui.row(this);
        header.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14));
        // La police technique, espacée comme sur G1 Extended.
        TextView brand = Ui.mono(this, getString(R.string.app_name).toUpperCase(Locale.ROOT), 17, Ui.INK);
        brand.setLetterSpacing(0.18f);
        brand.setSingleLine(true);
        header.addView(brand, Ui.wrap());
        header.addView(Ui.spring(this));
        dot = new StatusDot(this);
        header.addView(dot);
        statusText = Ui.mono(this, "", 11, Ui.INK_MUTED);
        statusText.setLetterSpacing(0.1f);
        statusText.setSingleLine(true);
        LinearLayout.LayoutParams sp = Ui.wrap();
        sp.leftMargin = Ui.dp(this, 8);
        header.addView(statusText, sp);
    }

    private void buildConnect() {
        // Le « visage » : l'écran de Cozmo, au format 128 × 32, qui dit où on en est.
        faceHero = Ui.col(this);
        faceHero.setBackground(new Ui.MatrixTile(this));
        int p14 = Ui.dp(this, Ui.PAGE_PAD);
        faceHero.setPadding(p14, p14, p14, p14);
        Ui.Aspect face = new Ui.Aspect(this, 4f);
        face.setBackground(Ui.outline(this, Ui.GROUND, Ui.TILE, Ui.SCREEN_RADIUS));
        faceText = Ui.mono(this, "", 13, Ui.INK);
        faceText.setLetterSpacing(0.12f);
        faceText.setGravity(Gravity.CENTER);
        faceText.setPadding(p14, 0, p14, 0);
        faceText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        face.addView(faceText, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        faceHero.addView(face, Ui.matchWrap());
        faceHero.setLayoutParams(Ui.matchWrap());

        connectCard = Ui.tile(this);
        connectCard.setLayoutParams(Ui.topMargin(this, Ui.GUTTER));
        connectCard.addView(Ui.title(this, R.string.connect_title));

        int[] steps = {R.string.step_1, R.string.step_2, R.string.step_3};
        for (int i = 0; i < steps.length; i++) {
            LinearLayout r = Ui.row(this);
            r.setGravity(Gravity.TOP);
            TextView n = Ui.mono(this, String.format(Locale.ROOT, "%02d", i + 1), 13, Ui.INK_MUTED);
            n.setMinWidth(Ui.dp(this, 32));
            r.addView(n, Ui.wrap());
            r.addView(Ui.body(this, getString(steps[i])), Ui.weight(1));
            connectCard.addView(r, Ui.topMargin(this, i == 0 ? 16 : 12));
        }

        TextView label = Ui.small(this, getString(R.string.wifi_password));
        connectCard.addView(label, Ui.topMargin(this, 22));
        password = Ui.field(this, R.string.wifi_password);
        password.setHint("");
        // Visible : il est recopié depuis le visage du robot, autant le relire.
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password.setTypeface(Ui.MONO);
        password.setLetterSpacing(0.08f);
        password.setImeOptions(EditorInfo.IME_ACTION_GO);
        password.setText(prefs.getString("password", ""));
        password.setContentDescription(getString(R.string.wifi_password));
        password.setOnEditorActionListener((v, id, e) -> {
            if (pyReady) beginConnect();
            return true;
        });
        connectCard.addView(password, Ui.topMargin(this, 8));

        TextView hint = Ui.small(this, getString(R.string.wifi_settings_hint));
        connectCard.addView(hint, Ui.topMargin(this, 10));

        // Erreur lisible, dans la carte même (pas de message qui disparaît).
        errorBox = Ui.col(this);
        errorBox.setBackground(Ui.rounded(this, Ui.TILE_ACTIVE, Ui.RADIUS));
        int p16 = Ui.dp(this, 16);
        errorBox.setPadding(p16, p16, p16, p16);
        errorBox.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        errorTitle = Ui.text(this, "", 14, Ui.INK);
        errorTitle.setTypeface(Ui.MEDIUM);
        errorBox.addView(errorTitle, Ui.matchWrap());
        errorDetail = Ui.mono(this, "", 11, Ui.INK_MUTED);
        errorBox.addView(errorDetail, Ui.topMargin(this, 6));
        errorBox.setVisibility(View.GONE);
        connectCard.addView(errorBox, Ui.topMargin(this, 16));

        connectBtn = Ui.row(this);
        connectBtn.setGravity(Gravity.CENTER);
        connectBtn.setBackground(Ui.ripple(this, Ui.rounded(this, Ui.TILE_ACTIVE, Ui.RADIUS), Ui.RADIUS));
        connectBtn.setMinimumHeight(Ui.dp(this, 52));
        connectBtn.setPadding(p16, 0, p16, 0);
        connectBtn.setClickable(true);
        connectBtn.setFocusable(true);
        connectSpin = Ui.spinner(this);
        connectSpin.setVisibility(View.GONE);
        connectBtn.addView(connectSpin);
        connectLabel = Ui.text(this, getString(R.string.connect), 15, Ui.INK);
        connectLabel.setTypeface(Ui.MEDIUM);
        connectLabel.setSingleLine(true);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.leftMargin = Ui.dp(this, 12);
        connectBtn.addView(connectLabel, lp);
        connectBtn.setOnClickListener(v -> beginConnect());
        connectCard.addView(connectBtn, Ui.topMargin(this, 18));

        settingsBtn = Ui.outlined(this, R.string.wifi_settings);
        settingsBtn.setOnClickListener(v -> openWifiSettings());
        connectCard.addView(settingsBtn, Ui.topMargin(this, Ui.GUTTER));
    }

    private void buildLive() {
        // Tuile principale : l'écran de la caméra sur la trame de points.
        hero = Ui.col(this);
        hero.setBackground(new Ui.MatrixTile(this));
        int p14 = Ui.dp(this, Ui.PAGE_PAD);
        hero.setPadding(p14, p14, p14, p14);
        hero.setLayoutParams(Ui.matchWrap());
        lens = new LensView(this, v -> toggleCamera());
        hero.addView(lens);
        setLensWeighted(false);

        LinearLayout status = Ui.row(this);
        badges = new Ui.Flow(this, 6);
        badgeDocked = Ui.chip(this, getString(R.string.on_charger));
        badgeCharging = Ui.chip(this, getString(R.string.charging));
        badgePicked = Ui.chip(this, getString(R.string.picked_up));
        badgeCliff = Ui.chip(this, getString(R.string.cliff));
        for (TextView b : new TextView[]{badgeDocked, badgeCharging, badgePicked, badgeCliff}) {
            b.setVisibility(View.GONE);
            badges.addView(b);
        }
        badges.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.addView(badges, Ui.weight(1));
        gauge = new BatteryGauge(this);
        LinearLayout.LayoutParams gp = Ui.wrap();
        gp.leftMargin = Ui.dp(this, 10);
        status.addView(gauge, gp);
        batteryPct = Ui.mono(this, "--", 13, Ui.INK);
        LinearLayout.LayoutParams pp = Ui.wrap();
        pp.leftMargin = Ui.dp(this, 7);
        status.addView(batteryPct, pp);
        batteryVolts = Ui.mono(this, "", 12, Ui.INK_MUTED);
        LinearLayout.LayoutParams vp = Ui.wrap();
        vp.leftMargin = Ui.dp(this, 8);
        status.addView(batteryVolts, vp);
        hero.addView(status, Ui.topMargin(this, 10));

        lowNotice = Ui.text(this, getString(R.string.battery_low), 13, Ui.INK);
        lowNotice.setBackground(Ui.rounded(this, Ui.TILE_ACTIVE, Ui.RADIUS));
        int p16 = Ui.dp(this, 16);
        lowNotice.setPadding(p16, Ui.dp(this, 14), p16, Ui.dp(this, 14));
        lowNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        lowNotice.setLayoutParams(Ui.topMargin(this, Ui.GUTTER));
        lowNotice.setVisibility(View.GONE);
    }

    private void buildControls() {
        controls = Ui.col(this);
        controls.setLayoutParams(Ui.matchWrap());

        // --- Conduite
        controls.addView(Ui.section(this, R.string.drive));
        LinearLayout drive = Ui.tile(this);
        joystick = new JoystickView(this);
        joystick.setListener(new JoystickView.Listener() {
            @Override public void onMove(float x, float y) {
                // Petite zone morte : un doigt posé au centre ne fait pas ramper le robot.
                if (Math.hypot(x, y) < 0.08) {
                    x = 0f;
                    y = 0f;
                }
                jx = x;
                jy = y;
                if (!driving) {
                    driving = true;
                    ui.post(driveTick);
                } else {
                    Py p = py;
                    if (p != null && phase == Phase.CONNECTED) p.drive(x, y);
                }
            }

            @Override public void onRelease() {
                stopDriving();
            }
        });
        drive.addView(joystick, Ui.matchWrap());
        driveHint = Ui.small(this, getString(R.string.drive_hint));
        driveHint.setGravity(Gravity.CENTER);
        drive.addView(driveHint, Ui.topMargin(this, 12));
        controls.addView(drive);

        // --- Tête, fourche, vitesse, volume : les tuiles sont les curseurs.
        head = new ValueTile(this, getString(R.string.head), -25, 44, 1, prefs.getInt("head", 10),
                v -> (v > 0 ? "+" : "") + v + "°", sender("set_head", "head"));
        lift = new ValueTile(this, getString(R.string.lift), 0, 100, 1, prefs.getInt("lift", 0),
                v -> getString(R.string.pct_fmt, v), sender("set_lift", "lift"));
        speed = new ValueTile(this, getString(R.string.speed), 20, 220, 10, prefs.getInt("speed", 120),
                v -> v + " mm/s", sender("set_speed", "speed"));
        volume = new ValueTile(this, getString(R.string.volume), 0, 100, 5, prefs.getInt("volume", 80),
                v -> getString(R.string.pct_fmt, v), sender("set_volume", "volume"));
        controls.addView(pair(head, lift), Ui.topMargin(this, Ui.GUTTER));
        controls.addView(pair(speed, volume), Ui.topMargin(this, Ui.GUTTER));

        // --- Voix et écran
        controls.addView(Ui.section(this, R.string.voice_screen));
        LinearLayout voice = Ui.tile(this);
        sayField = Ui.field(this, R.string.speak_hint);
        sayField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        sayField.setOnEditorActionListener((v, id, e) -> {
            speak();
            return true;
        });
        sayBtn = smallButton(R.string.speak);
        sayBtn.setOnClickListener(v -> speak());
        voice.addView(fieldRow(sayField, sayBtn));

        screenField = Ui.field(this, R.string.screen_hint);
        screenField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SCREEN_CHARS)});
        screenField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        screenField.setOnEditorActionListener((v, id, e) -> {
            showOnScreen();
            return true;
        });
        showBtn = smallButton(R.string.show);
        showBtn.setOnClickListener(v -> showOnScreen());
        voice.addView(fieldRow(screenField, showBtn), Ui.topMargin(this, Ui.GUTTER));
        screenCount = Ui.mono(this, "0/" + SCREEN_CHARS, 11, Ui.INK_FAINT);
        screenCount.setGravity(Gravity.END);
        screenCount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        voice.addView(screenCount, Ui.topMargin(this, 6));
        screenField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                screenCount.setText(s.length() + "/" + SCREEN_CHARS);
            }
        });
        controls.addView(voice);

        // --- LEDs du dos
        LinearLayout lightsHead = Ui.row(this);
        TextView ls = Ui.section(this, R.string.lights);
        lightsHead.addView(ls, Ui.weight(1));
        lightName = Ui.mono(this, "", 11, Ui.INK_MUTED);
        lightName.setLetterSpacing(0.1f);
        lightName.setPadding(0, Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 10));
        lightsHead.addView(lightName, Ui.wrap());
        controls.addView(lightsHead, Ui.matchWrap());
        LinearLayout lights = Ui.tile(this);
        int p8 = Ui.dp(this, 8);
        lights.setPadding(p8, p8, p8, p8);
        LinearLayout swRow = Ui.row(this);
        swatches = new Swatch[LIGHT_SHOWN.length];
        for (int i = 0; i < swatches.length; i++) {
            final int k = i;
            swatches[i] = new Swatch(this, LIGHT_SHOWN[i], i == 0, getString(LIGHT_NAMES[i]));
            swatches[i].setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                pickLight(k);
            });
            swRow.addView(swatches[i], Ui.weight(1));
        }
        lights.addView(swRow, Ui.matchWrap());
        controls.addView(lights);

        // --- Déconnexion : l'action secondaire, cerclée, comme sur G1 Extended.
        disconnectBtn = Ui.outlined(this, R.string.disconnect);
        disconnectBtn.setOnClickListener(v -> disconnect());
        controls.addView(disconnectBtn, Ui.topMargin(this, 22));
    }

    /** Envoi d'un curseur : au plus toutes les 120 ms pendant le geste, et au lâcher. */
    private ValueTile.Listener sender(String fn, String key) {
        final long[] last = {0};
        return (v, done) -> {
            long now = SystemClock.uptimeMillis();
            if (done || now - last[0] >= 120) {
                last[0] = now;
                if (phase == Phase.CONNECTED) send(fn, v);
            }
            if (done) prefs.edit().putInt(key, v).apply();
        };
    }

    private LinearLayout pair(View a, View b) {
        LinearLayout r = Ui.row(this);
        r.addView(a, Ui.weight(1));
        r.addView(Ui.space(this, Ui.GUTTER, 1));
        r.addView(b, Ui.weight(1));
        return r;
    }

    private TextView smallButton(int res) {
        TextView b = Ui.filled(this, res);
        b.setMinHeight(Ui.dp(this, 48));
        b.setMinWidth(Ui.dp(this, 88));
        b.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        b.setSingleLine(true);
        return b;
    }

    private LinearLayout fieldRow(EditText field, View button) {
        LinearLayout r = Ui.row(this);
        r.addView(field, Ui.weight(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        lp.leftMargin = Ui.dp(this, Ui.GUTTER);
        r.addView(button, lp);
        return r;
    }

    private void buildAbout() {
        about = Ui.tile(this);
        about.setPadding(0, 0, 0, 0);
        about.setLayoutParams(Ui.topMargin(this, 28));
        LayoutTransition lt = new LayoutTransition();
        lt.setDuration(180);
        about.setLayoutTransition(lt);

        int p = Ui.dp(this, Ui.TILE_PAD);
        LinearLayout top = Ui.row(this);
        top.setPadding(p, p, p, p);
        top.setBackground(Ui.ripple(this, null, Ui.RADIUS));
        top.setClickable(true);
        top.setFocusable(true);
        top.addView(Ui.title(this, R.string.about), Ui.weight(1));
        aboutVersion = Ui.mono(this, version(), 12, Ui.INK_MUTED);
        top.addView(aboutVersion, Ui.wrap());
        about.addView(top, Ui.matchWrap());

        aboutMore = Ui.col(this);
        aboutMore.setPadding(p, 0, p, Ui.dp(this, 8));
        TextView text = Ui.text(this, getString(R.string.about_text, version()), 13, Ui.INK_MUTED);
        aboutMore.addView(text, Ui.matchWrap());
        aboutMore.addView(linkRow(getString(R.string.source_code), REPO_LABEL, v -> openRepo()),
                Ui.topMargin(this, 12));
        aboutMore.addView(linkRow(getString(R.string.licences), getString(R.string.licences_hint),
                v -> openLicences()), Ui.matchWrap());
        aboutMore.setVisibility(View.GONE);
        about.addView(aboutMore, Ui.matchWrap());

        top.setOnClickListener(v -> aboutMore.setVisibility(
                aboutMore.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    private View linkRow(String title, String sub, View.OnClickListener l) {
        LinearLayout r = Ui.col(this);
        int v = Ui.dp(this, 12);
        r.setPadding(0, v, 0, v);
        r.setBackground(Ui.ripple(this, null, Ui.SCREEN_RADIUS));
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(l);
        r.addView(Ui.body(this, title));
        TextView s = Ui.mono(this, sub, 11, Ui.INK_FAINT);
        r.addView(s, Ui.topMargin(this, 3));
        return r;
    }

    /** Version lue dans le paquet installé (pas de BuildConfig ici). */
    @SuppressWarnings("deprecation")
    private String version() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return pi.versionName + " (" + code + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void buildSnack() {
        snack = Ui.text(this, "", 14, Ui.INK);
        snack.setBackground(Ui.rounded(this, Ui.TILE_ACTIVE, Ui.RADIUS));
        int p = Ui.dp(this, 16);
        snack.setPadding(p, Ui.dp(this, 14), p, Ui.dp(this, 14));
        snack.setElevation(Ui.dp(this, 6));
        snack.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        snack.setVisibility(View.GONE);
        snack.setOnClickListener(v -> hideSnack());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int m = Ui.dp(this, Ui.PAGE_PAD);
        lp.setMargins(m, m, m, m);
        root.addView(snack, lp);
    }

    // =====================================================================
    // Licences open source
    // =====================================================================

    private void buildLicences() {
        licencesPanel = new FrameLayout(this);
        licencesPanel.setBackgroundColor(Ui.SURFACE);
        licencesPanel.setClickable(true);   // ne laisse pas passer les touches dessous
        licencesPanel.setVisibility(View.GONE);

        Ui.Column col = new Ui.Column(this, 680);
        int pad = Ui.dp(this, Ui.PAGE_PAD);
        col.setPadding(pad, 0, pad, 0);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 14));
        TextView title = Ui.text(this, getString(R.string.licences), 20, Ui.INK);
        title.setTypeface(Ui.MEDIUM);
        bar.addView(title, Ui.weight(1));
        TextView close = Ui.outlined(this, R.string.close);
        close.setMinHeight(Ui.dp(this, 40));
        close.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        close.setOnClickListener(v -> closeLicences());
        bar.addView(close, Ui.wrap());
        col.addView(bar, Ui.matchWrap());

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout tile = Ui.tile(this);
        licencesText = Ui.text(this, "", 13, Ui.INK_MUTED);
        licencesText.setLineSpacing(0, 1.3f);
        licencesText.setTextIsSelectable(true);
        tile.addView(licencesText, Ui.matchWrap());
        LinearLayout inner = Ui.col(this);
        inner.setPadding(0, 0, 0, Ui.dp(this, 28));
        inner.addView(tile, Ui.matchWrap());
        sv.addView(inner);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        licencesPanel.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        root.addView(licencesPanel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void openLicences() {
        if (licencesText.length() == 0) licencesText.setText(readRaw(R.raw.third_party));
        hideKeyboard();
        licencesPanel.setVisibility(View.VISIBLE);
        licencesPanel.setAlpha(0f);
        licencesPanel.setTranslationY(Ui.dp(this, 24));
        licencesPanel.animate().alpha(1f).translationY(0f).setDuration(200).start();
    }

    private void closeLicences() {
        licencesPanel.animate().alpha(0f).translationY(Ui.dp(this, 24)).setDuration(160)
                .withEndAction(() -> licencesPanel.setVisibility(View.GONE)).start();
    }

    private String readRaw(int res) {
        try (InputStream in = getResources().openRawResource(res)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
