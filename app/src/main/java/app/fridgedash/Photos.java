package app.fridgedash;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;

/**
 * Google Photos shared albums (paste the album's share link in Settings).
 * Reads the album's public web page; new photos added to the album show up
 * within a few hours. Unofficial: if Google changes that page, this stops
 * working until FridgeDash is updated.
 */
final class Photos {
    private Photos() {}

    static final class Album {
        String link = "", name = "";
        final List<Art.Piece> items = new ArrayList<>();
    }

    private static final long MAX_AGE = 4L * 3600 * 1000;
    // Each photo appears in the page data as ["https://lh3.googleusercontent.com/pw/…",width,height,…
    private static final Pattern ITEM =
            Pattern.compile("\\[\"(https://lh3\\.googleusercontent\\.com/pw/[A-Za-z0-9_\\-]+)\",(\\d+),(\\d+)");
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);

    /** The share links from Settings, one per line. */
    static List<String> links(Context c) {
        List<String> out = new ArrayList<>();
        for (String l : Prefs.photoAlbums(c).split("[\\s,]+")) {
            l = l.trim();
            if (l.startsWith("https://photos.app.goo.gl/") || l.startsWith("https://photos.google.com/")) out.add(l);
        }
        return out;
    }

    /** Album names known so far (from the cache), in Settings order, without network. */
    static List<String> cachedNames(Context c) {
        List<String> out = new ArrayList<>();
        for (String l : links(c)) {
            Album a = readCache(c, l);
            out.add(a != null && !a.name.isEmpty() ? a.name : "Album " + (out.size() + 1));
        }
        return out;
    }

    /** Loads one album, from the cache if fresh. Network: off the UI thread. */
    static Album album(Context c, String link, boolean force) throws Exception {
        File f = cacheFile(c, link);
        Album cached = readCache(c, link);
        if (!force && cached != null && !cached.items.isEmpty()
                && System.currentTimeMillis() - f.lastModified() < MAX_AGE) return cached;
        try {
            Album a = fetch(link);
            if (a.items.isEmpty()) throw new java.io.IOException("no photos found in that album "
                    + "(is the link a shared album link?)");
            JSONArray items = new JSONArray();
            for (Art.Piece p : a.items) items.put(new JSONObject().put("i", p.image).put("r", p.ratio));
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(new JSONObject().put("name", a.name).put("items", items).toString()
                        .getBytes(StandardCharsets.UTF_8));
            }
            return a;
        } catch (Exception e) {
            if (cached != null && !cached.items.isEmpty()) return cached; // offline: keep showing what we have
            throw e;
        }
    }

    private static Album fetch(String link) throws Exception {
        String html = Art.text(new Request.Builder().url(link)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept-Language", "en")
                .build());
        Album a = new Album();
        a.link = link;
        Matcher t = TITLE.matcher(html);
        if (t.find()) {
            String name = t.group(1).replace("&#39;", "'").replace("&amp;", "&").replace("&quot;", "\"").trim();
            int dash = name.lastIndexOf(" - Google Photos");
            if (dash < 0) dash = name.lastIndexOf(" – Google Photos");
            a.name = dash > 0 ? name.substring(0, dash).trim() : name;
        }
        Map<String, double[]> seen = new LinkedHashMap<>();
        Matcher m = ITEM.matcher(html);
        while (m.find()) {
            double w = Double.parseDouble(m.group(2)), h = Double.parseDouble(m.group(3));
            if (w < 300 || h < 300) continue; // icons, not photos
            if (!seen.containsKey(m.group(1))) seen.put(m.group(1), new double[]{w, h});
        }
        for (Map.Entry<String, double[]> e : seen.entrySet()) {
            Art.Piece p = new Art.Piece();
            p.image = e.getKey();
            p.ratio = e.getValue()[0] / e.getValue()[1];
            p.museum = a.name;
            a.items.add(p);
        }
        return a;
    }

    private static File cacheFile(Context c, String link) {
        return new File(c.getFilesDir(), "album_" + Integer.toHexString(link.hashCode()) + ".json");
    }

    private static Album readCache(Context c, String link) {
        File f = cacheFile(c, link);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            JSONObject j = new JSONObject(new String(b, StandardCharsets.UTF_8));
            Album a = new Album();
            a.link = link;
            a.name = j.optString("name");
            JSONArray items = j.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                Art.Piece p = new Art.Piece();
                p.image = o.optString("i");
                p.ratio = o.optDouble("r", 0);
                p.museum = a.name;
                a.items.add(p);
            }
            return a;
        } catch (Exception e) {
            return null;
        }
    }
}
