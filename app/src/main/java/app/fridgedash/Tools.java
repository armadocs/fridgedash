package app.fridgedash;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Live information for voice ChatGPT, which on its own has no internet:
 *  - get_weather: Open-Meteo (free, no key)
 *  - web_search: OpenAI's web search (Responses API) with the same API key
 */
final class Tools {
    private Tools() {}

    static final String SEARCH_MODEL = "gpt-5-mini";

    private static OkHttpClient http;

    private static synchronized OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(45, TimeUnit.SECONDS)
                    .build();
        }
        return http;
    }

    /** Tool definitions for session.update. */
    static JSONArray definitions() throws org.json.JSONException {
        JSONArray a = new JSONArray();
        a.put(new JSONObject()
                .put("type", "function")
                .put("name", "get_weather")
                .put("description", "Current weather and the forecast for the next days. "
                        + "Leave location empty for the user's home.")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("location", new JSONObject().put("type", "string")
                                        .put("description", "City name, e.g. 'Novi Sad' or 'Paris'. Empty = home.")))));
        a.put(new JSONObject()
                .put("type", "function")
                .put("name", "web_search")
                .put("description", "Search the internet for anything current or factual you don't reliably know: "
                        + "news, sports results, prices, opening hours, events, recent facts.")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("query", new JSONObject().put("type", "string")
                                        .put("description", "What to look up, as a full question.")))
                        .put("required", new JSONArray().put("query"))));
        return a;
    }

    static boolean handles(String name) {
        return "get_weather".equals(name) || "web_search".equals(name);
    }

    /** Runs a tool; always returns text for the model (an error message if it failed). */
    static String run(Context c, String name, String argsJson) {
        try {
            JSONObject args = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
            if ("get_weather".equals(name)) return weather(c, args.optString("location", "").trim());
            if ("web_search".equals(name)) return search(c, args.optString("query", "").trim());
            return "Unknown tool.";
        } catch (Exception e) {
            return "The lookup failed (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "). Tell the user briefly.";
        }
    }

    // ------------------------------------------------------------------ home location

    /** "City, Country" for instructions; looks it up once from the internet address if not set. */
    static String homeName(Context c) {
        String set = Prefs.homeCity(c);
        if (!set.isEmpty()) return set;
        return Prefs.raw(c).getString("home_auto_name", "");
    }

    /** {lat, lon, name} for the home location, or null. */
    static Object[] home(Context c) throws Exception {
        String set = Prefs.homeCity(c);
        if (!set.isEmpty()) {
            Object[] g = geocode(set);
            if (g != null) return g;
        }
        android.content.SharedPreferences p = Prefs.raw(c);
        if (p.contains("home_auto_lat")) {
            return new Object[]{(double) p.getFloat("home_auto_lat", 0), (double) p.getFloat("home_auto_lon", 0),
                    p.getString("home_auto_name", "")};
        }
        JSONObject j = new JSONObject(get("https://ipapi.co/json/"));
        if (!j.has("latitude")) return null;
        String nm = j.optString("city") + ", " + j.optString("country_name");
        p.edit().putFloat("home_auto_lat", (float) j.getDouble("latitude"))
                .putFloat("home_auto_lon", (float) j.getDouble("longitude"))
                .putString("home_auto_name", nm).apply();
        return new Object[]{j.getDouble("latitude"), j.getDouble("longitude"), nm};
    }

    /** Looks up the home location in the background so the first question already knows the city. */
    static void warmUp(Context c) {
        if (!homeName(c).isEmpty()) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> { try { home(app); } catch (Exception ignored) {} }, "home-location").start();
    }

    static Object[] geocode(String place) throws Exception {
        JSONObject j = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                + URLEncoder.encode(place.split(",")[0].trim(), "UTF-8")));
        JSONArray r = j.optJSONArray("results");
        if (r == null || r.length() == 0) return null;
        JSONObject f = r.getJSONObject(0);
        return new Object[]{f.getDouble("latitude"), f.getDouble("longitude"),
                f.optString("name") + ", " + f.optString("country")};
    }

    // ------------------------------------------------------------------ weather

    private static String weather(Context c, String location) throws Exception {
        Object[] where = location.isEmpty() ? home(c) : geocode(location);
        if (where == null) return "Couldn't find the place \"" + location + "\".";
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + where[0] + "&longitude=" + where[1]
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum"
                + "&timezone=auto&forecast_days=4";
        JSONObject j = new JSONObject(get(url));
        JSONObject cur = j.getJSONObject("current");
        StringBuilder b = new StringBuilder();
        b.append("Weather for ").append(where[2]).append(" (°C, km/h).\nNow: ")
                .append(describe(cur.optInt("weather_code"))).append(", ")
                .append(Math.round(cur.optDouble("temperature_2m"))).append("°C, feels like ")
                .append(Math.round(cur.optDouble("apparent_temperature"))).append("°C, wind ")
                .append(Math.round(cur.optDouble("wind_speed_10m"))).append(" km/h, humidity ")
                .append(cur.optInt("relative_humidity_2m")).append("%.\n");
        JSONObject d = j.getJSONObject("daily");
        JSONArray days = d.getJSONArray("time");
        for (int i = 0; i < days.length(); i++) {
            b.append(i == 0 ? "Today" : i == 1 ? "Tomorrow" : days.getString(i)).append(": ")
                    .append(describe(d.getJSONArray("weather_code").optInt(i))).append(", ")
                    .append(Math.round(d.getJSONArray("temperature_2m_min").optDouble(i))).append(" to ")
                    .append(Math.round(d.getJSONArray("temperature_2m_max").optDouble(i))).append("°C, rain chance ")
                    .append(d.getJSONArray("precipitation_probability_max").optInt(i)).append("%.\n");
        }
        return b.toString();
    }

    static String describe(int code) {
        if (code == 0) return "clear";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "overcast";
        if (code <= 48) return "fog";
        if (code <= 57) return "drizzle";
        if (code <= 67) return "rain";
        if (code <= 77) return "snow";
        if (code <= 82) return "rain showers";
        if (code <= 86) return "snow showers";
        return "thunderstorms";
    }

    // ------------------------------------------------------------------ web search

    private static String search(Context c, String query) throws Exception {
        if (query.isEmpty()) return "No question given.";
        String place = homeName(c);
        String err = null;
        for (String tool : new String[]{"web_search", "web_search_preview"}) {
            JSONObject body = new JSONObject()
                    .put("model", SEARCH_MODEL)
                    .put("tools", new JSONArray().put(new JSONObject().put("type", tool)))
                    .put("instructions", "Answer in at most 4 short sentences, plain text without links or "
                            + "markdown; it will be read aloud. Include concrete numbers, dates and names."
                            + (place.isEmpty() ? "" : " The user is in " + place + "."))
                    .put("input", query);
            Request req = new Request.Builder()
                    .url("https://api.openai.com/v1/responses")
                    .header("Authorization", "Bearer " + Prefs.apiKey(c))
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                    .build();
            try (Response r = http().newCall(req).execute()) {
                String txt = r.body() == null ? "" : r.body().string();
                if (!r.isSuccessful()) {
                    err = "HTTP " + r.code();
                    if (r.code() == 400) continue; // older accounts: try the preview tool name
                    break;
                }
                return outputText(new JSONObject(txt));
            }
        }
        return "The web search failed (" + err + "). Tell the user you couldn't look it up right now.";
    }

    private static String outputText(JSONObject resp) throws org.json.JSONException {
        StringBuilder b = new StringBuilder();
        JSONArray out = resp.optJSONArray("output");
        if (out != null) for (int i = 0; i < out.length(); i++) {
            JSONObject item = out.getJSONObject(i);
            if (!"message".equals(item.optString("type"))) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int k = 0; k < content.length(); k++) {
                JSONObject part = content.getJSONObject(k);
                if ("output_text".equals(part.optString("type"))) b.append(part.optString("text"));
            }
        }
        return b.length() == 0 ? "No result found." : b.toString();
    }

    static String get(String url) throws Exception {
        try (Response r = http().newCall(new Request.Builder().url(url).build()).execute()) {
            if (!r.isSuccessful()) throw new java.io.IOException("HTTP " + r.code());
            return r.body() == null ? "" : r.body().string();
        }
    }
}
