"""Coeur Python de Cosmo Robot : pilote un Cozmo depuis le telephone via pycozmo.

Portage de l'agent Home Assistant (Cozmo_HomeAssistant/scripts/agent.py) sans
MQTT : l'interface Android appelle directement les fonctions de ce module.
Tous les pieges deja payes sur l'agent sont conserves ici, avec leur raison.

Le module est appele depuis plusieurs fils Java a la fois (commandes, sondage
de l'etat, sondage des images) : tout ce qui touche au client passe par LOCK.
"""
import array
import io
import json
import math
import os
import sys
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

# pycozmo.audio.u_law_encoding plante sur les echantillons negatifs de forte
# amplitude (seuil exact -31612) : la phrase entiere est perdue, en silence.
# On ramene le pic sous ce seuil par un gain uniforme, pas par ecretage.
SAY_PEAK_MAX = 30000
# La sortie audio du robot met un instant a monter : sans silence d'amorce, la
# phrase demarre en cours de mot.
SAY_LEAD_MS = 400
SAY_RATE = 22050

# --- Etat partage -----------------------------------------------------------

LOCK = threading.RLock()
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
_last_alive = 0.0
_last_try = 0.0
_fails = 0

_camera_wanted = False
_camera_on = False
_last_frame_seen = 0.0

_frame_lock = threading.Lock()
_frame = None
_frame_seq = 0
_last_frame_pub = 0.0

_state = {"battery_v": 0.0, "battery_pct": 0, "docked": False, "charging": False,
          "picked_up": False, "cliff": False, "low": False}

_say_thread = None
_screen_thread = None
_screen_until = 0.0

_maint_started = False


def log(*args):
    print("[cosmo]", *args, flush=True)


# --- Connexion --------------------------------------------------------------

def init(files_dir):
    """Appele une fois au demarrage de l'appli."""
    global _maint_started
    # Les ressources d'Anki (animations) iront ici le jour ou on les gere.
    os.environ["PYCOZMO_DIR"] = os.path.join(files_dir, "pycozmo")
    if not _maint_started:
        _maint_started = True
        threading.Thread(target=_maintenance, daemon=True).start()


def connected():
    return _cli is not None


def connect():
    """Ouvre la connexion a la demande de l'utilisateur."""
    global _want_connected, _fails
    _want_connected = True
    _fails = 0
    return _open()


def _open():
    """Leve une exception si le robot ne repond pas (eteint, hors de portee)."""
    global _cli, _last_error, _last_alive, _last_frame_seen
    with LOCK:
        if _cli is not None:
            return True
        cli = pycozmo.Client()
        try:
            cli.start()
            cli.connect()
            cli.wait_for_robot(timeout=15.0)
        except BaseException as exc:
            # start() lance trois fils et ouvre le socket UDP. Sans ce nettoyage
            # le client a moitie construit garde ses fils en vie : sur l'agent,
            # les tentatives repetees ont fini par epuiser fils et descripteurs.
            _abandon(cli)
            _last_error = str(exc) or exc.__class__.__name__
            raise
        cli.add_handler(pycozmo.protocol_encoder.RobotState, _on_state)
        cli.add_handler(pycozmo.event.EvtNewRawCameraImage, _on_image)
        _cli = cli
        _last_error = ""
        _last_alive = _last_frame_seen = time.time()
        if _camera_wanted:
            cli.enable_camera(enable=True, color=True)
        _restore(cli)
        log("connecte")
        return True


def _abandon(cli):
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
    """Deconnexion demandee par l'utilisateur (ou l'appli passe en arriere-plan)."""
    global _want_connected
    _want_connected = False
    _drop()


def _drop():
    """Toujours se deconnecter proprement : une session laissee ouverte reste
    fantome sur le robot, qui refuse ensuite toute nouvelle connexion."""
    global _cli, _camera_on
    with LOCK:
        if _cli is None:
            return
        try:
            _cli.stop_all_motors()
            _cli.disconnect()
            _cli.stop()
        except Exception as exc:
            log("deconnexion :", exc)
        finally:
            _cli = None
            _camera_on = False
            log("deconnecte")


def last_error():
    return _last_error


def _need():
    if _cli is None:
        raise RuntimeError("not connected")
    return _cli


# --- Mouvements -------------------------------------------------------------

def drive(x, y):
    """Joystick : x = rotation, y = avance, tous deux dans -1..1."""
    global _drive_until
    x = max(-1.0, min(1.0, float(x)))
    y = max(-1.0, min(1.0, float(y)))
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
    _speed = max(20.0, min(WHEEL_MAX, float(mmps)))


def set_head(deg):
    global _want_head
    deg = max(HEAD_MIN_DEG, min(HEAD_MAX_DEG, float(deg)))
    _want_head = pycozmo.util.Angle(degrees=deg).radians
    with LOCK:
        _need().set_head_angle(angle=_want_head)


def set_lift(pct):
    global _want_lift
    pct = max(0.0, min(100.0, float(pct)))
    _want_lift = LIFT_MIN + (LIFT_MAX - LIFT_MIN) * pct / 100.0
    with LOCK:
        _need().set_lift_height(height=_want_lift)


def _check_drive():
    global _drive_until
    if _drive_until and time.time() > _drive_until:
        _drive_until = 0.0
        with LOCK:
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
    global _frame, _frame_seq, _last_frame_pub, _last_frame_seen, _camera_on
    now = time.time()
    _last_frame_seen = now
    _camera_on = True
    if now - _last_frame_pub < 1.0 / CAM_FPS:
        return
    _last_frame_pub = now
    buf = io.BytesIO()
    image.save(buf, format="JPEG", quality=75)
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
    global _last_try, _fails
    if not _want_connected:
        return
    now = time.time()
    if _cli is not None:
        alive = now - _last_alive < ALIVE_TIMEOUT
        flowing = not _camera_wanted or now - _last_frame_seen < CAM_STALL_SECONDS
        if alive and flowing:
            return
    # Espacement progressif, plafonne : inutile de marteler un robot eteint.
    attente = min(RETRY_SECONDS * (1 + _fails), RETRY_MAX)
    if now - _last_try < attente:
        return
    _last_try = now
    log("liaison perdue, reconnexion")
    try:
        _drop()
        time.sleep(1.0)
        _open()
        _fails = 0
    except Exception as exc:
        _fails += 1
        log("reconnexion echouee :", exc)


# --- Etat du robot ----------------------------------------------------------

def _on_state(cli, pkt):
    global _last_alive
    _last_alive = time.time()
    v = pkt.battery_voltage
    pct = 100.0 * (v - BATT_EMPTY_V) / (BATT_FULL_V - BATT_EMPTY_V)
    docked = bool(pkt.status & FLAG_ON_CHARGER)
    low = v < LOW_BATTERY_V and not docked
    _state.update(
        battery_v=round(v, 2),
        battery_pct=int(max(0.0, min(100.0, pct))),
        docked=docked,
        charging=bool(pkt.status & FLAG_CHARGING),
        picked_up=bool(pkt.status & FLAG_PICKED_UP),
        cliff=bool(pkt.status & (FLAG_CLIFF | FLAG_FALLING)),
        low=low,
    )
    # Batterie faible hors socle : la camera est le plus gros consommateur.
    if low and _camera_wanted:
        log("batterie faible (%.2f V) hors socle : camera coupee" % v)
        threading.Thread(target=set_camera, args=(False,), daemon=True).start()


def state():
    d = dict(_state)
    d.update(connected=_cli is not None, wanted=_want_connected,
             camera_wanted=_camera_wanted,
             camera_on=_camera_on and _cli is not None,
             speaking=_say_thread is not None and _say_thread.is_alive(),
             error=_last_error)
    return json.dumps(d)


# --- Voix -------------------------------------------------------------------

def set_volume(pct):
    global _volume
    pct = max(0.0, min(100.0, float(pct)))
    _volume = int(VOLUME_MAX_RAW * pct / 100.0)
    with LOCK:
        if _cli is not None:
            _cli.set_volume(_volume)


def preparer_wav(src, dst):
    """Rend un WAV de la synthese Android jouable par Cozmo.

    pycozmo n'accepte que du 16 bits a 22050 ou 48000 Hz ; les moteurs de
    synthese Android sortent souvent du 24000 Hz. On repasse en mono 22050 Hz,
    puis on applique les deux corrections de l'agent : pic borne (gain
    uniforme) et silence d'amorce.
    """
    with wave.open(src, "rb") as w:
        rate, nch, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        data = w.readframes(w.getnframes())
    if width != 2:
        raise ValueError("unsupported WAV sample width %d" % width)
    ech = np.frombuffer(data, dtype="<i2").astype(np.float64)
    if nch > 1:
        ech = ech[: len(ech) - len(ech) % nch].reshape(-1, nch).mean(axis=1)
    if rate != SAY_RATE and len(ech):
        n = int(len(ech) * SAY_RATE / rate)
        ech = np.interp(np.arange(n) * (rate / SAY_RATE), np.arange(len(ech)), ech)
    crete = float(np.abs(ech).max()) if len(ech) else 0.0
    if crete > SAY_PEAK_MAX:
        ech *= SAY_PEAK_MAX / crete
    lead = np.zeros(int(SAY_RATE * SAY_LEAD_MS / 1000.0))
    out = np.concatenate([lead, ech]).clip(-SAY_PEAK_MAX, SAY_PEAK_MAX)
    with wave.open(dst, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAY_RATE)
        w.writeframes(out.astype("<i2").tobytes())


def say(wav_path):
    """Joue sur le haut-parleur du robot un WAV produit par la synthese Android."""
    global _say_thread
    if _say_thread is not None and _say_thread.is_alive():
        return False
    cli = _need()
    ready = wav_path + ".cozmo.wav"

    def run():
        try:
            # Volume regle avant la preparation : le robot a le temps de
            # l'appliquer, sinon il rognait encore le debut de la phrase.
            cli.set_volume(_volume)
            preparer_wav(wav_path, ready)
            cli.play_audio(ready)
        except Exception as exc:
            log("lecture audio :", exc)

    _say_thread = threading.Thread(target=run, daemon=True)
    _say_thread.start()
    return True


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


def show_text(text):
    """Ecrit du texte sur l'ecran OLED 128x32.

    Le controleur d'animation redessine le visage a chaque trame : laisse
    actif, il efface le texte des l'image suivante. On le coupe pendant
    l'affichage, et un fil le rallume a l'echeance (display_image(duration=)
    est bloquant).
    """
    global _screen_until, _screen_thread
    from PIL import Image, ImageDraw
    text = text.strip()
    im = Image.new("1", (128, 32), 0)
    d = ImageDraw.Draw(im)
    lignes = _wrap(text)
    y0 = 16 - 6 * len(lignes)
    for i, l in enumerate(lignes):
        d.text((max(0, (128 - 6 * len(l)) // 2), y0 + 12 * i), l, fill=1)
    _screen_until = time.time() + SCREEN_SECONDS
    with LOCK:
        cli = _need()
        cli.enable_procedural_face(False)
        cli.display_image(im, duration=None)
    if _screen_thread is None or not _screen_thread.is_alive():
        _screen_thread = threading.Thread(target=_screen_expire, daemon=True)
        _screen_thread.start()


def _screen_expire():
    while time.time() < _screen_until:
        time.sleep(0.2)
    with LOCK:
        if _cli is None:
            return
        try:
            _cli.enable_procedural_face(True)
        except Exception as exc:
            log("fin d'affichage :", exc)


# --- LEDs du dos ------------------------------------------------------------

def set_backpack(r, g, b):
    """Couleur des LEDs du dos ; (0, 0, 0) les eteint."""
    global _want_lights
    if r == g == b == 0:
        _want_lights = pycozmo.lights.off_light
    else:
        # LightState attend la couleur encodee sur 16 bits (to_int16), pas la
        # valeur RGBA 32 bits de int_color.
        enc = pycozmo.lights.Color(rgb=(int(r), int(g), int(b))).to_int16()
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
