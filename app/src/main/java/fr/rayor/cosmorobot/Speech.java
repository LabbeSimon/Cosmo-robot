package fr.rayor.cosmorobot;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Synthèse vocale du téléphone, écrite dans un WAV que Python repasse au format
 * de Cozmo. Remplace Piper, qui ne tourne pas sur Android ; en échange, toutes
 * les langues installées sur le téléphone sont disponibles.
 *
 * Chaque énoncé a son identifiant, son fichier et son rappel : avec un seul
 * rappel partagé, deux phrases tapées coup sur coup se croisaient (le onDone de
 * la première livrait le fichier, encore en cours d'écriture, de la seconde).
 */
final class Speech {
    private static final String TAG = "CosmoSpeech";
    // Fichiers en rotation : un fichier n'est réécrit que SLOTS énoncés plus
    // tard, bien après que Python l'a lu (quelques millisecondes).
    private static final int SLOTS = 8;
    // En deçà, le moteur n'a écrit qu'un en-tête : rien à jouer.
    private static final long MIN_WAV_BYTES = 64;

    private static final class Job {
        final File file;
        final Consumer<String> onWav;
        final Consumer<String> onError;

        Job(File file, Consumer<String> onWav, Consumer<String> onError) {
            this.file = file;
            this.onWav = onWav;
            this.onError = onError;
        }
    }

    private final File dir;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile TextToSpeech tts;
    private volatile boolean ready;

    Speech(Context ctx) {
        dir = ctx.getCacheDir();
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> ready = status == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }

            @Override public void onDone(String id) {
                Job j = jobs.remove(id);
                if (j == null) return;
                if (j.file.length() < MIN_WAV_BYTES) {
                    fail(j, "empty audio");
                } else {
                    j.onWav.accept(j.file.getAbsolutePath());
                }
            }

            @Override @SuppressWarnings("deprecation")
            public void onError(String id) {
                Job j = jobs.remove(id);
                if (j != null) fail(j, "tts error");
            }

            @Override public void onError(String id, int code) {
                Job j = jobs.remove(id);
                if (j != null) fail(j, "tts error " + code);
            }

            @Override public void onStop(String id, boolean interrupted) {
                Job j = jobs.remove(id);
                if (j != null) fail(j, "tts stopped");
            }
        });
    }

    private static void fail(Job j, String why) {
        Log.w(TAG, why);
        if (j.onError != null) j.onError.accept(why);
    }

    boolean isReady() {
        return ready && tts != null;
    }

    /** onWav est appelé hors du fil de l'interface, avec le chemin du WAV. */
    boolean synth(String text, Consumer<String> onWav) {
        return synth(text, onWav, null);
    }

    /**
     * Comme synth(text, onWav), avec en plus onError (hors du fil de
     * l'interface, avec une courte raison) si le moteur échoue en route.
     */
    boolean synth(String text, Consumer<String> onWav, Consumer<String> onError) {
        TextToSpeech t = tts;
        if (!ready || t == null || text == null || onWav == null) return false;
        text = text.trim();
        if (text.isEmpty()) return false;
        int max = TextToSpeech.getMaxSpeechInputLength();
        if (text.length() > max) text = text.substring(0, max);
        int n = seq.getAndIncrement();
        String id = "say-" + n;
        File out = new File(dir, "say-" + Math.floorMod(n, SLOTS) + ".wav");
        jobs.put(id, new Job(out, onWav, onError));
        int r = t.synthesizeToFile(text, new Bundle(), out, id);
        if (r != TextToSpeech.SUCCESS) {
            jobs.remove(id);
            return false;
        }
        return true;
    }

    void shutdown() {
        TextToSpeech t = tts;
        tts = null;
        ready = false;
        jobs.clear();
        if (t != null) {
            t.stop();
            t.shutdown();
        }
        for (int i = 0; i < SLOTS; i++) {
            // Fichiers temporaires : ni la phrase ni le son n'ont à traîner.
            //noinspection ResultOfMethodCallIgnored
            new File(dir, "say-" + i + ".wav").delete();
        }
    }
}
