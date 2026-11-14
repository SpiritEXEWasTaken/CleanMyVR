package com.cleanmyvr.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal SteamGridDB v2 client (https://www.steamgriddb.com/api/v2).
 * Needs a free personal API key: steamgriddb.com, then Preferences, then API.
 * Call from a background thread.
 */
final class SteamGridDb {
    private static final String BASE = "https://www.steamgriddb.com/api/v2";
    /** Horizontal "grid" sizes, the same shape as the launcher's tiles. */
    private static final String DIMENSIONS = "920x430,460x215";

    static final class Game {
        int id;
        String name, year;
        @Override public String toString() { return year.isEmpty() ? name : name + "  (" + year + ")"; }
    }

    static final class Grid {
        String url, thumb;
        int width, height;
    }

    private final String key;

    SteamGridDb(String key) { this.key = key; }

    List<Game> search(String term) throws Exception {
        String q = URLEncoder.encode(term, "UTF-8").replace("+", "%20");
        JSONArray data = get("/search/autocomplete/" + q).optJSONArray("data");
        List<Game> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            Game g = new Game();
            g.id = o.getInt("id");
            g.name = o.optString("name", "?");
            long rel = o.optLong("release_date", 0);
            g.year = rel > 0 ? String.valueOf(1970 + (int) (rel / 31556952L)) : "";
            out.add(g);
        }
        return out;
    }

    List<Grid> grids(int gameId) throws Exception {
        JSONArray data = get("/grids/game/" + gameId + "?dimensions=" + DIMENSIONS).optJSONArray("data");
        List<Grid> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            Grid g = new Grid();
            g.url = o.getString("url");
            g.thumb = o.optString("thumb", g.url);
            g.width = o.optInt("width");
            g.height = o.optInt("height");
            out.add(g);
        }
        return out;
    }

    private JSONObject get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        int code = c.getResponseCode();
        if (code == 401 || code == 403) throw new IOException("SteamGridDB rejected the API key");
        if (code == 404) return new JSONObject("{\"data\":[]}");
        if (code != 200) throw new IOException("SteamGridDB error " + code);
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(new String(read(in), "UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    static byte[] download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        try (InputStream in = c.getInputStream()) {
            return read(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
