package app.fridgedash;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The daily brief: a small JSON file that Grok writes (e.g. brief.json in a
 * private GitHub repo) and the fridge reads. Check-offs are stored on the
 * fridge, per brief date.
 *
 * Format:
 * {
 *   "date": "2026-10-29",
 *   "title": "Armada daily brief",
 *   "generated_at": "2026-10-29T08:25:00-07:00",
 *   "sections": [
 *     {"id": "needs",   "title": "Needs you",   "kind": "tasks",  "items": [ ... ]},
 *     {"id": "moving",  "title": "Moving",      "kind": "info",   "items": [ ... ]},
 *     {"id": "next",    "title": "Coming up",   "kind": "events", "items": [ ... ]},
 *     {"id": "parking", "title": "Parking lot", "kind": "later",  "items": [ ... ]}
 *   ]
 * }
 * item: {"id": "short-stable-id", "title": "...", "detail": "...", "when": "2026-10-30T09:00"}
 */
final class Brief {

    static final String DEFAULT_URL = "https://api.github.com/repos/armadocs/fridge-brief/contents/brief.json";

    static final class Item {
        String id, title, detail, when;
    }

    static final class Section {
        String id, title, kind;
        final List<Item> items = new ArrayList<>();
    }

    String date = "", title = "Daily brief", generatedAt = "";
    final List<Section> sections = new ArrayList<>();

    // ------------------------------------------------------------------ parsing

    static Brief parse(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            Brief b = new Brief();
            b.date = o.optString("date");
            b.title = o.optString("title", "Daily brief");
            b.generatedAt = o.optString("generated_at");
            JSONArray secs = o.optJSONArray("sections");
            if (secs == null) throw new IOException("No \"sections\" in the brief");
            for (int i = 0; i < secs.length(); i++) {
                JSONObject so = secs.getJSONObject(i);
                Section s = new Section();
                s.id = so.optString("id", "s" + i);
                s.title = so.optString("title", "");
                s.kind = so.optString("kind", guessKind(s.id, s.title));
                JSONArray items = so.optJSONArray("items");
                if (items != null) {
                    for (int j = 0; j < items.length(); j++) {
                        Object raw = items.get(j);
                        Item it = new Item();
                        if (raw instanceof JSONObject) {
                            JSONObject io = (JSONObject) raw;
                            it.title = io.optString("title", "");
                            it.detail = io.optString("detail", "");
                            it.when = io.optString("when", "");
                            it.id = io.optString("id", "");
                        } else {
                            it.title = String.valueOf(raw);
                            it.detail = "";
                            it.when = "";
                            it.id = "";
                        }
                        if (it.id.isEmpty()) it.id = s.id + "-" + Integer.toHexString(it.title.hashCode());
                        if (!it.title.isEmpty()) s.items.add(it);
                    }
                }
                if (!s.items.isEmpty()) b.sections.add(s);
            }
            return b;
        } catch (org.json.JSONException e) {
            throw new IOException("The brief file isn't valid JSON: " + e.getMessage());
        }
    }

    private static String guessKind(String id, String title) {
        String t = (id + " " + title).toLowerCase();
        if (t.contains("need") || t.contains("todo") || t.contains("task")) return "tasks";
        if (t.contains("park") || t.contains("later")) return "later";
        if (t.contains("next") || t.contains("tomorrow") || t.contains("coming") || t.contains("calendar")) return "events";
        return "info";
    }

    int taskCount() {
        int n = 0;
        for (Section s : sections) if ("tasks".equals(s.kind)) n += s.items.size();
        return n;
    }

    // ------------------------------------------------------------------ check-offs (on the fridge)

    private static SharedPreferences done(Context c) {
        return c.getSharedPreferences("brief_done", Context.MODE_PRIVATE);
    }

    boolean isDone(Context c, Item it) {
        return done(c).getBoolean(date + "/" + it.id, false);
    }

    void setDone(Context c, Item it, boolean v) {
        done(c).edit().putBoolean(date + "/" + it.id, v).apply();
    }

    int doneCount(Context c) {
        int n = 0;
        for (Section s : sections) {
            if (!"tasks".equals(s.kind)) continue;
            for (Item it : s.items) if (isDone(c, it)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ fetch + cache

    static File cacheFile(Context c) { return new File(c.getFilesDir(), "brief.json"); }

    /** Last brief saved on the fridge, or null. */
    static Brief cached(Context c) {
        File f = cacheFile(c);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return parse(new String(readAll(in, 512 * 1024), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean configured(Context c) {
        return !Prefs.briefUrl(c).isEmpty();
    }

    /**
     * Downloads the brief (network: call off the UI thread). Returns the new brief,
     * or null if it hasn't changed since last time.
     */
    static Brief fetch(Context c) throws IOException {
        String url = Prefs.briefUrl(c);
        if (url.isEmpty()) throw new IOException("No brief link set (Settings → Daily brief)");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "FridgeDash");
        conn.setRequestProperty("Cache-Control", "no-cache");
        if (url.contains("api.github.com")) {
            conn.setRequestProperty("Accept", "application/vnd.github.raw+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        }
        String token = Prefs.briefToken(c);
        if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        String etag = Prefs.raw(c).getString("brief_etag", "");
        if (!etag.isEmpty() && cacheFile(c).exists()) conn.setRequestProperty("If-None-Match", etag);
        try {
            int code = conn.getResponseCode();
            if (code == 304) return null;
            if (code == 401) throw new IOException("The GitHub key was rejected (401). Check it in Settings.");
            if (code == 403) throw new IOException("Access denied or rate-limited (403).");
            if (code == 404) throw new IOException("Brief file not found (404): the file doesn't exist yet, "
                    + "or the key can't see that repository.");
            if (code != 200) throw new IOException("Server answered HTTP " + code);
            byte[] body;
            try (InputStream in = conn.getInputStream()) {
                body = readAll(in, 512 * 1024);
            }
            String json = new String(body, "UTF-8");
            Brief b = parse(json); // validate before replacing the cached copy
            try (FileOutputStream out = new FileOutputStream(cacheFile(c))) {
                out.write(body);
            }
            String newTag = conn.getHeaderField("ETag");
            Prefs.raw(c).edit()
                    .putString("brief_etag", newTag == null ? "" : newTag)
                    .putLong("brief_fetched_at", System.currentTimeMillis())
                    .apply();
            return b;
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > limit) throw new IOException("Brief file too large");
        }
        return bos.toByteArray();
    }
}
