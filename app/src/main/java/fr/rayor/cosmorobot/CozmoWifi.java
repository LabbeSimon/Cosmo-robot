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
 */
final class CozmoWifi {
    interface Listener {
        void onJoined();
        void onFailed();
        void onLost();
    }

    private final ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private volatile Network bound;

    CozmoWifi(Context ctx) {
        cm = ctx.getSystemService(ConnectivityManager.class);
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
        WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher("Cozmo_", PatternMatcher.PATTERN_PREFIX))
                .setWpa2Passphrase(password)
                .build();
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                if (cm.bindProcessToNetwork(n)) {
                    bound = n;
                    l.onJoined();
                } else {
                    l.onFailed();
                }
            }

            @Override public void onUnavailable() {
                l.onFailed();
            }

            @Override public void onLost(Network n) {
                if (n.equals(bound)) {
                    cm.bindProcessToNetwork(null);
                    bound = null;
                    l.onLost();
                }
            }
        };
        cm.requestNetwork(req, callback, 60_000);
    }

    /** Pour le chemin « réglages », qui n'a pas de rappel : vérifié au sondage. */
    boolean stillThere() {
        Network n = bound;
        if (n == null) return false;
        if (cm.getNetworkCapabilities(n) != null) return true;
        cm.bindProcessToNetwork(null);
        bound = null;
        return false;
    }

    void release() {
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException ignored) {
                // déjà désinscrit
            }
            callback = null;
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
