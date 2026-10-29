package app.fridgedash;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings, stored privately on the fridge (never in the APK or on GitHub). */
final class Prefs {
    private Prefs() {}

    static final String DEFAULT_MODEL = "gpt-realtime-2.1";
    static final String DEFAULT_VOICE = "marin";
    static final String[] VOICES = {"marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse"};
    static final int[] UI_WIDTHS = {480, 540, 600, 660};

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("fridgedash", Context.MODE_PRIVATE);
    }

    static String apiKey(Context c) { return p(c).getString("openai_key", "").trim(); }
    static void setApiKey(Context c, String v) { p(c).edit().putString("openai_key", v == null ? "" : v.trim()).apply(); }

    static String model(Context c) {
        String m = p(c).getString("openai_model", DEFAULT_MODEL).trim();
        return m.isEmpty() ? DEFAULT_MODEL : m;
    }
    static void setModel(Context c, String v) { p(c).edit().putString("openai_model", v == null ? "" : v.trim()).apply(); }

    static String voice(Context c) { return p(c).getString("openai_voice", DEFAULT_VOICE); }
    static void setVoice(Context c, String v) { p(c).edit().putString("openai_voice", v).apply(); }

    /** Off (default): the mic pauses while ChatGPT talks, so it can't hear itself. */
    static boolean bargeIn(Context c) { return p(c).getBoolean("barge_in", false); }
    static void setBargeIn(Context c, boolean v) { p(c).edit().putBoolean("barge_in", v).apply(); }

    static boolean dailyWallpaper(Context c) { return p(c).getBoolean("daily_wallpaper", true); }
    static void setDailyWallpaper(Context c, boolean v) { p(c).edit().putBoolean("daily_wallpaper", v).apply(); }

    /** Width of the virtual layout canvas in dp; smaller = everything bigger. */
    static int uiWidthDp(Context c) { return p(c).getInt("ui_width_dp", 540); }
    static void setUiWidthDp(Context c, int v) { p(c).edit().putInt("ui_width_dp", v).apply(); }

    /** Microphone input that worked best in the test; -1 = not tested. */
    static int micSource(Context c) { return p(c).getInt("mic_source", -1); }
    static void setMicSource(Context c, int v) { p(c).edit().putInt("mic_source", v).apply(); }

    /** "auto" (keyboard unless the mic test passed), "mic" or "keyboard". */
    static String inputMode(Context c) { return p(c).getString("input_mode", "auto"); }
    static void setInputMode(Context c, String v) { p(c).edit().putString("input_mode", v).apply(); }
    static boolean useKeyboard(Context c) {
        String m = inputMode(c);
        if ("keyboard".equals(m)) return true;
        if ("mic".equals(m)) return false;
        if (BtAudio.headsetConnected(c)) return false;          // Bluetooth speakerphone present
        if (!micScan(c).isEmpty()) return false;               // deep scan found a working channel
        return micSource(c) < 0 || micViaBt(c);                 // built-in mic only if it tested OK
    }

    /** The last successful mic test used a Bluetooth device (so it says nothing about the fridge). */
    static boolean micViaBt(Context c) { return p(c).getBoolean("mic_via_bt", false); }
    static void setMicViaBt(Context c, boolean v) { p(c).edit().putBoolean("mic_via_bt", v).apply(); }

    /** Mic setup found by the deep scan ("source,rate,channels,channel"), or "" if none. */
    static String micScan(Context c) { return p(c).getString("mic_scan", ""); }
    static void setMicScan(Context c, String v) { p(c).edit().putString("mic_scan", v).apply(); }

    static SharedPreferences raw(Context c) { return p(c); }
}
