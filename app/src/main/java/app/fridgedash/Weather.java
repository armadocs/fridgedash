package app.fridgedash;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Weather for the home screen chip and the forecast screen (Open-Meteo, free, no key). */
final class Weather {

    static final long MAX_AGE_MS = 30 * 60 * 1000;

    static final class Hour {
        String time; int temp, code, rain; boolean day;
    }

    static final class Day {
        Date date; int code, min, max, rain; String sunrise = "", sunset = "";
    }

    String place = "";
    long fetched;
    int temp, feels, code, wind, humidity, todayMin, todayMax, todayRain;
    boolean isDay = true;
    final List<Hour> hours = new ArrayList<>();
    final List<Day> days = new ArrayList<>();

    // ------------------------------------------------------------------ loading

    private static File cacheFile(Context c) { return new File(c.getFilesDir(), "weather.json"); }

    /** Last saved forecast (may be old), or null. */
    static Weather cached(Context c) {
        try {
            File f = cacheFile(c);
            if (!f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            }
            JSONObject j = new JSONObject(new String(b, StandardCharsets.UTF_8));
            if (!j.optString("for").equals(Prefs.homeCity(c))) return null; // city changed in Settings
            return parse(j.getJSONObject("data"), j.optString("place"), j.optLong("fetched"));
        } catch (Exception e) {
            return null;
        }
    }

    boolean stale() { return System.currentTimeMillis() - fetched > MAX_AGE_MS; }

    /** Downloads a fresh forecast for the home location and saves it. Network: call off the UI thread. */
    static Weather fetch(Context c) throws Exception {
        Object[] where = Tools.home(c);
        if (where == null) throw new java.io.IOException("location unknown");
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + where[0] + "&longitude=" + where[1]
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m"
                + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                + "&timezone=auto&forecast_days=7";
        JSONObject data = new JSONObject(Tools.get(url));
        long now = System.currentTimeMillis();
        String place = String.valueOf(where[2]);
        Weather w = parse(data, place, now);
        JSONObject save = new JSONObject().put("for", Prefs.homeCity(c)).put("place", place)
                .put("fetched", now).put("data", data);
        try (FileOutputStream out = new FileOutputStream(cacheFile(c))) {
            out.write(save.toString().getBytes(StandardCharsets.UTF_8));
        }
        return w;
    }

    private static Weather parse(JSONObject j, String place, long fetched) throws Exception {
        Weather w = new Weather();
        w.place = place;
        w.fetched = fetched;
        JSONObject cur = j.getJSONObject("current");
        w.temp = (int) Math.round(cur.optDouble("temperature_2m"));
        w.feels = (int) Math.round(cur.optDouble("apparent_temperature"));
        w.code = cur.optInt("weather_code");
        w.isDay = cur.optInt("is_day", 1) == 1;
        w.wind = (int) Math.round(cur.optDouble("wind_speed_10m"));
        w.humidity = cur.optInt("relative_humidity_2m");

        // Hours from "now" (the forecast may be up to 30 min old; skip hours already past).
        JSONObject h = j.getJSONObject("hourly");
        JSONArray ht = h.getJSONArray("time");
        String nowHour = new SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).format(new Date());
        int start = 0;
        while (start < ht.length() && ht.getString(start).compareTo(nowHour) < 0) start++;
        for (int i = start; i < ht.length() && w.hours.size() < 24; i++) {
            Hour x = new Hour();
            x.time = ht.getString(i).substring(11, 16);
            x.temp = (int) Math.round(h.getJSONArray("temperature_2m").optDouble(i));
            x.code = h.getJSONArray("weather_code").optInt(i);
            x.rain = h.getJSONArray("precipitation_probability").optInt(i);
            x.day = h.getJSONArray("is_day").optInt(i, 1) == 1;
            w.hours.add(x);
        }

        JSONObject d = j.getJSONObject("daily");
        JSONArray dt = d.getJSONArray("time");
        SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (int i = 0; i < dt.length(); i++) {
            Day x = new Day();
            x.date = ymd.parse(dt.getString(i));
            x.code = d.getJSONArray("weather_code").optInt(i);
            x.min = (int) Math.round(d.getJSONArray("temperature_2m_min").optDouble(i));
            x.max = (int) Math.round(d.getJSONArray("temperature_2m_max").optDouble(i));
            x.rain = d.getJSONArray("precipitation_probability_max").optInt(i);
            String sr = d.getJSONArray("sunrise").optString(i, ""), ss = d.getJSONArray("sunset").optString(i, "");
            x.sunrise = sr.length() >= 16 ? sr.substring(11, 16) : "";
            x.sunset = ss.length() >= 16 ? ss.substring(11, 16) : "";
            w.days.add(x);
        }
        if (!w.days.isEmpty()) {
            w.todayMin = w.days.get(0).min;
            w.todayMax = w.days.get(0).max;
            w.todayRain = w.days.get(0).rain;
        }
        return w;
    }

    // ------------------------------------------------------------------ display helpers

    /** City without the country, for the small chip. */
    String shortPlace() {
        int comma = place.indexOf(',');
        return comma > 0 ? place.substring(0, comma) : place;
    }

    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code == 1) return day ? "🌤️" : "🌙";
        if (code == 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code <= 48) return "🌫️";
        if (code <= 57) return "🌦️";
        if (code <= 67) return "🌧️";
        if (code <= 77) return "🌨️";
        if (code <= 82) return "🌦️";
        if (code <= 86) return "🌨️";
        return "⛈️";
    }

    static String label(int code) {
        String s = Tools.describe(code);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
