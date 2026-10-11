package fr.rayor.cosmorobot;

import android.content.Context;
import android.util.Log;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Passerelle vers cosmo_core.py. Aucun appel Python ne part du fil de l'interface.
 *
 * Deux files séparées : la connexion peut bloquer 15 s (wait_for_robot) pendant
 * lesquelles les commandes rapides ne doivent pas s'empiler derrière elle.
 */
final class Py {
    private static final String TAG = "CosmoPy";

    private final PyObject core;
    private final ExecutorService link = Executors.newSingleThreadExecutor();
    private final ExecutorService cmd = Executors.newSingleThreadExecutor();

    // Joystick et curseurs envoient plus vite que le robot n'absorbe : on ne
    // garde que la dernière valeur, et une seule tâche en attente à la fois.
    private final AtomicBoolean drivePending = new AtomicBoolean();
    private volatile float driveX, driveY;

    Py(Context ctx) {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(ctx.getApplicationContext()));
        core = Python.getInstance().getModule("cosmo_core");
        core.callAttr("init", ctx.getFilesDir().getAbsolutePath());
    }

    /** Connexion ; onDone reçoit null si tout va bien, sinon le message d'erreur. */
    void connect(Consumer<String> onDone) {
        link.execute(() -> {
            String err = null;
            try {
                core.callAttr("connect");
            } catch (PyException e) {
                err = e.getMessage();
                Log.w(TAG, "connect", e);
            }
            onDone.accept(err);
        });
    }

    void disconnect() {
        link.execute(() -> call("disconnect"));
    }

    void drive(float x, float y) {
        driveX = x;
        driveY = y;
        if (drivePending.compareAndSet(false, true)) {
            cmd.execute(() -> {
                drivePending.set(false);
                call("drive", driveX, driveY);
            });
        }
    }

    void stop() {
        cmd.execute(() -> call("stop"));
    }

    /** Commande rapide, sans retour ; les erreurs ne vont qu'au journal. */
    void send(String fn, Object... args) {
        cmd.execute(() -> call(fn, args));
    }

    private void call(String fn, Object... args) {
        try {
            core.callAttr(fn, args);
        } catch (PyException e) {
            Log.w(TAG, fn + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- sondages
    // Appelés depuis le fil de sondage de l'activité, jamais depuis l'interface.

    String state() {
        return core.callAttr("state").toString();
    }

    /** Dernière image si plus récente que lastSeq : {seq, jpeg}, sinon null. */
    Object[] frame(int lastSeq) {
        PyObject r = core.callAttr("frame", lastSeq);
        if (r == null) return null;
        List<PyObject> l = r.asList();
        return new Object[]{l.get(0).toInt(), l.get(1).toJava(byte[].class)};
    }

    void shutdown() {
        link.shutdown();
        cmd.shutdown();
    }
}
