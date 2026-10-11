# Connecting Cozmo

Cozmo is its own Wi-Fi access point. It does not join your home network: your
phone joins *its* network, named `Cozmo_XXXXXX`, and talks to the robot at
`172.31.1.1`. That network has no internet access, which explains most of what
can go wrong below.

## 1. Wake the robot

Plug the charger into USB power and put Cozmo on it. The backpack lights come on
and pulse green while it charges. The robot's Wi-Fi only starts once it has been
on the charger; a robot left idle for a long time switches itself off, and the
charger is also how you switch it back on.

## 2. Read the network name and password

With Cozmo on its charger, raise its lift by hand, then lower it again. The
screen shows the network name, `Cozmo_XXXXXX`, and the password. Both are fixed
for a given robot: write them down once and you will not need to do this again.

## 3. Join the network

### Android 10 and later

1. Open Cosmo Robot.
2. Type the password in **Cozmo Wi-Fi password** and tap **Connect**.
3. Android shows its own dialog listing the `Cozmo_…` networks nearby. Pick
   yours and confirm.

The connection is reserved for Cosmo Robot. Your other apps keep using mobile
data or your home Wi-Fi as usual, and nothing changes in your saved networks.

### Android 8 and 9

These versions cannot join a network from inside an app.

1. In Cosmo Robot, tap **Open Wi-Fi settings**, or open them yourself.
2. Join `Cozmo_XXXXXX` with the password.
3. Android warns that the network has no internet access. Choose to stay
   connected.
4. Come back to Cosmo Robot and tap **Connect**, leaving the password field
   empty.

The app recognises the robot's network by its address and sends its own traffic
there. Depending on the phone, other apps may lose internet access while the
phone is on Cozmo's Wi-Fi this way; that is Android's routing, not the app's.

This path also works on Android 10 and later, if you prefer to join the network
in the settings.

## 4. Once connected

The status line reads **Connected**, Cozmo's eyes light up, and its firmware
version appears on its screen for a moment. The battery level and the robot's
state (on charger, charging, picked up, edge detected) are shown at the top.

**Take Cozmo off the charger to drive it.** The robot does not move while it is
docked; the joystick will look dead until you lift it off.

**Disconnect** when you are done. As long as an app is connected, Cozmo stays
awake and drains its battery.

## Automatic reconnection

A worn battery makes Cozmo reboot without warning, often as it starts to
drive. The app notices within about five seconds that the robot has stopped
reporting its state, shows **Connection lost, reconnecting…**, and tries again,
spacing the attempts up to 30 seconds apart. Once the robot is back, the head,
lift, lights and volume are set back to what they were.

The app keeps trying until you tap **Disconnect**.

## Troubleshooting

### "Could not join Cozmo's Wi-Fi"

- Check the password, character by character, against the robot's screen.
- Put the robot back on its charger. A robot that has switched itself off has no
  Wi-Fi at all.
- Bring the phone closer to the robot.

### "Cozmo did not answer"

The phone is on the robot's network, but the robot does not reply.

- Make sure no other app is connected to it: the official app, another phone,
  or a computer running pycozmo or
  [Cozmo for Home Assistant](https://github.com/LabbeSimon/Cozmo_HomeAssistant).
  Cozmo accepts one controller at a time.
- If nothing else is connected, the robot may still be holding a ghost session
  (see below).

### The phone switches to mobile data

Android prefers networks that have internet access. A phone that joined Cozmo's
Wi-Fi through the settings may leave it on its own a few seconds later, or keep
it while sending everything through mobile data.

- On Android 10 and later, connect from inside the app instead (step 3 above):
  the network is then requested for the app alone, and Android keeps it up for
  as long as the app needs it.
- On Android 8 and 9, answer **yes** when Android asks whether to stay on a
  network without internet, and turn off options that automatically switch away
  from weak or offline Wi-Fi. Their names vary by manufacturer ("Switch to
  mobile data", "Intelligent Wi-Fi", "Wi-Fi+", and so on).

### Ghost session: the robot refuses every new connection

If the app was closed while still connected — swiped away, killed by the
system, or the phone ran out of battery — the robot can keep the old session
open and ignore new connections. Restarting the robot clears it.

Anki's procedure to restart (power-cycle) a Cozmo:

**If your Cozmo has a backpack button**

1. Take Cozmo off its charger.
2. Press and hold the backpack button for 5 seconds.
3. Put Cozmo back on its charger.

**If your Cozmo has no backpack button**

1. Take Cozmo off its charger.
2. Unplug the USB adapter from the wall socket, leaving the cable in the
   adapter.
3. Put Cozmo on the charger and wait until its light goes off.
4. Take Cozmo off the charger again.
5. Plug the adapter back into the socket.
6. Put Cozmo back on the charger.

Then connect again from the app.

### The connection keeps dropping

Almost always the battery. These packs are a decade old: on the charger the
robot runs indefinitely, off it a tired battery may last only a few minutes and
make the robot reboot whenever it drives. The app reconnects on its own each
time, but it cannot make up for the battery. Replacement guides exist, for
instance on [iFixit](https://www.ifixit.com/Guide/Anki+Cozmo+Battery+Replacement/146656).

When the battery runs low away from the charger, the app turns the camera off,
since it is the biggest consumer, and says so.

### The camera stays black

- **Waiting for the camera…** is normal for a second or two after starting it.
  If the picture does not come, the app restarts the link by itself after about
  12 seconds.
- If the app says the battery is low, the camera was turned off on purpose. Put
  Cozmo on its charger.

### Cozmo does not speak

- Check the **Volume** slider.
- Cosmo Robot uses the phone's text-to-speech engine. If the app says
  text-to-speech is not available, install or enable one in Android's settings
  (usually under *Accessibility* or *Languages and input*, then
  *Text-to-speech output*), and download a voice for your language there.

### Reporting a problem

Open an [issue](https://github.com/LabbeSimon/Cosmo-robot/issues) with your
phone model, Android version and what the status line says. The app's own log
can be read over USB:

```bash
adb logcat -s python.stdout python.stderr
```
