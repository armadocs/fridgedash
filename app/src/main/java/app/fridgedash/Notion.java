package app.fridgedash;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The shared to-do list lives in a Notion database called "Daily Brief".
 * Grok writes to it, the fridge and phone read it and tick items off.
 *
 * Properties (created by {@link #connect}):
 *   Item (title) · Detail (text) · Section (select) · Done (checkbox) ·
 *   Due (date) · Key (text, stable id Grok uses to update the same item) ·
 *   Source (select: Grok / Me) · Brief date (date)
 *
 * All calls are network calls: run them off the UI thread.
 */
final class Notion {
    private Notion() {}

    static final String API = "https://api.notion.com/v1/";
    static final String VERSION = "2022-06-28";
    static final String DB_TITLE = "Daily Brief";

    /** Section name in Notion → how the fridge shows it. Order = order on screen. */
    static final Map<String, String> SECTIONS = new LinkedHashMap<>();
    static {
        SECTIONS.put("Needs you", "tasks");
        SECTIONS.put("Moving", "info");
        SECTIONS.put("Coming up", "events");
        SECTIONS.put("Parking lot", "later");
    }

    static boolean configured(Context c) {
        return !Prefs.notionToken(c).isEmpty() && !Prefs.notionDb(c).isEmpty();
    }

    // ------------------------------------------------------------------ setup

    static final class ConnectResult {
        String dbId, parentTitle;
        boolean created;
    }

    /** Finds an existing "Daily Brief" database the key can see, or creates one in a shared page. */
    static ConnectResult connect(String token) throws IOException {
        ConnectResult r = new ConnectResult();
        try {
            JSONObject dbs = call("POST", "search", token, new JSONObject()
                    .put("query", DB_TITLE)
                    .put("filter", new JSONObject().put("property", "object").put("value", "database")));
            JSONArray res = dbs.optJSONArray("results");
            if (res != null) {
                for (int i = 0; i < res.length(); i++) {
                    JSONObject db = res.getJSONObject(i);
                    if (DB_TITLE.equalsIgnoreCase(plain(db.optJSONArray("title"))) && !db.optBoolean("archived")) {
                        ensureSchema(token, db.getString("id"));
                        r.dbId = db.getString("id");
                        r.parentTitle = "(existing database)";
                        return r;
                    }
                }
            }
            JSONObject pages = call("POST", "search", token, new JSONObject()
                    .put("filter", new JSONObject().put("property", "object").put("value", "page")));
            JSONArray pr = pages.optJSONArray("results");
            JSONObject parent = null;
            if (pr != null) {
                // Prefer a top-level page (shared directly with the integration).
                for (int i = 0; i < pr.length() && parent == null; i++) {
                    JSONObject p = pr.getJSONObject(i);
                    if ("workspace".equals(p.optJSONObject("parent") == null ? "" :
                            p.getJSONObject("parent").optString("type"))) parent = p;
                }
                if (parent == null && pr.length() > 0) parent = pr.getJSONObject(0);
            }
            if (parent == null) {
                throw new IOException("The key can't see any Notion page yet. In Notion, open a page "
                        + "(e.g. \"Fridge\"), tap ··· → Connections → add \"FridgeDash\", then try again.");
            }
            JSONObject body = new JSONObject()
                    .put("parent", new JSONObject().put("type", "page_id").put("page_id", parent.getString("id")))
                    .put("title", new JSONArray().put(new JSONObject().put("type", "text")
                            .put("text", new JSONObject().put("content", DB_TITLE))))
                    .put("properties", schema());
            JSONObject db = call("POST", "databases", token, body);
            r.dbId = db.getString("id");
            r.parentTitle = pageTitle(parent);
            r.created = true;
            return r;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from Notion: " + e.getMessage());
        }
    }

    private static JSONObject schema() throws JSONException {
        JSONArray sectionOpts = new JSONArray()
                .put(opt("Needs you", "orange")).put(opt("Moving", "green"))
                .put(opt("Coming up", "blue")).put(opt("Parking lot", "gray"));
        return new JSONObject()
                .put("Item", new JSONObject().put("title", new JSONObject()))
                .put("Detail", new JSONObject().put("rich_text", new JSONObject()))
                .put("Section", new JSONObject().put("select", new JSONObject().put("options", sectionOpts)))
                .put("Done", new JSONObject().put("checkbox", new JSONObject()))
                .put("Due", new JSONObject().put("date", new JSONObject()))
                .put("Key", new JSONObject().put("rich_text", new JSONObject()))
                .put("Source", new JSONObject().put("select", new JSONObject().put("options",
                        new JSONArray().put(opt("Grok", "purple")).put(opt("Me", "yellow")))))
                .put("Brief date", new JSONObject().put("date", new JSONObject()));
    }

    /** Adds any missing properties to an existing database (never removes anything). */
    private static void ensureSchema(String token, String dbId) throws IOException, JSONException {
        JSONObject db = call("GET", "databases/" + dbId, token, null);
        JSONObject have = db.optJSONObject("properties");
        JSONObject want = schema();
        JSONObject add = new JSONObject();
        java.util.Iterator<String> keys = want.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (k.equals("Item")) continue; // title property exists under some name already
            if (have == null || !have.has(k)) add.put(k, want.get(k));
        }
        if (add.length() > 0) call("PATCH", "databases/" + dbId, token, new JSONObject().put("properties", add));
    }

    private static JSONObject opt(String name, String color) throws JSONException {
        return new JSONObject().put("name", name).put("color", color);
    }

    /** Copies a brief (e.g. the one from GitHub) into the database. Returns how many were added. */
    static int importBrief(String token, String dbId, Brief b) throws IOException {
        int n = 0;
        for (Brief.Section s : b.sections) {
            String section = sectionNameFor(s);
            for (Brief.Item it : s.items) {
                try {
                    JSONObject props = new JSONObject()
                            .put("Item", new JSONObject().put("title", text(it.title)))
                            .put("Detail", new JSONObject().put("rich_text", text(it.detail)))
                            .put("Section", new JSONObject().put("select", new JSONObject().put("name", section)))
                            .put("Key", new JSONObject().put("rich_text", text(it.id)))
                            .put("Source", new JSONObject().put("select", new JSONObject().put("name", "Grok")));
                    if (!b.date.isEmpty()) props.put("Brief date", new JSONObject()
                            .put("date", new JSONObject().put("start", b.date)));
                    if (it.when != null && !it.when.isEmpty()) props.put("Due", new JSONObject()
                            .put("date", new JSONObject().put("start", it.when)));
                    call("POST", "pages", token, new JSONObject()
                            .put("parent", new JSONObject().put("database_id", dbId))
                            .put("properties", props));
                    n++;
                } catch (JSONException e) {
                    throw new IOException(e.getMessage());
                }
            }
        }
        return n;
    }

    private static String sectionNameFor(Brief.Section s) {
        for (Map.Entry<String, String> e : SECTIONS.entrySet()) {
            if (e.getKey().equalsIgnoreCase(s.title) || e.getValue().equals(s.kind)) return e.getKey();
        }
        return "Needs you";
    }

    // ------------------------------------------------------------------ daily use

    /** Open items, plus items ticked off today (shown crossed out). */
    static Brief fetch(Context c) throws IOException {
        String token = Prefs.notionToken(c), db = Prefs.notionDb(c);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Brief b = new Brief();
        b.date = today;
        b.title = "Daily brief";
        b.live = true;
        Map<String, Brief.Section> secs = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : SECTIONS.entrySet()) {
            Brief.Section s = new Brief.Section();
            s.id = e.getValue();
            s.title = e.getKey();
            s.kind = e.getValue();
            secs.put(e.getKey(), s);
        }
        try {
            JSONObject filter = new JSONObject().put("or", new JSONArray()
                    .put(new JSONObject().put("property", "Done")
                            .put("checkbox", new JSONObject().put("equals", false)))
                    .put(new JSONObject().put("timestamp", "last_edited_time")
                            .put("last_edited_time", new JSONObject().put("on_or_after", today))));
            JSONArray sorts = new JSONArray()
                    .put(new JSONObject().put("property", "Due").put("direction", "ascending"))
                    .put(new JSONObject().put("timestamp", "created_time").put("direction", "ascending"));
            String cursor = null;
            for (int page = 0; page < 5; page++) {
                JSONObject q = new JSONObject().put("filter", filter).put("sorts", sorts).put("page_size", 100);
                if (cursor != null) q.put("start_cursor", cursor);
                JSONObject res = call("POST", "databases/" + db + "/query", token, q);
                JSONArray rows = res.optJSONArray("results");
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        JSONObject p = row.optJSONObject("properties");
                        if (p == null) continue;
                        Brief.Item it = new Brief.Item();
                        it.pageId = row.getString("id");
                        it.title = plain(titleOf(p));
                        if (it.title.isEmpty()) continue;
                        it.detail = plain(arr(p, "Detail", "rich_text"));
                        it.id = plain(arr(p, "Key", "rich_text"));
                        if (it.id.isEmpty()) it.id = it.pageId;
                        JSONObject due = obj(p, "Due", "date");
                        it.when = due == null ? "" : due.optString("start", "");
                        JSONObject doneProp = p.optJSONObject("Done");
                        it.done = doneProp != null && doneProp.optBoolean("checkbox");
                        JSONObject sel = obj(p, "Section", "select");
                        String sectionName = sel == null ? "Needs you" : sel.optString("name", "Needs you");
                        Brief.Section s = secs.get(sectionName);
                        if (s == null) {
                            s = new Brief.Section();
                            s.id = sectionName.toLowerCase(Locale.US);
                            s.title = sectionName;
                            s.kind = "tasks";
                            secs.put(sectionName, s);
                        }
                        s.items.add(it);
                    }
                }
                if (!res.optBoolean("has_more")) break;
                cursor = res.optString("next_cursor", null);
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from Notion: " + e.getMessage());
        }
        for (Brief.Section s : secs.values()) if (!s.items.isEmpty()) b.sections.add(s);
        return b;
    }

    /** Ticks or unticks an item in Notion. */
    static void setDone(Context c, String pageId, boolean done) throws IOException {
        try {
            call("PATCH", "pages/" + pageId, Prefs.notionToken(c), new JSONObject().put("properties",
                    new JSONObject().put("Done", new JSONObject().put("checkbox", done))));
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JSONArray text(String s) throws JSONException {
        if (s == null) s = "";
        if (s.length() > 1900) s = s.substring(0, 1900);
        return new JSONArray().put(new JSONObject().put("type", "text")
                .put("text", new JSONObject().put("content", s)));
    }

    private static JSONArray titleOf(JSONObject props) {
        java.util.Iterator<String> keys = props.keys();
        while (keys.hasNext()) {
            JSONObject p = props.optJSONObject(keys.next());
            if (p != null && "title".equals(p.optString("type"))) return p.optJSONArray("title");
        }
        return null;
    }

    private static JSONArray arr(JSONObject props, String name, String type) {
        JSONObject p = props.optJSONObject(name);
        return p == null ? null : p.optJSONArray(type);
    }

    private static JSONObject obj(JSONObject props, String name, String type) {
        JSONObject p = props.optJSONObject(name);
        return p == null ? null : p.optJSONObject(type);
    }

    static String plain(JSONArray rich) {
        if (rich == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rich.length(); i++) {
            JSONObject o = rich.optJSONObject(i);
            if (o != null) sb.append(o.optString("plain_text", ""));
        }
        return sb.toString().trim();
    }

    private static String pageTitle(JSONObject page) {
        JSONObject props = page.optJSONObject("properties");
        if (props == null) return "(page)";
        String t = plain(titleOf(props));
        return t.isEmpty() ? "(untitled page)" : t;
    }

    private static okhttp3.OkHttpClient http;

    private static synchronized okhttp3.OkHttpClient http() {
        if (http == null) {
            http = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
        }
        return http;
    }

    static JSONObject call(String method, String path, String token, JSONObject body) throws IOException {
        okhttp3.RequestBody rb = body == null ? null
                : okhttp3.RequestBody.create(body.toString(), okhttp3.MediaType.get("application/json; charset=utf-8"));
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(API + path)
                .method(method, rb)
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", VERSION)
                .header("User-Agent", "FridgeDash")
                .build();
        try (okhttp3.Response r = http().newCall(req).execute()) {
            int code = r.code();
            String resp = r.body() == null ? "" : r.body().string();
            if (code >= 400) {
                String msg = resp;
                try { msg = new JSONObject(resp).optString("message", resp); } catch (JSONException ignored) {}
                if (code == 401) throw new IOException("Notion rejected the key (401). Check it in Settings.");
                if (code == 404) throw new IOException("Notion can't find it (404). Is the page/database "
                        + "connected to the FridgeDash integration? " + msg);
                if (code == 429) throw new IOException("Notion rate limit (429), will retry.");
                throw new IOException("Notion error " + code + ": " + msg);
            }
            return resp.isEmpty() ? new JSONObject() : new JSONObject(resp);
        } catch (JSONException e) {
            throw new IOException("Bad answer from Notion: " + e.getMessage());
        }
    }

}
