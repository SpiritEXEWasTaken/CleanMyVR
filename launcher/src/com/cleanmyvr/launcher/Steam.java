package com.cleanmyvr.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Steam store banners (no API key needed). Steam's "header" image is the same wide shape as the
 * launcher tiles; plenty of VR games have one on Steam even when SteamGridDB has nothing.
 * Call from a background thread.
 */
final class Steam {
    private static final String CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/";

    static final class Game {
        int id;
        String name;
        @Override public String toString() { return name; }
    }

    static List<Game> search(String term) throws Exception {
        String q = URLEncoder.encode(term, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://store.steampowered.com/api/storesearch/?l=english&cc=US&term=" + q).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        List<Game> out = new ArrayList<>();
        try {
            if (c.getResponseCode() != 200) return out;
            JSONArray items = new JSONObject(new String(Http.read(c.getInputStream()), "UTF-8")).optJSONArray("items");
            if (items == null) return out;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                Game g = new Game();
                g.id = o.getInt("id");
                g.name = o.optString("name", "?");
                out.add(g);
            }
        } finally {
            c.disconnect();
        }
        return out;
    }

    /** The banner images Steam has for a game, best first (missing ones are skipped by the picker). */
    static List<SteamGridDb.Grid> banners(int appId) {
        List<SteamGridDb.Grid> out = new ArrayList<>();
        for (String file : new String[]{"header.jpg", "capsule_616x353.jpg", "capsule_467x181.jpg"}) {
            SteamGridDb.Grid g = new SteamGridDb.Grid();
            g.url = CDN + appId + "/" + file;
            g.thumb = g.url;
            out.add(g);
        }
        return out;
    }
}
