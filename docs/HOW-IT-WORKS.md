# How Chop Torch detects a double chop

## How Motorola does it

On Motorola phones the "chop chop" flashlight is mostly hardware. The phone's sensor hub exposes a wake-up gesture sensor, `com.motorola.sensor.chopchop`, and recognises the double chop in its firmware:

- Each event from the sensor is one complete double chop, and switches the torch.
- A new chop within about **750 ms** of the last switch is ignored.
- The main processor sleeps until the event arrives, so the gesture costs almost no battery.

No other phone has this sensor, so Motorola's software cannot do the gesture anywhere else. Chop Torch detects it again from the plain accelerometer.

The rules above came from recording Motorola's sensor events next to the accelerometer and watching how the gesture behaves. The key finding was that one event is already a whole double chop, not a single chop.

## The gesture in the data

In a double chop the phone is shaken sideways, along the device X axis (the short side). X swings past about ±3 g one way and then the other within roughly 0.3–0.6 s.

On a cheap ±4 g, 100 Hz accelerometer every chop clips. With Motorola recordings clipped to ±4 g and resampled to 100 Hz, a Motorola gesture and a double chop on a Samsung Galaxy M10 look the same: X-limit episodes of about 60 ms, in a cluster of about 0.35 s.

## The detector (`ChopDetector.java`)

It counts swings:

1. **Episode:** consecutive samples with raw X beyond +30 m/s², or beyond −30 m/s², lasting at least 20 ms. On a sensor with a smaller range the limit is 80% of that range.
2. **Swing:** an episode of the opposite sign to the previous one.
3. **Cluster:** episodes separated by pauses of at most 0.35 s. A cluster toggles the torch at most once.
4. **Double chop:** at least 2 swings within 1.2 s, and at least 750 ms since the last toggle, as on Motorola phones.

No gravity filter is needed. Walking, running and stairs move the phone along Y and Z, and don't swing X past the limit both ways.

`ChopDetector` has no Android imports, so recordings can be replayed on a desktop JVM with `tools/chop_replay`.

## Results

Replay scores on recordings:

| Recording | Result |
|---|---|
| motorola edge 20 fusion (±8 g, 400 Hz), against Motorola's own chop events | 41 of 42, 1 extra. The same when clipped to ±4 g and resampled to 100 Hz or 50 Hz. |
| Samsung Galaxy M10 (±4 g, 100 Hz), counted double chops | 63 of 64 |
| Walking, running, stairs, idle (Motorola and M10) | 0 toggles |
| Redmi A2 | 2 toggles, both real sideways shakes when the phone was picked up |

Live test on the Samsung M10: no misses in hand, on the palm, walking, running and on stairs. One false toggle came from a very fast simulated run.

## How it runs

- **Foreground service** (`ChopService`) reading the accelerometer at 100 Hz.
- **Partial wake lock**, so detection keeps working with the screen off. Most phones have no wake-up accelerometer, so this costs some battery.
- **Torch** through `CameraManager.setTorchMode`, which needs no camera permission. A torch callback tracks changes made elsewhere (quick settings, Motorola's own gesture), so a toggle always does the right thing.
- **Feedback:** a 70 ms vibration and a short click, played only after the torch has really switched. The click can be turned off and is silent in silent and vibrate modes.

## Recording format (dev edition)

The dev edition writes CSV files to the app's private storage:

| Line | Meaning |
|---|---|
| `# ...` | Header: model, SDK level, sensor name, range and resolution |
| `M,<elapsedRealtimeNanos>,<currentTimeMillis>` | Clock reference |
| `A,<t_ns>,<x>,<y>,<z>` | Accelerometer, m/s² |
| `G,<t_ns>,<x>,<y>,<z>` | Gyroscope, rad/s |
| `C,<t_ns>,<value>` | Motorola chop sensor event (Motorola phones only) |
| `D,<t_ns>` | Chop Torch detection |
| `S,<t_ns>,1\|0` | Screen on or off |
