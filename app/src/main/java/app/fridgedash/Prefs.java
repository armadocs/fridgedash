package app.fridgedash;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings, stored privately on the fridge (never in the APK or on GitHub). */
final class Prefs {
    private Prefs() {}

    static final String DEFAULT_MODEL = "gpt-realtime-2.1";
    static final String DEFAULT_VOICE = "marin";
    static final String[] VOICES = {"marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse"};
    static final String[] VOICE_INFO = {
            "female, warm and natural (most lifelike)", "male, calm and natural (most lifelike)",
            "neutral, clear", "male, soft", "male, expressive", "female, bright and friendly",
            "male, steady", "female, calm", "female, light", "male, lively"};

    /** Realtime voice models offered in Settings (any other name can be typed in "Model (advanced)"). */
    static final String[] MODELS = {"gpt-realtime-2.1", "gpt-realtime-2", "gpt-realtime-mini"};
    static final String[] MODEL_INFO = {"newest, best quality (default)", "previous version", "cheaper and faster, simpler answers"};

    static final float[] SPEEDS = {0.85f, 1.0f, 1.1f, 1.2f};
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
        if (RemoteMic.get(c).connected()) return false;          // a phone is the microphone
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

    /** Software microphone boost (the fridge's mic array is very quiet). */
    static final int[] MIC_GAINS = {1, 4, 8, 12, 16, 24};
    static int micGain(Context c) { return p(c).getInt("mic_gain", 12); }
    static void setMicGain(Context c, int v) { p(c).edit().putInt("mic_gain", v).apply(); }

    /** Where the daily brief JSON lives, and the read-only key for it (GitHub fine-grained token). */
    static String briefUrl(Context c) { return p(c).getString("brief_url", "").trim(); }
    static void setBriefUrl(Context c, String v) { p(c).edit().putString("brief_url", v == null ? "" : v.trim()).apply(); }
    static String briefToken(Context c) { return p(c).getString("brief_token", "").trim(); }
    static void setBriefToken(Context c, String v) { p(c).edit().putString("brief_token", v == null ? "" : v.trim()).apply(); }

    /** Notion internal-integration key and the "Daily Brief" database id. */
    static String notionToken(Context c) { return p(c).getString("notion_token", "").trim(); }
    static void setNotionToken(Context c, String v) { p(c).edit().putString("notion_token", v == null ? "" : v.trim()).apply(); }
    static String notionDb(Context c) { return p(c).getString("notion_db", "").trim(); }
    static void setNotionDb(Context c, String v) { p(c).edit().putString("notion_db", v == null ? "" : v.trim()).apply(); }

    /** Fridge: accept a phone / mic module on the network as the microphone. */
    static boolean remoteMicServer(Context c) { return p(c).getBoolean("remote_mic_server", true); }
    static void setRemoteMicServer(Context c, boolean v) { p(c).edit().putBoolean("remote_mic_server", v).apply(); }

    /** Phone: act as the fridge's microphone ("Fridge Mic"), and the fridge's address if auto-find fails. */
    static boolean linkEnabled(Context c) { return p(c).getBoolean("link_enabled", false); }
    static void setLinkEnabled(Context c, boolean v) { p(c).edit().putBoolean("link_enabled", v).apply(); }
    static String linkHost(Context c) { return p(c).getString("link_host", "").trim(); }
    static void setLinkHost(Context c, String v) { p(c).edit().putString("link_host", v == null ? "" : v.trim()).apply(); }

    /** Home city for weather and local answers; empty = worked out from the internet address. */
    static String homeCity(Context c) { return p(c).getString("home_city", "").trim(); }
    static void setHomeCity(Context c, String v) { p(c).edit().putString("home_city", v == null ? "" : v.trim()).apply(); }

    /** "auto" (answer in the language you speak), "sr" (always Serbian) or "en". */
    static String language(Context c) { return p(c).getString("voice_language", "auto"); }
    static void setLanguage(Context c, String v) { p(c).edit().putString("voice_language", v).apply(); }

    static float speed(Context c) { return p(c).getFloat("voice_speed", 1.0f); }
    static void setSpeed(Context c, float v) { p(c).edit().putFloat("voice_speed", v).apply(); }

    /** Your own extra instructions for ChatGPT (personality, style, facts about the household). */
    static String extraInstructions(Context c) { return p(c).getString("extra_instructions", "").trim(); }
    static void setExtraInstructions(Context c, String v) { p(c).edit().putString("extra_instructions", v == null ? "" : v.trim()).apply(); }

    /** Art mode: minutes of no activity before paintings appear (0 = off), and how often they change. */
    static final int[] ART_IDLE = {0, 1, 2, 5, 10, 30};
    static final int[] ART_INTERVAL = {1, 5, 15, 60};
    static int artIdleMin(Context c) { return p(c).getInt("art_idle_min", 0); }
    static void setArtIdleMin(Context c, int v) { p(c).edit().putInt("art_idle_min", v).apply(); }
    static int artIntervalMin(Context c) { return p(c).getInt("art_interval_min", 5); }
    static void setArtIntervalMin(Context c, int v) { p(c).edit().putInt("art_interval_min", v).apply(); }

    /** "auto" (follow the device), "portrait" or "landscape": for frames that don't rotate on their own. */
    static String orientation(Context c) { return p(c).getString("orientation", "auto"); }
    static void setOrientation(Context c, String v) { p(c).edit().putString("orientation", v).apply(); }

    static SharedPreferences raw(Context c) { return p(c); }
}
