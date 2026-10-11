"""Coeur Python de Cosmo Robot : pilote un Cozmo depuis le telephone via pycozmo.

Portage de l'agent Home Assistant (Cozmo_HomeAssistant/scripts/agent.py) sans
MQTT : l'interface Android appelle directement les fonctions de ce module.
Tous les pieges deja payes sur l'agent sont conserves ici, avec leur raison.

Le module est appele depuis plusieurs fils Java a la fois (commandes, sondage
de l'etat, sondage des images) : tout ce qui touche au client passe par LOCK.

Regle a ne jamais enfreindre : les gestionnaires appeles par pycozmo
(_on_state, _on_image) tournent sur le fil de la connexion, et _drop() joint
ce fil en tenant LOCK. S'ils prenaient LOCK a leur tour, la deconnexion
resterait bloquee pour toujours.
"""
import io
import json
import math
import os
import queue
import struct
import threading
import time
import wave

import numpy as np

import pycozmo

# --- Reglages repris de l'agent --------------------------------------------

LIFT_MIN = pycozmo.MIN_LIFT_HEIGHT.mm
LIFT_MAX = pycozmo.MAX_LIFT_HEIGHT.mm
HEAD_MIN_DEG = -25.0
HEAD_MAX_DEG = 44.0
WHEEL_MAX = pycozmo.MAX_WHEEL_SPEED.mmps
VOLUME_MAX_RAW = 65535

# Bits du champ status de RobotState. Un Cozmo docke refuse de rouler : sans
# cette information, une absence de mouvement ressemble a une panne.
FLAG_PICKED_UP = 8
FLAG_FALLING = 32
FLAG_ON_CHARGER = 4096
FLAG_CHARGING = 8192
FLAG_CLIFF = 16384

LOW_BATTERY_V = 3.88
BATT_EMPTY_V = 3.76
BATT_FULL_V = 4.20

# Homme-mort : le joystick renvoie l'ordre toutes les 100 ms tant qu'on le
# tient. Sans nouvel ordre dans ce delai, les roues s'arretent : une appli
# figee ou un doigt qui glisse hors de l'ecran ne laisse pas le robot filer.
DRIVE_HOLD = 0.35

# Delai total pour qu'un robot joignable reponde et termine son initialisation.
# L'attente se fait par tranches courtes : une deconnexion demandee pendant ce
# temps l'interrompt au lieu de laisser l'utilisateur patienter pour rien.
CONNECT_TIMEOUT = 20.0

CAM_FPS = 15.0
# Session a moitie morte : quand le robot redemarre (frequent, batterie usee),
# pycozmo croit la connexion valide alors que plus rien n'arrive. Le robot emet
# un RobotState ~30 fois par seconde : leur absence est un signe plus sur que
# celle des images, et marche aussi camera eteinte.
ALIVE_TIMEOUT = 5.0
CAM_STALL_SECONDS = 12.0
RETRY_SECONDS = 4.0
RETRY_MAX = 30.0

SCREEN_SECONDS = 10.0
SCREEN_W, SCREEN_H = 128, 32

# pycozmo.audio.u_law_encoding plante sur les echantillons negatifs de forte
# amplitude (seuil exact -31612) : la phrase entiere est perdue, en silence.
# On ramene le pic sous ce seuil par un gain uniforme, pas par ecretage.
SAY_PEAK_MAX = 30000
# La sortie audio du robot met un instant a monter : sans silence d'amorce, la
# phrase demarre en cours de mot.
SAY_LEAD_MS = 400
SAY_RATE = 22050
# La file audio de pycozmo garde 4500 trames de 744 echantillons (~150 s) et
# jette les PLUS ANCIENNES quand elle deborde : une phrase trop longue perdrait
# son debut. On coupe donc la fin au-dela de cette duree.
SAY_MAX_SECONDS = 140.0
# Au-dela de ce retard accumule, une nouvelle phrase est refusee.
SAY_QUEUE_MAX = 60.0

# --- Etat partage -----------------------------------------------------------

LOCK = threading.RLock()
# Serialise les ouvertures de connexion (bouton et chien de garde) sans tenir
# LOCK : l'attente du robot peut durer CONNECT_TIMEOUT, pendant lesquelles les
# autres commandes doivent rester servies.
_OPEN_LOCK = threading.Lock()
_cli = None
_last_error = ""

_speed = 120.0
_volume = int(VOLUME_MAX_RAW * 0.8)
_drive_until = 0.0

# Etat voulu, rejoue apres chaque reconnexion (voir _restore) : le robot
# repart tete baissee, LEDs eteintes et volume par defaut.
_want_head = None
_want_lift = None
_want_lights = None

# Intention de l'utilisateur, distincte de l'etat reel : tant qu'il n'a pas
# demande la deconnexion, une liaison perdue est retablie toute seule.
_want_connected = False
# Incremente a chaque deconnexion demandee : toute ouverture lancee avant
# s'arrete d'elle-meme au lieu de rebrancher un robot qu'on vient de lacher.
_gen = 0
_last_alive = 0.0
_last_try = 0.0
_fails = 0
_reconnect_thread = None

_camera_wanted = False
_camera_on = False
_last_frame_seen = 0.0

_frame_lock = threading.Lock()
_frame = None
_frame_seq = 0
_last_frame_pub = 0.0

_state = {"battery_v": 0.0, "battery_pct": 0, "docked": False, "charging": False,
          "picked_up": False, "cliff": False, "low": False}

# Un seul fil prepare les phrases, dans l'ordre d'arrivee : les fichiers
# intermediaires sont partages, et la file du robot doit les recevoir dans
# l'ordre (des fils concurrents se doubleraient au verrou).
_say_queue = queue.Queue()
_say_lock = threading.Lock()
_say_worker = None
_say_pending = 0
_speak_until = 0.0

_screen_thread = None
_screen_until = 0.0

_init_lock = threading.Lock()
_maint_started = False


class Cancelled(RuntimeError):
    """Ouverture abandonnee parce que l'utilisateur a demande la deconnexion."""

    def __init__(self):
        super().__init__("cancelled")


def log(*args):
    print("[cosmo]", *args, flush=True)


def _num(v, lo, hi):
    """Borne une valeur venue de l'interface. NaN et infinis sont refuses :
    max()/min() laisseraient passer NaN comme une borne (vitesse maximale)."""
    v = float(v)
    if not math.isfinite(v):
        raise ValueError("invalid number: %r" % v)
    return max(lo, min(hi, v))


# --- Connexion --------------------------------------------------------------

def init(files_dir):
    """Appele au demarrage de l'appli ; sans effet les fois suivantes."""
    global _maint_started
    # pycozmo calcule Path.home() avant meme de lire PYCOZMO_DIR : sans HOME,
    # il leverait une erreur.
    os.environ.setdefault("HOME", files_dir)
    # Les ressources d'Anki (animations) iront ici le jour ou on les gere.
    os.environ["PYCOZMO_DIR"] = os.path.join(files_dir, "pycozmo")
    with _init_lock:
        if not _maint_started:
            _maint_started = True
            threading.Thread(target=_maintenance, name="cosmo-maint", daemon=True).start()


def connected():
    return _cli is not None


def connect():
    """Ouvre la connexion a la demande de l'utilisateur. Leve une exception si
    le robot ne repond pas, ou Cancelled si disconnect() arrive entre-temps."""
    global _want_connected, _fails
    _want_connected = True
    _fails = 0
    return _open(_gen)


def _open(gen):
    """Leve une exception si le robot ne repond pas (eteint, hors de portee)."""
    global _cli, _last_error, _last_alive, _last_frame_seen
    with _OPEN_LOCK:
        if _cli is not None:
            return True
        if gen != _gen:
            raise Cancelled()
        cli = pycozmo.Client()
        ready = threading.Event()
        # Pose avant start() : EvtRobotReady part a la fin de l'initialisation
        # (controleur d'animation demarre), une seule fois.
        cli.add_handler(pycozmo.event.EvtRobotReady, lambda *a: ready.set(), one_shot=True)
        try:
            cli.start()
            cli.connect()
            limite = time.time() + CONNECT_TIMEOUT
            while not ready.wait(0.25):
                if gen != _gen:
                    raise Cancelled()
                if time.time() > limite:
                    raise pycozmo.exception.ConnectionTimeout(
                        "Failed to initialize Cozmo." if cli.robot_fw_sig
                        else "Failed to connect to Cozmo.")
            cli.add_handler(pycozmo.protocol_encoder.RobotState, _on_state)
            cli.add_handler(pycozmo.event.EvtNewRawCameraImage, _on_image)
            with LOCK:
                if gen != _gen:
                    raise Cancelled()
                _cli = cli
        except BaseException as exc:
            # start() lance trois fils et ouvre le socket UDP. Sans ce nettoyage
            # le client a moitie construit garde ses fils en vie : sur l'agent,
            # les tentatives repetees ont fini par epuiser fils et descripteurs.
            _abandon(cli)
            if not isinstance(exc, Cancelled):
                _last_error = str(exc) or exc.__class__.__name__
            raise
        _last_error = ""
        _last_alive = _last_frame_seen = time.time()
        with LOCK:
            if _camera_wanted:
                try:
                    cli.enable_camera(enable=True, color=True)
                except Exception as exc:
                    log("camera :", exc)
            _restore(cli)
        log("connecte")
        return True


def _abandon(cli):
    """Libere un client. Ne leve jamais, et va toujours jusqu'a stop() : un
    Disconnect envoye sans stop() laisse le fil d'envoi de pycozmo tourner a
    vide en boucle, et ses fils de reception ouverts."""
    for action in (cli.disconnect, cli.stop):
        try:
            action()
        except Exception as exc:
            log("nettoyage %s : %s" % (action.__name__, exc))


def _restore(cli):
    for label, action in (
        ("volume", lambda: cli.set_volume(_volume)),
        ("tete", lambda: cli.set_head_angle(angle=_want_head)
            if _want_head is not None else None),
        ("bras", lambda: cli.set_lift_height(height=_want_lift)
            if _want_lift is not None else None),
        ("leds", lambda: cli.set_all_backpack_lights(_want_lights)
            if _want_lights is not None else None),
    ):
        try:
            action()
        except Exception as exc:
            log("restauration %s : %s" % (label, exc))


def disconnect():
    """Deconnexion demandee par l'utilisateur (ou l'appli passe en arriere-plan).
    Interrompt aussi une connexion en cours d'ouverture."""
    global _want_connected, _gen, _last_error
    _want_connected = False
    _gen += 1
    _last_error = ""
    _drop()


def _drop(only=None):
    """Toujours se deconnecter proprement : une session laissee ouverte reste
    fantome sur le robot, qui refuse ensuite toute nouvelle connexion.

    only : ne ferme que ce client-la, pour qu'un chien de garde en retard ne
    coupe pas une connexion neuve ouverte entre-temps.
    """
    global _cli, _camera_on, _speak_until
    with LOCK:
        cli = _cli
        if cli is None or (only is not None and cli is not only):
            return
        _cli = None
        _camera_on = False
        # La file audio meurt avec le client.
        _speak_until = 0.0
        try:
            cli.stop_all_motors()
        except Exception as exc:
            log("arret moteurs :", exc)
        _abandon(cli)
        log("deconnecte")


def last_error():
    return _last_error


def _need():
    cli = _cli
    if cli is None:
        raise RuntimeError("not connected")
    return cli


# --- Mouvements -------------------------------------------------------------

def drive(x, y):
    """Joystick : x = rotation, y = avance, tous deux dans -1..1."""
    global _drive_until
    x = _num(x, -1.0, 1.0)
    y = _num(y, -1.0, 1.0)
    # Melange differentiel, normalise pour ne pas depasser la vitesse demandee
    # quand les deux axes sont pousses a fond.
    left, right = y + x, y - x
    peak = max(abs(left), abs(right), 1.0)
    with LOCK:
        cli = _need()
        # Pas de duration= : drive_wheels(duration) fait un time.sleep()
        # bloquant, et les ordres du joystick s'empilaient sur l'agent.
        cli.drive_wheels(lwheel_speed=_speed * left / peak,
                         rwheel_speed=_speed * right / peak)
        _drive_until = time.time() + DRIVE_HOLD


def stop():
    global _drive_until
    _drive_until = 0.0
    with LOCK:
        if _cli is not None:
            _cli.stop_all_motors()


def set_speed(mmps):
    global _speed
    _speed = _num(mmps, 20.0, WHEEL_MAX)


def set_head(deg):
    global _want_head
    deg = _num(deg, HEAD_MIN_DEG, HEAD_MAX_DEG)
    _want_head = pycozmo.util.Angle(degrees=deg).radians
    with LOCK:
        _need().set_head_angle(angle=_want_head)


def set_lift(pct):
    global _want_lift
    pct = _num(pct, 0.0, 100.0)
    _want_lift = LIFT_MIN + (LIFT_MAX - LIFT_MIN) * pct / 100.0
    with LOCK:
        _need().set_lift_height(height=_want_lift)


def _check_drive():
    global _drive_until
    if _drive_until and time.time() > _drive_until:
        with LOCK:
            # Relu sous le verrou : un ordre arrive entre-temps a repousse
            # l'echeance, il ne faut pas l'annuler.
            if not _drive_until or time.time() <= _drive_until:
                return
            _drive_until = 0.0
            if _cli is not None:
                _cli.stop_all_motors()


# --- Camera -----------------------------------------------------------------

def set_camera(on):
    """Enregistre l'intention ; _watch_link() retablit le flux tant qu'il
    n'est pas revenu, y compris apres un redemarrage du robot."""
    global _camera_wanted, _camera_on, _last_frame_seen
    _camera_wanted = bool(on)
    if on:
        _last_frame_seen = time.time()
        with LOCK:
            if _cli is not None:
                _cli.enable_camera(enable=True, color=True)
        return
    _camera_on = False
    with LOCK:
        if _cli is not None:
            try:
                _cli.enable_camera(enable=False)
            except Exception as exc:
                log("arret camera :", exc)


def _on_image(cli, image):
    """Fil de la connexion pycozmo : ne jamais prendre LOCK ici."""
    global _frame, _frame_seq, _last_frame_pub, _last_frame_seen, _camera_on
    now = time.time()
    _last_frame_seen = now
    if not _camera_wanted:
        return      # images encore en vol apres l'arret demande
    _camera_on = True
    if now - _last_frame_pub < 1.0 / CAM_FPS:
        return
    _last_frame_pub = now
    try:
        buf = io.BytesIO()
        image.save(buf, format="JPEG", quality=75)
    except Exception as exc:
        log("image :", exc)
        return
    with _frame_lock:
        _frame = buf.getvalue()
        _frame_seq += 1


def frame(last_seq):
    """Derniere image JPEG si elle est plus recente que last_seq, sinon None."""
    with _frame_lock:
        if _frame is None or _frame_seq == last_seq:
            return None
        return [_frame_seq, _frame]


def _watch_link():
    """Retablit la liaison tant que l'utilisateur la veut : robot redemarre,
    session a moitie morte, ou flux camera tari."""
    global _last_try, _reconnect_thread
    if not _want_connected:
        return
    if _reconnect_thread is not None and _reconnect_thread.is_alive():
        return
    now = time.time()
    cli = _cli
    if cli is not None:
        alive = now - _last_alive < ALIVE_TIMEOUT
        flowing = not _camera_wanted or now - _last_frame_seen < CAM_STALL_SECONDS
        if alive and flowing:
            return
    # Espacement progressif, plafonne : inutile de marteler un robot eteint.
    attente = min(RETRY_SECONDS * (1 + _fails), RETRY_MAX)
    if now - _last_try < attente:
        return
    _last_try = now
    # Dans un fil a part : l'ouverture peut durer CONNECT_TIMEOUT, et
    # l'homme-mort de la boucle de maintenance doit continuer de tourner.
    _reconnect_thread = threading.Thread(target=_reconnect, args=(cli, _gen),
                                         name="cosmo-reconnect", daemon=True)
    _reconnect_thread.start()


def _reconnect(old, gen):
    global _fails
    log("liaison perdue, reconnexion")
    try:
        if old is not None:
            _drop(only=old)
            time.sleep(1.0)
        if gen != _gen or not _want_connected:
            return
        _open(gen)
        _fails = 0
    except Cancelled:
        pass
    except Exception as exc:
        _fails += 1
        log("reconnexion echouee :", exc)


# --- Etat du robot ----------------------------------------------------------

def _on_state(cli, pkt):
    """Fil de la connexion pycozmo, ~30 fois par seconde : ne jamais prendre
    LOCK ici, ni lancer de fil a chaque paquet."""
    global _last_alive, _state, _camera_wanted, _camera_on
    _last_alive = time.time()
    v = pkt.battery_voltage
    pct = 100.0 * (v - BATT_EMPTY_V) / (BATT_FULL_V - BATT_EMPTY_V)
    docked = bool(pkt.status & FLAG_ON_CHARGER)
    low = v < LOW_BATTERY_V and not docked
    # Remplace d'un bloc : state() lit toujours un etat coherent.
    _state = {
        "battery_v": round(v, 2),
        "battery_pct": int(max(0.0, min(100.0, pct))),
        "docked": docked,
        "charging": bool(pkt.status & FLAG_CHARGING),
        "picked_up": bool(pkt.status & FLAG_PICKED_UP),
        "cliff": bool(pkt.status & (FLAG_CLIFF | FLAG_FALLING)),
        "low": low,
    }
    # Batterie faible hors socle : la camera est le plus gros consommateur.
    if low and _camera_wanted:
        log("batterie faible (%.2f V) hors socle : camera coupee" % v)
        _camera_wanted = False
        _camera_on = False
        try:
            cli.enable_camera(enable=False)     # simple mise en file d'envoi
        except Exception as exc:
            log("arret camera :", exc)


def state():
    d = dict(_state)
    d.update(connected=_cli is not None, wanted=_want_connected,
             camera_wanted=_camera_wanted,
             camera_on=_camera_on and _cli is not None,
             speaking=_say_pending > 0 or time.time() < _speak_until,
             error=_last_error,
             # Ajoute : la passerelle Java renvoie ready=false tant que Python
             # demarre.
             ready=True)
    return json.dumps(d)


# --- Voix -------------------------------------------------------------------

def set_volume(pct):
    global _volume
    pct = _num(pct, 0.0, 100.0)
    _volume = int(VOLUME_MAX_RAW * pct / 100.0)
    with LOCK:
        if _cli is not None:
            _cli.set_volume(_volume)


def _lire_wav(path):
    """Lit un WAV quelconque : (echantillons float64 mono a l'echelle 16 bits,
    frequence).

    Le module wave ne lit que le PCM entier : or un moteur de synthese peut
    sortir du 8, 24 ou 32 bits, du flottant, un en-tete WAVE_FORMAT_EXTENSIBLE,
    ou une taille de donnees fausse (0 ou 0xFFFFFFFF quand il ecrit en flux).
    Android ecrit en plus l'en-tete PCM (1) meme pour du flottant 32 bits.
    """
    with open(path, "rb") as f:
        raw = f.read()
    if len(raw) < 12 or raw[:4] != b"RIFF" or raw[8:12] != b"WAVE":
        raise ValueError("not a WAV file")
    fmt = data = None
    pos = 12
    while pos + 8 <= len(raw):
        cid = raw[pos:pos + 4]
        size = struct.unpack("<I", raw[pos + 4:pos + 8])[0]
        if cid == b"data":
            # Taille nulle ou fausse : tout le reste du fichier est du son.
            if size in (0, 0xFFFFFFFF) or pos + 8 + size > len(raw):
                data = raw[pos + 8:]
                break
            data = raw[pos + 8:pos + 8 + size]
        if cid == b"fmt " and size >= 16:
            tag, nch, rate, _, _, bits = struct.unpack("<HHIIHH", raw[pos + 8:pos + 24])
            if tag == 0xFFFE and size >= 26:      # WAVE_FORMAT_EXTENSIBLE
                tag = struct.unpack("<H", raw[pos + 32:pos + 34])[0]
            fmt = (tag, nch, rate, bits)
        pos += 8 + size + (size & 1)
    if fmt is None or data is None:
        raise ValueError("WAV without fmt or data chunk")
    tag, nch, rate, bits = fmt
    if nch < 1 or not 4000 <= rate <= 384000 or bits not in (8, 16, 24, 32, 64):
        raise ValueError("unsupported WAV: %d ch, %d Hz, %d bits" % (nch, rate, bits))
    larg = bits // 8
    data = data[:len(data) - len(data) % (larg * nch)]

    if tag == 1 and bits == 8:
        ech = (np.frombuffer(data, dtype=np.uint8).astype(np.float64) - 128.0) * 256.0
    elif tag == 1 and bits == 16:
        ech = np.frombuffer(data, dtype="<i2").astype(np.float64)
    elif tag == 1 and bits == 24:
        b = np.frombuffer(data, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        ech = ((b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)) << 8 >> 8).astype(np.float64) / 256.0
    elif tag == 1 and bits == 32:
        f = np.frombuffer(data, dtype="<f4")
        # Flottant deguise en PCM : valeurs finies et dans [-2, 2]. Du vrai
        # PCM 32 bits relu en flottant donne des NaN ou des valeurs enormes
        # des qu'un echantillon est negatif.
        if len(f) and np.all(np.isfinite(f)) and float(np.abs(f).max()) <= 2.0:
            ech = f.astype(np.float64) * 32767.0
        else:
            ech = np.frombuffer(data, dtype="<i4").astype(np.float64) / 65536.0
    elif tag == 3 and bits in (32, 64):
        ech = np.frombuffer(data, dtype="<f4" if bits == 32 else "<f8").astype(np.float64)
        ech = np.nan_to_num(ech, nan=0.0, posinf=1.0, neginf=-1.0) * 32767.0
    else:
        raise ValueError("unsupported WAV format %d (%d bits)" % (tag, bits))

    if nch > 1:
        ech = ech.reshape(-1, nch).mean(axis=1)
    return ech, rate


def preparer_wav(src, dst):
    """Rend un WAV de la synthese Android jouable par Cozmo ; renvoie sa duree
    en secondes.

    pycozmo n'accepte que du 16 bits a 22050 ou 48000 Hz ; les moteurs de
    synthese Android sortent souvent du 24000 Hz. On repasse en mono 22050 Hz,
    puis on applique les deux corrections de l'agent : pic borne (gain
    uniforme) et silence d'amorce.
    """
    ech, rate = _lire_wav(src)
    if not len(ech):
        raise ValueError("empty WAV")
    if rate != SAY_RATE:
        n = max(1, int(len(ech) * SAY_RATE / rate))
        ech = np.interp(np.arange(n) * (rate / SAY_RATE), np.arange(len(ech)), ech)
    lead = int(SAY_RATE * SAY_LEAD_MS / 1000.0)
    maxi = int(SAY_RATE * SAY_MAX_SECONDS) - lead
    if len(ech) > maxi:
        log("phrase trop longue (%.0f s), coupee a %.0f s"
            % (len(ech) / SAY_RATE, SAY_MAX_SECONDS))
        ech = ech[:maxi]
    crete = float(np.abs(ech).max())
    if crete > SAY_PEAK_MAX:
        ech = ech * (SAY_PEAK_MAX / crete)
    out = np.concatenate([np.zeros(lead), ech]).clip(-SAY_PEAK_MAX, SAY_PEAK_MAX)
    with wave.open(dst, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAY_RATE)
        w.writeframes(np.round(out).astype("<i2").tobytes())
    return len(out) / float(SAY_RATE)


def say(wav_path):
    """Joue sur le haut-parleur du robot un WAV produit par la synthese Android.

    Les phrases s'enchainent dans l'ordre ; renvoie False si le retard
    accumule depasse SAY_QUEUE_MAX (phrase ignoree).
    """
    global _say_pending, _say_worker
    _need()
    if _speak_until - time.time() > SAY_QUEUE_MAX:
        log("trop de paroles en attente, phrase ignoree")
        return False
    with _say_lock:
        _say_pending += 1
        _say_queue.put(wav_path)
        if _say_worker is None:
            _say_worker = threading.Thread(target=_say_loop, name="cosmo-say", daemon=True)
            _say_worker.start()
    return True


def _say_loop():
    global _say_pending
    while True:
        wav_path = _say_queue.get()
        try:
            _play(wav_path)
        except Exception as exc:
            log("lecture audio :", exc)
        finally:
            with _say_lock:
                _say_pending -= 1


def _play(wav_path):
    global _speak_until
    ready = wav_path + ".cozmo.wav"
    try:
        with LOCK:
            # Volume renvoye avant la preparation : le robot a le temps de
            # l'appliquer, sinon il rognait le debut de la phrase.
            _need().set_volume(_volume)
        duree = preparer_wav(wav_path, ready)
        # Encodage u-law hors verrou : quelques dixiemes de seconde pendant
        # lesquels le joystick doit rester servi.
        pkts = pycozmo.audio.load_wav(ready)
        with LOCK:
            # Client relu : la liaison a pu etre retablie entre-temps.
            # (Client.play_audio = load_wav + cette mise en file.)
            _need().anim_controller.play_audio(pkts)
            _speak_until = max(time.time(), _speak_until) + duree
    finally:
        try:
            os.remove(ready)
        except OSError:
            pass


# --- Ecran ------------------------------------------------------------------

def _wrap(text, width=21, lines=2):
    mots, out, cur = text.split(), [], ""
    for m in mots:
        if cur and len(cur) + 1 + len(m) > width:
            out.append(cur)
            cur = m
        else:
            cur = (cur + " " + m).strip()
    if cur:
        out.append(cur)
    return [l[:width] for l in out[:lines]] or [""]


def _render_text(text):
    """Image 1 bit de l'ecran, texte centre. La police par defaut de Pillow
    est proportionnelle : on mesure chaque ligne au lieu de compter 6 px par
    caractere, et on rogne ce qui deborde."""
    from PIL import Image, ImageDraw
    im = Image.new("1", (SCREEN_W, SCREEN_H), 0)
    d = ImageDraw.Draw(im)
    lignes = _wrap(text)
    y0 = SCREEN_H // 2 - 6 * len(lignes)
    for i, l in enumerate(lignes):
        while l and d.textlength(l) > SCREEN_W:
            l = l[:-1]
        x = max(0, int((SCREEN_W - d.textlength(l)) // 2))
        d.text((x, y0 + 12 * i), l, fill=1)
    return im


def show_text(text):
    """Ecrit du texte sur l'ecran OLED 128x32 ; un texte vide rend le visage.

    Le controleur d'animation redessine le visage a chaque trame : laisse
    actif, il efface le texte des l'image suivante. On le coupe pendant
    l'affichage, et un fil le rallume a l'echeance (display_image(duration=)
    est bloquant).
    """
    global _screen_until, _screen_thread
    text = (text or "").strip()
    im = _render_text(text) if text else None
    with LOCK:
        cli = _need()
        if im is None:
            _screen_until = 0.0
            cli.enable_procedural_face(True)
            return
        _screen_until = time.time() + SCREEN_SECONDS
        cli.enable_procedural_face(False)
        cli.display_image(im, duration=None)
        # Decide sous LOCK, comme la sortie du fil : un texte affiche pile a
        # l'echeance ne peut pas etre efface par un fil sur le depart.
        if _screen_thread is None:
            _screen_thread = threading.Thread(target=_screen_expire,
                                              name="cosmo-screen", daemon=True)
            _screen_thread.start()


def _screen_expire():
    global _screen_thread
    while True:
        time.sleep(0.2)
        with LOCK:
            if time.time() < _screen_until:
                continue
            _screen_thread = None
            if _cli is None:
                return
            try:
                _cli.enable_procedural_face(True)
            except Exception as exc:
                log("fin d'affichage :", exc)
            return


# --- LEDs du dos ------------------------------------------------------------

def set_backpack(r, g, b):
    """Couleur des LEDs du dos ; (0, 0, 0) les eteint."""
    global _want_lights
    r, g, b = (int(_num(c, 0, 255)) for c in (r, g, b))
    if r == g == b == 0:
        _want_lights = pycozmo.lights.off_light
    else:
        # LightState attend la couleur encodee sur 16 bits (to_int16), pas la
        # valeur RGBA 32 bits de int_color.
        enc = pycozmo.lights.Color(rgb=(r, g, b)).to_int16()
        _want_lights = pycozmo.protocol_encoder.LightState(on_color=enc, off_color=enc)
    with LOCK:
        _need().set_all_backpack_lights(_want_lights)


# --- Boucle de maintenance --------------------------------------------------

def _maintenance():
    """Homme-mort a 20 Hz, et surveillance de la liaison une fois par seconde."""
    tick = 0
    while True:
        time.sleep(0.05)
        try:
            _check_drive()
        except Exception as exc:
            log("homme-mort :", exc)
        tick += 1
        if tick % 20 == 0:
            try:
                _watch_link()
            except Exception as exc:
                log("liaison :", exc)
