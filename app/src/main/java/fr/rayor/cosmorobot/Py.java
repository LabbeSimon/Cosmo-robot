package fr.rayor.cosmorobot;

import android.content.Context;
import android.util.Log;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Passerelle vers cosmo_core.py. Aucun appel Python ne part du fil de l'interface.
 *
 * Deux files séparées : la connexion peut bloquer 20 s (attente du robot)
 * pendant lesquelles les commandes rapides ne doivent pas s'empiler derrière elle.
 *
 * Python démarre en arrière-plan : au premier lancement, Chaquopy extrait la
 * bibliothèque standard et les .so de numpy, ce qui prend plusieurs secondes et
 * figerait l'interface (ANR) si le constructeur l'attendait. Tant que ce n'est
 * pas fini, state() renvoie un état « ready: false » et frame() null ; les
 * commandes, elles, attendent leur tour dans leur file.
 */
final class Py {
    private static final String TAG = "CosmoPy";

    private final FutureTask<PyObject> boot;
    private final ExecutorService link = Executors.newSingleThreadExecutor(r -> new Thread(r, "cosmo-link"));
    private final ExecutorService cmd = Executors.newSingleThreadExecutor(r -> new Thread(r, "cosmo-cmd"));

    // Joystick et curseurs envoient plus vite que le robot n'absorbe : on ne
    // garde que la dernière valeur, et une seule tâche en attente à la fois.
    private final AtomicBoolean drivePending = new AtomicBoolean();
    private volatile float driveX, driveY;

    // Incrémenté à chaque disconnect() : une connexion demandée avant, encore
    // dans la file, n'est pas lancée (sinon le robot se rebrancherait après
    // qu'on l'a lâché).
    private final AtomicInteger linkGen = new AtomicInteger();

    Py(Context ctx) {
        Context app = ctx.getApplicationContext();
        String files = app.getFilesDir().getAbsolutePath();
        boot = new FutureTask<>(() -> {
            // Même verrou que Python.start() : deux Py créés de front ne
            // doivent pas démarrer l'interpréteur deux fois (exception).
            synchronized (Python.class) {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(app));
            }
            PyObject m = Python.getInstance().getModule("cosmo_core");
            m.callAttr("init", files);
            return m;
        });
        link.execute(boot);
    }

    /** Module prêt, en l'attendant si besoin (jamais depuis l'interface). */
    private PyObject core() {
        try {
            return boot.get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Python: " + c.getMessage(), c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** Module s'il est prêt, sinon null, sans jamais attendre. */
    private PyObject coreIfReady() {
        if (!boot.isDone()) return null;
        try {
            return boot.get();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Connexion ; onDone reçoit null si tout va bien, sinon le message d'erreur
     * ("cancelled" si disconnect() est arrivé entre-temps). Appelé hors du fil
     * de l'interface.
     */
    void connect(Consumer<String> onDone) {
        final int gen = linkGen.get();
        submit(link, () -> {
            String err = null;
            if (gen != linkGen.get()) {
                err = "cancelled";
            } else {
                try {
                    core().callAttr("connect");
                } catch (PyException e) {
                    String m = String.valueOf(e.getMessage());
                    err = gen != linkGen.get() || m.contains("Cancelled") ? "cancelled" : m;
                    Log.w(TAG, "connect: " + m);
                } catch (RuntimeException e) {
                    err = String.valueOf(e.getMessage());
                    Log.w(TAG, "connect", e);
                }
            }
            if (onDone != null) onDone.accept(err);
        });
    }

    void disconnect() {
        linkGen.incrementAndGet();
        // Pas dans la file « link » : elle peut être occupée par la connexion
        // que cette déconnexion doit justement interrompre.
        submit(cmd, () -> call("disconnect"));
    }

    void drive(float x, float y) {
        driveX = x;
        driveY = y;
        if (drivePending.compareAndSet(false, true)) {
            if (!submit(cmd, () -> {
                drivePending.set(false);
                call("drive", driveX, driveY);
            })) {
                drivePending.set(false);
            }
        }
    }

    void stop() {
        submit(cmd, () -> call("stop"));
    }

    /** Commande rapide, sans retour ; les erreurs ne vont qu'au journal. */
    void send(String fn, Object... args) {
        submit(cmd, () -> call(fn, args));
    }

    /** Une commande arrivée après shutdown() est ignorée au lieu de planter. */
    private static boolean submit(ExecutorService ex, Runnable r) {
        try {
            ex.execute(r);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void call(String fn, Object... args) {
        try (PyObject r = core().callAttr(fn, args)) {
            // résultat ignoré, libéré tout de suite
        } catch (PyException e) {
            Log.w(TAG, fn + ": " + e.getMessage());
        } catch (RuntimeException e) {
            Log.w(TAG, fn, e);
        }
    }

    // ------------------------------------------------------------- sondages
    // Appelés depuis le fil de sondage de l'activité, jamais depuis l'interface.

    /** JSON de cosmo_core.state() ; champ ready=false tant que Python démarre. */
    String state() {
        PyObject m = coreIfReady();
        if (m == null) return notReady();
        try (PyObject r = m.callAttr("state")) {
            return r.toString();
        } catch (PyException e) {
            Log.w(TAG, "state: " + e.getMessage());
            return notReady();
        }
    }

    /** Mêmes champs que cosmo_core.state(), valeurs par défaut. */
    private String notReady() {
        String err = "";
        if (boot.isDone()) {
            try {
                boot.get();
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                err = "Python: " + c.getMessage();
            }
        }
        try {
            return new JSONObject()
                    .put("battery_v", 0.0).put("battery_pct", 0).put("docked", false)
                    .put("charging", false).put("picked_up", false).put("cliff", false)
                    .put("low", false).put("connected", false).put("wanted", false)
                    .put("camera_wanted", false).put("camera_on", false)
                    .put("speaking", false).put("error", err).put("ready", false)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Dernière image si plus récente que lastSeq : {seq, jpeg}, sinon null. */
    Object[] frame(int lastSeq) {
        PyObject m = coreIfReady();
        if (m == null) return null;
        try (PyObject r = m.callAttr("frame", lastSeq)) {
            if (r == null) return null;
            List<PyObject> l = r.asList();
            try (PyObject seq = l.get(0); PyObject jpeg = l.get(1)) {
                return new Object[]{seq.toInt(), jpeg.toJava(byte[].class)};
            }
        } catch (PyException e) {
            Log.w(TAG, "frame: " + e.getMessage());
            return null;
        }
    }

    /**
     * Relâche le robot puis arrête les files. Sans la déconnexion, un robot
     * resté branché garderait l'appli fermée en vie côté Python : le chien de
     * garde le reconnecterait sans fin et Cozmo ne dormirait plus.
     */
    void shutdown() {
        disconnect();
        link.shutdown();
        cmd.shutdown();
    }
}
