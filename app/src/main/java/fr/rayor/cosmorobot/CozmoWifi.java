package fr.rayor.cosmorobot;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.PatternMatcher;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Le Wi-Fi de Cozmo n'a pas d'accès internet : Android continue alors d'envoyer
 * le trafic par la 4G, et les paquets UDP vers 172.31.1.1 partent dans le vide.
 * On lie donc tout le processus à ce réseau (bindProcessToNetwork), ce qui vaut
 * aussi pour les sockets ouverts par Python.
 *
 * Deux chemins :
 * - réseau déjà rejoint dans les réglages : on le reconnaît à son adresse
 *   172.31.1.x, sans permission de localisation (lire le SSID en exigerait une) ;
 * - Android 10+ : demande au système un réseau Cozmo_… réservé à l'appli, avec
 *   le mot de passe affiché par le robot. Le téléphone garde son internet ailleurs.
 *   La demande reste active : si le robot redémarre, son Wi-Fi disparaît puis
 *   revient, et onJoined() est rappelé sans rien refaire.
 *
 * Les sockets de pycozmo sont liés au réseau du moment de leur création : après
 * un onLost()/onJoined(), c'est la reconnexion de cosmo_core (nouveau client,
 * nouveau socket) qui reprend la main.
 */
final class CozmoWifi {
    private static final String TAG = "CozmoWifi";

    /** Rappels émis hors du fil de l'interface. */
    interface Listener {
        void onJoined();
        void onFailed();
        void onLost();
    }

    private final ConnectivityManager cm;
    private volatile ConnectivityManager.NetworkCallback callback;
    private volatile Network bound;

    CozmoWifi(Context ctx) {
        cm = ctx.getApplicationContext().getSystemService(ConnectivityManager.class);
    }

    boolean isBound() {
        return bound != null;
    }

    /** Cherche un Wi-Fi déjà connecté qui soit celui de Cozmo, et s'y lie. */
    boolean bindExisting() {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            if (isCozmo(cm.getLinkProperties(n)) && cm.bindProcessToNetwork(n)) {
                bound = n;
                return true;
            }
        }
        return false;
    }

    static boolean canJoin() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** Android 10+ : le système affiche sa propre fenêtre de choix du réseau. */
    void join(String password, Listener l) {
        if (!canJoin()) {
            l.onFailed();
            return;
        }
        release();
        WifiNetworkSpecifier spec;
        try {
            spec = new WifiNetworkSpecifier.Builder()
                    .setSsidPattern(new PatternMatcher("Cozmo_", PatternMatcher.PATTERN_PREFIX))
                    .setWpa2Passphrase(password == null ? "" : password.trim())
                    .build();
        } catch (IllegalArgumentException e) {
            // Mot de passe WPA2 hors de 8 à 63 caractères : le Builder lève.
            Log.w(TAG, "join: " + e.getMessage());
            l.onFailed();
            return;
        }
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build();
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            // Un rappel déjà remplacé par release()/join() peut encore arriver
            // (fil de ConnectivityManager) : il ne doit plus rien lier.
            private boolean current() {
                return callback == this;
            }

            @Override public void onAvailable(Network n) {
                if (!current()) return;
                if (cm.bindProcessToNetwork(n)) {
                    bound = n;
                    l.onJoined();
                } else {
                    l.onFailed();
                }
            }

            @Override public void onUnavailable() {
                if (!current()) return;
                // Demande expirée ou refusée : le système l'a déjà retirée.
                callback = null;
                l.onFailed();
            }

            @Override public void onLost(Network n) {
                if (!current() || !n.equals(bound)) return;
                cm.bindProcessToNetwork(null);
                bound = null;
                l.onLost();
            }
        };
        callback = cb;
        try {
            cm.requestNetwork(req, cb, 60_000);
        } catch (RuntimeException e) {
            // SecurityException (permission retirée) ou trop de demandes en cours.
            Log.w(TAG, "requestNetwork", e);
            callback = null;
            l.onFailed();
        }
    }

    /** Pour le chemin « réglages », qui n'a pas de rappel : vérifié au sondage. */
    boolean stillThere() {
        Network n = bound;
        if (n == null) return false;
        if (cm.getNetworkCapabilities(n) != null) return true;
        if (bound == n) {
            cm.bindProcessToNetwork(null);
            bound = null;
        }
        return false;
    }

    void release() {
        ConnectivityManager.NetworkCallback cb = callback;
        callback = null;
        if (cb != null) {
            try {
                cm.unregisterNetworkCallback(cb);
            } catch (IllegalArgumentException ignored) {
                // déjà désinscrit (demande expirée)
            }
        }
        cm.bindProcessToNetwork(null);
        bound = null;
    }

    private static boolean isCozmo(LinkProperties lp) {
        if (lp == null) return false;
        for (LinkAddress a : lp.getLinkAddresses()) {
            InetAddress ia = a.getAddress();
            if (!(ia instanceof Inet4Address)) continue;
            byte[] b = ia.getAddress();
            if ((b[0] & 0xff) == 172 && (b[1] & 0xff) == 31 && (b[2] & 0xff) == 1) return true;
        }
        return false;
    }
}
