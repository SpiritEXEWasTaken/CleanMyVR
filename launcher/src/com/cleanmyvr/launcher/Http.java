package com.cleanmyvr.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tiny HTTP helpers (background threads only). */
final class Http {
    interface Progress { void update(long done, long total); }

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "CleanMyVR-Launcher");
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    static String getText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code == 403 || code == 429) throw new IOException("Rate limited by the server, try again later");
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = c.getInputStream()) { return new String(read(in), "UTF-8"); }
        } finally {
            c.disconnect();
        }
    }

    static JSONObject getObject(String url) throws Exception { return new JSONObject(getText(url)); }

    static JSONArray getArray(String url) throws Exception { return new JSONArray(getText(url)); }

    /** Download to a file, following redirects (GitHub release assets redirect to a CDN). */
    static void download(String url, File dest, Progress p) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/octet-stream");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Download failed (HTTP " + code + ")");
            long total = c.getContentLengthLong(), done = 0, lastReport = 0;
            //noinspection ResultOfMethodCallIgnored
            dest.getParentFile().mkdirs();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null && done - lastReport > 256 * 1024) {
                        lastReport = done;
                        p.update(done, total);
                    }
                }
            }
            if (p != null) p.update(done, total);
        } finally {
            c.disconnect();
        }
    }
}
