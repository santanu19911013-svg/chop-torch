package com.motofeatures.choptorch;

/**
 * Detects Motorola's "chop chop" gesture (the phone shaken sideways twice, palm-style) from raw accelerometer
 * samples. Plain Java with no Android imports, so recorded sessions can be replayed on a desktop JVM
 * (tools/chop_replay) and scored against Motorola's own chop sensor.
 *
 * <p>On Motorola phones the gesture comes from a sensor-hub gesture sensor, {@code com.motorola.sensor.chopchop}.
 * Each of its events is a complete double chop and toggles the torch, unless the last toggle was under
 * 750 ms ago. The motion analysis happens in the sensor hub's firmware, so other phones need this detector.
 *
 * <p>Here: a double chop drives the device X axis (the phone's short side) to {@link #xLimit} one way and
 * then the other, at least {@link #minSwings} times in quick succession. Shaking along X is the gesture;
 * walking, running and stairs move the phone along Y/Z and never swing X past the limit both ways.
 * <ul>
 * <li><b>episode</b>: consecutive samples beyond +limit or beyond -limit, lasting {@link #minEpisodeNs}+;</li>
 * <li><b>swing</b>: an episode of the opposite sign to the previous one in the same cluster;</li>
 * <li><b>cluster</b>: episodes with pauses of at most {@link #clusterGapNs}; one cluster toggles at most once.</li>
 * </ul>
 * Scored on recordings from a Motorola Edge 20 Fusion (±8 g, 400 Hz, with Motorola's events as reference),
 * a Samsung Galaxy M10 (±4 g, 100 Hz) and a Redmi A2: see docs/HOW-IT-WORKS.md and tools/chop_replay.
 */
final class ChopDetector {
    interface Listener {
        /** One swing (direction change of X at the limit); for the calibration screen. */
        default void onSwing(long timeNs) {
        }

        void onDoubleChop(long timeNs);
    }

    // Tunables, in m/s² and nanoseconds
    /** X limit: 30 m/s² (about 3 g), or 80% of a smaller sensor's range. */
    float xLimit = 30f;
    /** Shorter excursions past the limit are ignored (single noisy samples). */
    long minEpisodeNs = 20_000_000L;
    /** Direction changes needed within {@link #swingWindowNs} for a double chop. */
    int minSwings = 2;
    long swingWindowNs = 1_200_000_000L;
    /** A pause longer than this ends the cluster (one shake gesture). */
    long clusterGapNs = 350_000_000L;
    /** Motorola's rule: no toggle within 750 ms of the previous one. */
    long minToggleNs = 750_000_000L;

    private static final int MAX_SWINGS = 8;

    private final Listener listener;
    private long lastT = Long.MIN_VALUE;
    // The episode in progress
    private int epSign;
    private long epStart;
    // Cluster state
    private int lastSign;
    private long lastEnd = Long.MIN_VALUE / 2;
    private boolean fired;
    private final long[] swings = new long[MAX_SWINGS];
    private int swingCount;
    private long lastFire = Long.MIN_VALUE / 2;
    private int totalSwings;

    ChopDetector(Listener listener) {
        this.listener = listener;
    }

    void reset() {
        lastT = Long.MIN_VALUE;
        epSign = 0;
        lastSign = 0;
        swingCount = 0;
        fired = false;
        lastEnd = Long.MIN_VALUE / 2;
    }

    /** Fits {@link #xLimit} to a sensor whose range ({@code Sensor.getMaximumRange()}) is small, e.g. ±4 g. */
    void setSensorRange(float maxRange) {
        if (maxRange > 0) xLimit = Math.min(xLimit, 0.8f * maxRange);
    }

    /** Swings seen so far. */
    int chops() {
        return totalSwings;
    }

    void feed(long t, float x, float y, float z) {
        if (lastT != Long.MIN_VALUE && (t - lastT > 500_000_000L || t <= lastT)) epSign = 0; // sensor paused
        lastT = t;
        int sign = x >= xLimit ? 1 : x <= -xLimit ? -1 : 0;
        if (sign == epSign) return;
        if (epSign != 0) onEpisode(epSign, epStart, t);
        epSign = sign;
        epStart = t;
    }

    private void onEpisode(int sign, long start, long end) {
        if (end - start < minEpisodeNs) return;
        if (start - lastEnd > clusterGapNs) { // a new shake
            lastSign = 0;
            swingCount = 0;
            fired = false;
        }
        if (fired || start - lastFire < minToggleNs) {
            lastSign = sign;
            lastEnd = end;
            return;
        }
        if (lastSign != 0 && sign != lastSign) {
            totalSwings++;
            listener.onSwing(start);
            if (swingCount == MAX_SWINGS) {
                System.arraycopy(swings, 1, swings, 0, MAX_SWINGS - 1);
                swingCount--;
            }
            swings[swingCount++] = start;
            int recent = 0;
            for (int i = 0; i < swingCount; i++) if (start - swings[i] <= swingWindowNs) recent++;
            if (recent >= minSwings) {
                fired = true;
                lastFire = start;
                swingCount = 0;
                listener.onDoubleChop(start);
            }
        }
        lastSign = sign;
        lastEnd = end;
    }
}
