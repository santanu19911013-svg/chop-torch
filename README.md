# Chop Torch

Motorola's "chop chop" flashlight for any Android phone. Shake the phone sideways twice, like chopping, and the flashlight switches on or off. It works with the screen off too.

## Why this gesture deserves to be everywhere

Motorola owners know the chop: shake the phone twice and the light is on. It is one of the few phone gestures that become a habit, because it fits the moments when a flashlight is needed:

- **No looking, no unlocking.** In a dark room, a parking lot or a power cut, you pick up the phone and chop twice. No screen to wake, no lock screen, no quick-settings tile to find.
- **One hand, even with gloves.** A wrist movement works when your other hand is full, your hands are wet, or a touchscreen won't respond.
- **Just as fast to switch off.** Chop twice again. You feel the result as a short buzz, so you never wonder whether it worked.
- **Hard to trigger by accident.** In testing, walking, running and climbing stairs didn't set it off.

Yet outside Motorola phones there is nothing like it built in. A flashlight is something everyone needs, and a gesture this simple should be a standard Android feature on every phone, not a perk of one brand. Until it is, Chop Torch brings it to any Android phone with a flashlight.

## Motorola uses a sensor chip; Chop Torch doesn't need one

On Motorola phones the double chop is recognised by a dedicated gesture sensor in the phone's sensor hub (`com.motorola.sensor.chopchop`). The hub's firmware watches the motion and wakes the phone with a single event when it sees a double chop, and Motorola's app then switches the torch. That is why it costs Motorola almost no battery, and also why copying Motorola's app to another phone can never work: no other phone has that sensor.

Chop Torch recognises the same gesture in software, from the ordinary accelerometer that every phone has. The detector was designed and tuned by comparing the accelerometer, sample by sample, with the moments Motorola's chip reported a chop, and by looking closely at how Motorola's own gesture responds. It was then tested on a Samsung and a Redmi that have no such chip. See [How it works](docs/HOW-IT-WORKS.md).

Built with the help of **Claude Opus 5.5** (Anthropic): it analysed the recordings, designed and tuned the detector, and wrote the app with me.

## Features

- Android 8.0 (API 26) or newer, with a flashlight.
- No internet access, no ads, no tracking. No camera permission is needed for the torch.
- Each switch gives a short vibration and a click. The click can be turned off.

## Install

1. Download `ChopTorch-1.3.apk` from the [latest release](https://github.com/santanu19911013-svg/chop-torch/releases/latest), or build it yourself (below).
2. Open it on the phone and allow installs from that app if asked.
3. Open Chop Torch and turn on **Chop chop flashlight**.
4. Tap the background-running button and allow it. Without this, Android may stop detection when the screen is off.

Some phone makers add their own battery limits that apps can't check:

- **Samsung:** Settings → Battery and device care → Battery → Background usage limits → Never sleeping apps → add Chop Torch.
- **Xiaomi / Redmi / POCO (MIUI):** Settings → Apps → Chop Torch → Autostart on, and Battery saver → No restrictions.

Detection keeps the CPU awake while it is on, so it costs some battery. Turn it off when you don't need it.

## Build

Needs JDK 17 or newer and the Android SDK (platform 34).

```sh
./gradlew assembleProdRelease   # everyday app: app/build/outputs/apk/prod/release/
./gradlew assembleDevDebug      # calibration edition: app/build/outputs/apk/dev/debug/
```

Set `sdk.dir` in `local.properties`, or `ANDROID_HOME`, if Gradle can't find the SDK. The release build is signed with your debug key so it installs over debug builds. Use your own release key if you publish it.

There are two editions with the same package name, so installing one replaces the other:

| Edition | What it has |
|---|---|
| `prod` | The flashlight gesture only. No internet permission. |
| `dev` | Adds a calibration recorder (accelerometer, gyroscope and, on Motorola phones, Motorola's chop sensor), upload of recordings to a PC, and adb record commands. |

## Tuning the detector

1. Install the dev edition and tap **Start recording**, or use adb:
   `adb shell am start -n com.motofeatures.choptorch/.MainActivity -a com.motofeatures.choptorch.RECORD_START`
2. Do some double chops, then walk, run and so on, and stop the recording.
3. Get the CSV files onto your PC. On the PC run `python tools/upload_server.py <serve_dir> <upload_dir>`, put `http://<PC address>:8765` in the app and tap **Send recordings to PC**. The server is reachable by anything on your network while it runs.
4. Replay them through the detector:

```sh
javac -d out app/src/main/java/com/motofeatures/choptorch/ChopDetector.java tools/chop_replay/ChopReplay.java
java -cp out com.motofeatures.choptorch.ChopReplay -v recording.csv
```

`--set field=value` overrides a detector setting, `--clip 39.2` simulates a ±4 g sensor and `--rate 100` a 100 Hz one. The file format is in [HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md#recording-format-dev-edition).

## Licence

Chop Torch is free software under the [GNU General Public License v3.0](LICENSE). The icon and the click sound were made for this project and are under the same licence.

Not affiliated with or endorsed by Motorola or Lenovo. "Motorola" and "Moto" are trademarks of their owners and are used here only to describe the gesture. This project contains no Motorola code or files.
