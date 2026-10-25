package com.motofeatures.choptorch;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;

/**
 * Sends the calibration recordings to the PC's tools/upload_server.py (POST /upload?name=...), for phones
 * that are not on adb. Blocking; call it off the main thread.
 */
final class Uploader {
    interface Progress {
        void onProgress(String line);
    }

    /** @return how many files were sent */
    static int sendAll(Context c, String server, Progress progress) {
        File[] files = new File(c.getFilesDir(), "rec").listFiles((d, n) -> n.endsWith(".csv"));
        if (files == null || files.length == 0) {
            progress.onProgress("No recordings on this phone.");
            return 0;
        }
        Arrays.sort(files);
        String base = server.trim().replaceAll("/+$", "");
        if (base.isEmpty()) {
            progress.onProgress("Enter the PC upload server address first.");
            return 0;
        }
        String device = (Build.MANUFACTURER + "-" + Build.MODEL).replaceAll("[^A-Za-z0-9._-]", "_");
        int sent = 0;
        for (File f : files) {
            if (ChopService.recording && f.getAbsolutePath().equals(ChopService.recordPath)) {
                progress.onProgress(f.getName() + ": still recording, skipped");
                continue;
            }
            try {
                post(base + "/upload?name=" + URLEncoder.encode(f.getName(), "UTF-8"), device, f);
                sent++;
                progress.onProgress(f.getName() + ": sent (" + f.length() / 1024 + " KB)");
            } catch (IOException e) {
                progress.onProgress(f.getName() + ": failed, " + e.getMessage());
            }
        }
        return sent;
    }

    private static void post(String url, String device, File f) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(60000);
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "text/csv");
        con.setRequestProperty("X-Device", device);
        con.setFixedLengthStreamingMode(f.length());
        try {
            try (InputStream in = new FileInputStream(f); OutputStream out = con.getOutputStream()) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
        } finally {
            con.disconnect();
        }
    }

    private Uploader() {}
}
