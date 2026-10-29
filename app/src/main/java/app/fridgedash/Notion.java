package app.fridgedash;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The shared to-do list lives in a Notion database whose title contains
 * "Daily Brief" (Grok made "Armada Daily Brief"). Grok writes to it; the
 * fridge and phone read it and tick items off.
 *
 * Column names are detected, not assumed, so Grok's own layout works:
 * title = the item; a checkbox (Done/Completed…) or a status/select
 * ("Status" = Done) marks it finished; a select (Section/Category…) groups
 * it; text columns give detail and a stable key; a date column gives the due date.
 *
 * All calls are network calls: run them off the UI thread.
 */
final class Notion {
    private Notion() {}

    static final String API = "https://api.notion.com/v1/";
    static final String VERSION = "2022-06-28";
    static final String DB_TITLE = "Daily Brief";

    static boolean configured(Context c) {
        return !Prefs.notionToken(c).isEmpty() && !Prefs.notionDb(c).isEmpty();
    }

    // ------------------------------------------------------------------ column mapping

    /** Which Notion columns mean what. Stored as JSON in Prefs after connecting. */
    static final class Cols {
        String title = "", detail = "", section = "", due = "", key = "";
        String done = "", doneType = "", doneValue = "", openValue = "";

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("title", title).put("detail", detail).put("section", section)
                    .put("due", due).put("key", key).put("done", done).put("doneType", doneType)
                    .put("doneValue", doneValue).put("openValue", openValue);
        }

        static Cols fromJson(String s) {
            Cols c = new Cols();
            try {
                JSONObject o = new JSONObject(s);
                c.title = o.optString("title"); c.detail = o.optString("detail");
                c.section = o.optString("section"); c.due = o.optString("due");
                c.key = o.optString("key"); c.done = o.optString("done");
                c.doneType = o.optString("doneType"); c.doneValue = o.optString("doneValue");
                c.openValue = o.optString("openValue");
            } catch (JSONException ignored) {
            }
            return c;
        }

        String describe() {
            return "title \"" + title + "\""
                    + (done.isEmpty() ? ", no done column" : ", done \"" + done + "\""
                        + (doneType.equals("checkbox") ? "" : " = " + doneValue))
                    + (section.isEmpty() ? "" : ", section \"" + section + "\"")
                    + (detail.isEmpty() ? "" : ", detail \"" + detail + "\"")
                    + (due.isEmpty() ? "" : ", date \"" + due + "\"")
                    + (key.isEmpty() ? "" : ", key \"" + key + "\"");
        }
    }

    private static boolean nameIs(String name, String... words) {
        String n = name.toLowerCase(Locale.US).trim();
        for (String w : words) if (n.equals(w)) return true;
        return false;
    }

    private static boolean nameHas(String name, String... words) {
        String n = name.toLowerCase(Locale.US);
        for (String w : words) if (n.contains(w)) return true;
        return false;
    }

    /** Works out the column roles from a database's property definitions. */
    static Cols detect(JSONObject props) {
        Cols c = new Cols();
        // Pass 1: exact/likely names.
        Iterator<String> it = props.keys();
        while (it.hasNext()) {
            String name = it.next();
            JSONObject p = props.optJSONObject(name);
            if (p == null) continue;
            String type = p.optString("type");
            switch (type) {
                case "title":
                    c.title = name;
                    break;
                case "checkbox":
                    if (c.done.isEmpty() || nameIs(name, "done", "completed", "complete", "finished", "resolved")) {
                        c.done = name;
                        c.doneType = "checkbox";
                    }
                    break;
                case "status":
                case "select":
                    if (nameHas(name, "status", "state") && !"checkbox".equals(c.doneType)) {
                        String dv = optionLike(p.optJSONObject(type), "done", "complete", "resolved", "finished", "closed");
                        if (!dv.isEmpty()) {
                            c.done = name;
                            c.doneType = type;
                            c.doneValue = dv;
                            c.openValue = optionLike(p.optJSONObject(type), "open", "to do", "todo",
                                    "not started", "pending", "new", "in progress");
                        }
                    } else if (type.equals("select") && (c.section.isEmpty()
                            || nameHas(name, "section", "category", "group", "bucket", "lane"))
                            && !nameHas(name, "source", "priority", "owner")) {
                        c.section = name;
                    }
                    break;
                case "rich_text":
                    if (nameIs(name, "key", "id", "slug", "item id", "item key", "uid")) c.key = name;
                    else if (nameHas(name, "detail", "note", "description", "summary", "context", "info")) c.detail = name;
                    break;
                case "date":
                    if (nameHas(name, "due", "deadline", "when", "scheduled") || (c.due.isEmpty()
                            && !nameHas(name, "brief", "created", "edited", "updated", "added"))) c.due = name;
                    break;
                default:
                    break;
            }
        }
        // Pass 2: fall back to any remaining text column for detail.
        if (c.detail.isEmpty()) {
            it = props.keys();
            while (it.hasNext()) {
                String name = it.next();
                JSONObject p = props.optJSONObject(name);
                if (p != null && "rich_text".equals(p.optString("type")) && !name.equals(c.key)) {
                    c.detail = name;
                    break;
                }
            }
        }
        return c;
    }

    private static String optionLike(JSONObject typeObj, String... words) {
        if (typeObj == null) return "";
        JSONArray opts = typeObj.optJSONArray("options");
        if (opts == null) return "";
        for (String w : words) {
            for (int i = 0; i < opts.length(); i++) {
                JSONObject o = opts.optJSONObject(i);
                String n = o == null ? "" : o.optString("name");
                if (n.toLowerCase(Locale.US).contains(w)) return n;
            }
        }
        return "";
    }

    /** Section name → how the fridge shows it. */
    static String kindFor(String section) {
        String s = section.toLowerCase(Locale.US);
        if (s.contains("need") || s.contains("action") || s.contains("to do") || s.contains("todo") || s.contains("urgent"))
            return "tasks";
        if (s.contains("park") || s.contains("later") || s.contains("someday") || s.contains("if time")) return "later";
        if (s.contains("coming") || s.contains("tomorrow") || s.contains("next") || s.contains("upcoming")
                || s.contains("calendar") || s.contains("schedule")) return "events";
        if (s.contains("moving") || s.contains("flight") || s.contains("fyi") || s.contains("progress")
                || s.contains("watch")) return "info";
        return "tasks";
    }

    private static int kindOrder(String kind) {
        switch (kind) {
            case "tasks": return 0;
            case "info": return 1;
            case "events": return 2;
            default: return 3;
        }
    }

    // ------------------------------------------------------------------ setup

    static final class ConnectResult {
        String dbId, dbTitle, parentTitle;
        boolean created;
        Cols cols;
        boolean addedDone;
    }

    /** Uses an existing database whose title contains "Daily Brief", or creates one in a shared page. */
    static ConnectResult connect(String token) throws IOException {
        ConnectResult r = new ConnectResult();
        try {
            JSONObject dbs = call("POST", "search", token, new JSONObject()
                    .put("query", DB_TITLE)
                    .put("filter", new JSONObject().put("property", "object").put("value", "database")));
            JSONArray res = dbs.optJSONArray("results");
            JSONObject best = null;
            if (res != null) {
                for (int i = 0; i < res.length(); i++) {
                    JSONObject db = res.getJSONObject(i);
                    if (db.optBoolean("archived")) continue;
                    String t = plain(db.optJSONArray("title"));
                    if (!t.toLowerCase(Locale.US).contains("daily brief")) continue;
                    if (best == null || t.toLowerCase(Locale.US).contains("armada")) best = db;
                }
            }
            if (best != null) {
                r.dbId = best.getString("id");
                r.dbTitle = plain(best.optJSONArray("title"));
                r.cols = detect(best.optJSONObject("properties"));
                if (r.cols.done.isEmpty()) {
                    // Nothing to tick: add a Done checkbox (Grok should use it too).
                    call("PATCH", "databases/" + r.dbId, token, new JSONObject().put("properties",
                            new JSONObject().put("Done", new JSONObject().put("checkbox", new JSONObject()))));
                    r.cols.done = "Done";
                    r.cols.doneType = "checkbox";
                    r.addedDone = true;
                }
                return r;
            }

            JSONObject pages = call("POST", "search", token, new JSONObject()
                    .put("filter", new JSONObject().put("property", "object").put("value", "page")));
            JSONArray pr = pages.optJSONArray("results");
            JSONObject parent = null;
            if (pr != null) {
                for (int i = 0; i < pr.length() && parent == null; i++) {
                    JSONObject p = pr.getJSONObject(i);
                    JSONObject par = p.optJSONObject("parent");
                    if (par != null && "workspace".equals(par.optString("type"))) parent = p;
                }
                if (parent == null && pr.length() > 0) parent = pr.getJSONObject(0);
            }
            if (parent == null) {
                throw new IOException("The key can't see the Daily Brief database or any page. In Notion, open the "
                        + "\"Armada Daily Brief\" database (or the page it's in), tap ··· → Connections → "
                        + "add \"FridgeDash\", then try again.");
            }
            JSONObject body = new JSONObject()
                    .put("parent", new JSONObject().put("type", "page_id").put("page_id", parent.getString("id")))
                    .put("title", new JSONArray().put(new JSONObject().put("type", "text")
                            .put("text", new JSONObject().put("content", DB_TITLE))))
                    .put("properties", schema());
            JSONObject db = call("POST", "databases", token, body);
            r.dbId = db.getString("id");
            r.dbTitle = DB_TITLE;
            r.parentTitle = pageTitle(parent);
            r.cols = detect(db.optJSONObject("properties"));
            r.created = true;
            return r;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from Notion: " + e.getMessage());
        }
    }

    static void saveConnection(Context c, ConnectResult r) {
        Prefs.setNotionDb(c, r.dbId);
        try {
            Prefs.raw(c).edit().putString("notion_cols", r.cols.toJson().toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private static Cols cols(Context c) {
        return Cols.fromJson(Prefs.raw(c).getString("notion_cols", "{}"));
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

    private static JSONObject opt(String name, String color) throws JSONException {
        return new JSONObject().put("name", name).put("color", color);
    }

    /** Copies a brief (e.g. the GitHub one) into a freshly created database. */
    static int importBrief(String token, String dbId, Brief b) throws IOException {
        int n = 0;
        for (Brief.Section s : b.sections) {
            String section = s.title.isEmpty() ? "Needs you" : s.title;
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

    // ------------------------------------------------------------------ daily use

    private static JSONObject notDoneFilter(Cols c) throws JSONException {
        if ("checkbox".equals(c.doneType)) {
            return new JSONObject().put("property", c.done).put("checkbox", new JSONObject().put("equals", false));
        }
        return new JSONObject().put("property", c.done)
                .put(c.doneType, new JSONObject().put("does_not_equal", c.doneValue));
    }

    private static boolean isDone(Cols c, JSONObject props) {
        if (c.done.isEmpty()) return false;
        JSONObject p = props.optJSONObject(c.done);
        if (p == null) return false;
        if ("checkbox".equals(c.doneType)) return p.optBoolean("checkbox");
        JSONObject v = p.optJSONObject(c.doneType);
        return v != null && c.doneValue.equalsIgnoreCase(v.optString("name"));
    }

    /** Open items, plus items finished today (shown crossed out). */
    static Brief fetch(Context ctx) throws IOException {
        String token = Prefs.notionToken(ctx), db = Prefs.notionDb(ctx);
        Cols c = cols(ctx);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Brief b = new Brief();
        b.date = today;
        b.title = "Daily brief";
        b.live = true;
        Map<String, Brief.Section> secs = new LinkedHashMap<>();
        try {
            JSONObject q0 = new JSONObject().put("page_size", 100);
            if (!c.done.isEmpty()) {
                q0.put("filter", new JSONObject().put("or", new JSONArray()
                        .put(notDoneFilter(c))
                        .put(new JSONObject().put("timestamp", "last_edited_time")
                                .put("last_edited_time", new JSONObject().put("on_or_after", today)))));
            }
            JSONArray sorts = new JSONArray();
            if (!c.due.isEmpty()) sorts.put(new JSONObject().put("property", c.due).put("direction", "ascending"));
            sorts.put(new JSONObject().put("timestamp", "created_time").put("direction", "ascending"));
            q0.put("sorts", sorts);
            String cursor = null;
            for (int page = 0; page < 5; page++) {
                JSONObject q = new JSONObject(q0.toString());
                if (cursor != null) q.put("start_cursor", cursor);
                JSONObject res = call("POST", "databases/" + db + "/query", token, q);
                JSONArray rows = res.optJSONArray("results");
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        if (row.optBoolean("archived")) continue;
                        JSONObject p = row.optJSONObject("properties");
                        if (p == null) continue;
                        Brief.Item it = new Brief.Item();
                        it.pageId = row.getString("id");
                        it.title = plain(titleOf(p));
                        if (it.title.isEmpty()) continue;
                        it.detail = c.detail.isEmpty() ? "" : textOf(p.optJSONObject(c.detail));
                        it.id = c.key.isEmpty() ? "" : textOf(p.optJSONObject(c.key));
                        if (it.id.isEmpty()) it.id = it.pageId;
                        JSONObject due = c.due.isEmpty() ? null : obj(p, c.due, "date");
                        it.when = due == null ? "" : due.optString("start", "");
                        it.done = isDone(c, p);
                        JSONObject sel = c.section.isEmpty() ? null : obj(p, c.section, "select");
                        String sectionName = sel == null ? "Needs you" : sel.optString("name", "Needs you");
                        Brief.Section s = secs.get(sectionName);
                        if (s == null) {
                            s = new Brief.Section();
                            s.id = sectionName.toLowerCase(Locale.US);
                            s.title = sectionName;
                            s.kind = kindFor(sectionName);
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
        java.util.List<Brief.Section> list = new java.util.ArrayList<>(secs.values());
        java.util.Collections.sort(list, (a, z) -> Integer.compare(kindOrder(a.kind), kindOrder(z.kind)));
        b.sections.addAll(list);
        return b;
    }

    /** Ticks or unticks an item in Notion, using whatever done column the database has. */
    static void setDone(Context ctx, String pageId, boolean done) throws IOException {
        Cols c = cols(ctx);
        if (c.done.isEmpty()) throw new IOException("This database has no done column.");
        try {
            JSONObject val;
            if ("checkbox".equals(c.doneType)) {
                val = new JSONObject().put("checkbox", done);
            } else {
                String name = done ? c.doneValue : c.openValue;
                val = new JSONObject().put(c.doneType, name.isEmpty() ? JSONObject.NULL : new JSONObject().put("name", name));
            }
            call("PATCH", "pages/" + pageId, Prefs.notionToken(ctx),
                    new JSONObject().put("properties", new JSONObject().put(c.done, val)));
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
        Iterator<String> keys = props.keys();
        while (keys.hasNext()) {
            JSONObject p = props.optJSONObject(keys.next());
            if (p != null && "title".equals(p.optString("type"))) return p.optJSONArray("title");
        }
        return null;
    }

    /** Text of a rich_text, select, number or url property. */
    private static String textOf(JSONObject p) {
        if (p == null) return "";
        String type = p.optString("type");
        switch (type) {
            case "rich_text": return plain(p.optJSONArray("rich_text"));
            case "select": {
                JSONObject s = p.optJSONObject("select");
                return s == null ? "" : s.optString("name");
            }
            case "number": return p.isNull("number") ? "" : String.valueOf(p.opt("number"));
            case "url": return p.optString("url", "");
            default: return "";
        }
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
                if (code == 404) throw new IOException("Notion can't find it (404). Is the database connected "
                        + "to the FridgeDash integration? " + msg);
                if (code == 429) throw new IOException("Notion rate limit (429), will retry.");
                throw new IOException("Notion error " + code + ": " + msg);
            }
            return resp.isEmpty() ? new JSONObject() : new JSONObject(resp);
        } catch (JSONException e) {
            throw new IOException("Bad answer from Notion: " + e.getMessage());
        }
    }
}
