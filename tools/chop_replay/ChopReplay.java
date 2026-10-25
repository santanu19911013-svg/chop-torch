package com.motofeatures.choptorch;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays Chop Torch recordings through {@link ChopDetector} and scores it against Motorola's chop sensor.
 *
 * <pre>
 * javac -d out app/src/main/java/com/motofeatures/choptorch/ChopDetector.java tools/chop_replay/ChopReplay.java
 * java -cp out com.motofeatures.choptorch.ChopReplay [-v] [--rate HZ] [--clip MS2] [--set field=value] [--from S --to S] rec.csv ...
 * </pre>
 *
 * Ground truth: each Motorola "C" line is one complete double chop, which a Motorola phone turns into one
 * torch toggle. --rate decimates (the service runs at 100 Hz), --clip simulates a smaller-range
 * accelerometer (±4 g = 39.2), --set overrides a detector tunable, --from/--to count only that time span
 * (seconds from the start), -v lists every toggle.
 */
public final class ChopReplay {
    /** Our toggle fires mid-gesture; Motorola reports at the end of it. */
    private static final long MATCH_BEFORE_NS = 1_000_000_000L;
    private static final long MATCH_AFTER_NS = 300_000_000L;

    public static void main(String[] args) throws IOException {
        double rate = 0, clip = 0, from = 0, to = Double.MAX_VALUE;
        boolean verbose = false;
        List<String> sets = new ArrayList<>(), files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--rate": rate = Double.parseDouble(args[++i]); break;
                case "--clip": clip = Double.parseDouble(args[++i]); break;
                case "--set": sets.add(args[++i]); break;
                case "--from": from = Double.parseDouble(args[++i]); break;
                case "--to": to = Double.parseDouble(args[++i]); break;
                case "-v": verbose = true; break;
                default: files.add(args[i]);
            }
        }
        int tpAll = 0, motoAll = 0, extraAll = 0;
        for (String f : files) {
            List<long[]> t = new ArrayList<>();
            List<float[]> v = new ArrayList<>();
            List<Long> moto = new ArrayList<>();
            float range = 0;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("A,")) {
                        String[] p = line.split(",");
                        if (p.length != 5) continue; // a truncated last line
                        t.add(new long[]{Long.parseLong(p[1])});
                        v.add(new float[]{Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4])});
                    } else if (line.startsWith("C,")) {
                        moto.add(Long.parseLong(line.split(",")[1]));
                    } else if (line.startsWith("# model=")) {
                        Matcher m = Pattern.compile("maxRange=([0-9.]+)").matcher(line);
                        if (m.find()) range = Float.parseFloat(m.group(1));
                    }
                }
            }
            if (t.isEmpty()) continue;
            long t0 = t.get(0)[0];
            List<Long> ours = new ArrayList<>();
            ChopDetector d = new ChopDetector(ours::add);
            // As ChopService does: the X limit fits the sensor's range
            d.setSensorRange(clip > 0 ? (float) clip : range);
            for (String kv : sets) set(d, kv);
            long step = rate > 0 ? (long) (1e9 / rate) : 0, next = Long.MIN_VALUE;
            for (int i = 0; i < t.size(); i++) {
                long ts = t.get(i)[0];
                if (step > 0 && ts < next) continue;
                next = (next == Long.MIN_VALUE ? ts : next) + step;
                float[] a = v.get(i);
                float x = a[0], y = a[1], z = a[2];
                if (clip > 0) {
                    x = (float) Math.max(-clip, Math.min(clip, x));
                    y = (float) Math.max(-clip, Math.min(clip, y));
                    z = (float) Math.max(-clip, Math.min(clip, z));
                }
                d.feed(ts, x, y, z);
            }
            final double lo = from, hi = to;
            ours.removeIf(o -> (o - t0) / 1e9 < lo || (o - t0) / 1e9 > hi);
            moto.removeIf(m -> (m - t0) / 1e9 < lo || (m - t0) / 1e9 > hi);

            boolean[] used = new boolean[ours.size()];
            int tp = 0;
            StringBuilder missed = new StringBuilder();
            for (long m : moto) {
                boolean hit = false;
                for (int j = 0; j < ours.size() && !hit; j++) {
                    long dlt = ours.get(j) - m;
                    if (!used[j] && dlt >= -MATCH_BEFORE_NS && dlt <= MATCH_AFTER_NS) used[j] = hit = true;
                }
                if (hit) tp++;
                else missed.append(String.format(" %.1f", (m - t0) / 1e9));
            }
            StringBuilder extra = new StringBuilder();
            int ex = 0;
            for (int j = 0; j < ours.size(); j++) {
                if (!used[j]) {
                    ex++;
                    extra.append(String.format(" %.1f", (ours.get(j) - t0) / 1e9));
                }
            }
            System.out.printf("%s%n  toggles: ours %d, Motorola %d | matched %d%s%s%n", f, ours.size(), moto.size(), tp,
                    missed.length() > 0 ? " | missed at" + missed : "",
                    ex > 0 ? (moto.isEmpty() ? " | at" : " | extra at") + extra : "");
            if (verbose) {
                StringBuilder sb = new StringBuilder("  ours at");
                for (long o : ours) sb.append(String.format(" %.2f", (o - t0) / 1e9));
                System.out.println(sb);
            }
            tpAll += tp;
            motoAll += moto.size();
            if (!moto.isEmpty()) extraAll += ex;
        }
        System.out.printf("TOTAL rate=%s clip=%s: Motorola toggles matched %d/%d, extra %d (in files with Motorola data)%n",
                rate > 0 ? rate : "full", clip > 0 ? clip : "none", tpAll, motoAll, extraAll);
    }

    /** --set field=value: overrides a ChopDetector tunable (float, int or long field), for parameter sweeps. */
    private static void set(ChopDetector d, String kv) {
        String[] p = kv.split("=", 2);
        try {
            java.lang.reflect.Field f = ChopDetector.class.getDeclaredField(p[0]);
            f.setAccessible(true);
            if (f.getType() == float.class) f.setFloat(d, Float.parseFloat(p[1]));
            else if (f.getType() == int.class) f.setInt(d, Integer.parseInt(p[1]));
            else f.setLong(d, Long.parseLong(p[1]));
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("--set " + kv + ": " + e);
        }
    }

    private ChopReplay() {}
}
