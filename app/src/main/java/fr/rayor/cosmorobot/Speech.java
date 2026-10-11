package fr.rayor.cosmorobot;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.util.function.Consumer;

/**
 * Synthèse vocale du téléphone, écrite dans un WAV que Python repasse au format
 * de Cozmo. Remplace Piper, qui ne tourne pas sur Android ; en échange, toutes
 * les langues installées sur le téléphone sont disponibles.
 */
final class Speech {
    private final File out;
    private TextToSpeech tts;
    private volatile boolean ready;
    private Consumer<String> pending;

    Speech(Context ctx) {
        out = new File(ctx.getCacheDir(), "say.wav");
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> ready = status == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }

            @Override public void onDone(String id) {
                Consumer<String> cb = pending;
                pending = null;
                if (cb != null) cb.accept(out.getAbsolutePath());
            }

            @Override public void onError(String id) {
                pending = null;
            }
        });
    }

    boolean isReady() {
        return ready;
    }

    /** onWav est appelé hors du fil de l'interface, avec le chemin du WAV. */
    boolean synth(String text, Consumer<String> onWav) {
        if (!ready || text.trim().isEmpty()) return false;
        pending = onWav;
        int r = tts.synthesizeToFile(text, new Bundle(), out, "say-" + System.currentTimeMillis());
        return r == TextToSpeech.SUCCESS;
    }

    void shutdown() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }
}
