package app.fridgedash;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Buttons and hand gestures you taught FridgeDash (Settings → Gestures & buttons).
 * Three kinds of input can be learned:
 *  - key:    a button / key press (frame buttons, remote, keyboard)
 *  - sensor: a pattern of readings from a sensor (e.g. the frame's gesture sensor)
 *  - motion: a direction from a joystick-like input device
 */
final class Bindings {
    private Bindings() {}

    static final String NEXT = "next", PREV = "prev", ALBUM_NEXT = "album_next", ALBUM_PREV = "album_prev",
            DASHBOARD = "dashboard", ART = "art", CHATGPT = "chatgpt";

    static final String[][] ACTIONS = {
            {NEXT, "Next picture"},
            {PREV, "Previous picture"},
            {ALBUM_NEXT, "Next album"},
            {ALBUM_PREV, "Previous album"},
            {DASHBOARD, "Show the dashboard"},
            {ART, "Show art / photos"},
            {CHATGPT, "Ask ChatGPT"},
    };

    static String label(String action) {
        for (String[] a : ACTIONS) if (a[0].equals(action)) return a[1];
        return action;
    }

    // ------------------------------------------------------------------ storage

    private static JSONObject all(Context c) {
        try {
            return new JSONObject(Prefs.raw(c).getString("bindings", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONObject get(Context c, String action) { return all(c).optJSONObject(action); }

    static void set(Context c, String action, JSONObject b) {
        JSONObject a = all(c);
        try {
            // One input does one thing: remove the same input from other actions.
            String sig = signature(b);
            Iterator<String> it = a.keys();
            List<String> drop = new ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                if (!k.equals(action) && sig.equals(signature(a.optJSONObject(k)))) drop.add(k);
            }
            for (String k : drop) a.remove(k);
            a.put(action, b);
        } catch (Exception ignored) {
        }
        Prefs.raw(c).edit().putString("bindings", a.toString()).apply();
    }

    static void clear(Context c, String action) {
        JSONObject a = all(c);
        a.remove(action);
        Prefs.raw(c).edit().putString("bindings", a.toString()).apply();
    }

    /** Identical inputs have identical signatures. */
    static String signature(JSONObject b) {
        if (b == null) return "";
        switch (b.optString("type")) {
            case "key": return "key:" + b.optInt("code") + ":" + b.optInt("scan");
            case "sensor": return "sensor:" + b.optString("sensor") + ":" + b.optJSONArray("seq");
            case "motion": return "motion:" + b.optString("device") + ":" + b.optString("dir");
            default: return "";
        }
    }

    static String describe(JSONObject b) {
        if (b == null) return "not set";
        switch (b.optString("type")) {
            case "key": {
                int code = b.optInt("code");
                String name = code == KeyEvent.KEYCODE_UNKNOWN ? "button #" + b.optInt("scan")
                        : KeyEvent.keyCodeToString(code).replace("KEYCODE_", "").replace('_', ' ').toLowerCase();
                return "Button: " + name + (b.optString("device").isEmpty() ? "" : " (" + b.optString("device") + ")");
            }
            case "sensor": {
                JSONArray s = b.optJSONArray("seq");
                return "Gesture on " + b.optString("sensor") + ": " + (s == null ? "" : s.join(" → ").replace("\"", ""));
            }
            case "motion": return "Movement " + b.optString("dir") + " (" + b.optString("device") + ")";
            default: return "not set";
        }
    }

    // ------------------------------------------------------------------ building bindings

    static JSONObject keyBinding(KeyEvent e) {
        try {
            return new JSONObject().put("type", "key").put("code", e.getKeyCode()).put("scan", e.getScanCode())
                    .put("device", e.getDevice() == null ? "" : e.getDevice().getName());
        } catch (Exception x) {
            return null;
        }
    }

    static JSONObject sensorBinding(Sensor s, List<String> seq) {
        try {
            JSONArray a = new JSONArray();
            for (String v : seq) a.put(v);
            return new JSONObject().put("type", "sensor").put("sensor", s.getName())
                    .put("stype", s.getType()).put("seq", a);
        } catch (Exception x) {
            return null;
        }
    }

    static JSONObject motionBinding(MotionEvent e, String dir) {
        try {
            return new JSONObject().put("type", "motion").put("dir", dir)
                    .put("device", e.getDevice() == null ? "" : e.getDevice().getName());
        } catch (Exception x) {
            return null;
        }
    }

    // ------------------------------------------------------------------ matching

    static String actionForKey(Context c, KeyEvent e) {
        JSONObject a = all(c);
        Iterator<String> it = a.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject b = a.optJSONObject(k);
            if (b == null || !"key".equals(b.optString("type"))) continue;
            int code = b.optInt("code");
            if (code == e.getKeyCode() && (code != KeyEvent.KEYCODE_UNKNOWN || b.optInt("scan") == e.getScanCode())) return k;
        }
        return null;
    }

    static String actionForMotion(Context c, MotionEvent e, String dir) {
        String dev = e.getDevice() == null ? "" : e.getDevice().getName();
        JSONObject a = all(c);
        Iterator<String> it = a.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject b = a.optJSONObject(k);
            if (b != null && "motion".equals(b.optString("type")) && dir.equals(b.optString("dir"))
                    && dev.equals(b.optString("device"))) return k;
        }
        return null;
    }

    /** Is a learned gesture using this kind of sensor? (Then it shouldn't also wake the screen.) */
    static boolean usesSensorType(Context c, int type) {
        JSONObject a = all(c);
        Iterator<String> it = a.keys();
        while (it.hasNext()) {
            JSONObject b = a.optJSONObject(it.next());
            if (b != null && "sensor".equals(b.optString("type")) && b.optInt("stype") == type) return true;
        }
        return false;
    }

    /** Direction from a joystick / d-pad / gesture input device; null for mice, touch or tiny movements. */
    static String motionDir(MotionEvent e) {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE) || e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                || e.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return null;
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float x = Math.abs(hx) > 0.5f ? hx : e.getAxisValue(MotionEvent.AXIS_X);
        float y = Math.abs(hy) > 0.5f ? hy : e.getAxisValue(MotionEvent.AXIS_Y);
        if (Math.max(Math.abs(x), Math.abs(y)) < 0.5f) return null;
        if (Math.abs(x) >= Math.abs(y)) return x > 0 ? "right" : "left";
        return y > 0 ? "down" : "up";
    }

    /** A sensor reading reduced to a short, comparable label. */
    static String valueKey(SensorEvent e) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(3, e.values.length); i++) {
            if (i > 0) b.append('/');
            float v = Math.round(e.values[i] * 10) / 10f;
            b.append(v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v));
        }
        return b.toString();
    }

    /** Sensors that never carry a hand gesture (they change constantly). */
    static boolean ignorable(Sensor s) {
        switch (s.getType()) {
            case Sensor.TYPE_ACCELEROMETER: case Sensor.TYPE_GYROSCOPE: case Sensor.TYPE_MAGNETIC_FIELD:
            case Sensor.TYPE_ROTATION_VECTOR: case Sensor.TYPE_GAME_ROTATION_VECTOR:
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR: case Sensor.TYPE_GRAVITY:
            case Sensor.TYPE_LINEAR_ACCELERATION: case Sensor.TYPE_ACCELEROMETER_UNCALIBRATED:
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED: case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
            case Sensor.TYPE_ORIENTATION: case Sensor.TYPE_LIGHT: case Sensor.TYPE_PRESSURE:
            case Sensor.TYPE_AMBIENT_TEMPERATURE: case Sensor.TYPE_RELATIVE_HUMIDITY:
                return true;
            default:
                return false;
        }
    }

    /** Watches the sensors used by learned gestures and reports the matching action. */
    static final class Watcher implements SensorEventListener {
        interface Listener { void onAction(String action); }

        private final Context ctx;
        private final SensorManager sm;
        private final Listener listener;
        private final Map<Sensor, List<Object[]>> recent = new HashMap<>(); // {time, value}
        private final Map<Sensor, String> lastValue = new HashMap<>();
        private final List<Sensor> registered = new ArrayList<>();
        private long lastFire;

        Watcher(Context c, Listener l) {
            ctx = c;
            sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            listener = l;
        }

        void start() {
            stop();
            if (sm == null) return;
            JSONObject a = all(ctx);
            Iterator<String> it = a.keys();
            while (it.hasNext()) {
                JSONObject b = a.optJSONObject(it.next());
                if (b == null || !"sensor".equals(b.optString("type"))) continue;
                for (Sensor s : sm.getSensorList(b.optInt("stype"))) {
                    if (s.getName().equals(b.optString("sensor")) && !registered.contains(s)) {
                        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
                        registered.add(s);
                    }
                }
            }
        }

        void stop() {
            if (sm != null && !registered.isEmpty()) sm.unregisterListener(this);
            registered.clear();
            recent.clear();
            lastValue.clear();
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            String v = valueKey(e);
            if (v.equals(lastValue.get(e.sensor))) return;
            boolean first = !lastValue.containsKey(e.sensor);
            lastValue.put(e.sensor, v);
            if (first) return; // the initial reading is the resting state, not a gesture
            long now = System.currentTimeMillis();
            List<Object[]> buf = recent.get(e.sensor);
            if (buf == null) { buf = new ArrayList<>(); recent.put(e.sensor, buf); }
            buf.add(new Object[]{now, v});
            while (!buf.isEmpty() && now - (Long) buf.get(0)[0] > 2500) buf.remove(0);

            // The longest learned pattern that the latest readings end with wins.
            JSONObject a = all(ctx);
            Iterator<String> it = a.keys();
            String best = null;
            int bestLen = 0;
            while (it.hasNext()) {
                String action = it.next();
                JSONObject b = a.optJSONObject(action);
                if (b == null || !"sensor".equals(b.optString("type"))
                        || !e.sensor.getName().equals(b.optString("sensor"))) continue;
                JSONArray seq = b.optJSONArray("seq");
                if (seq == null || seq.length() == 0 || seq.length() > buf.size() || seq.length() <= bestLen) continue;
                boolean match = true;
                for (int i = 0; i < seq.length(); i++) {
                    if (!seq.optString(i).equals(buf.get(buf.size() - seq.length() + i)[1])) { match = false; break; }
                }
                if (match) { best = action; bestLen = seq.length(); }
            }
            if (best != null && now - lastFire > 700) {
                lastFire = now;
                buf.clear();
                listener.onAction(best);
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int a) {}
    }
}
