package app.fridgedash;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ComponentInfo;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Finds out how this device reports hand gestures: lists its sensors and the
 * Lenovo/gesture apps, then logs every sensor change and key press while you wave.
 */
public class GestureTestActivity extends BaseActivity implements SensorEventListener {

    private SensorManager sm;
    private TextView log;
    private final StringBuilder lines = new StringBuilder();
    private final Map<Sensor, String> last = new HashMap<>();
    private float lastLux = Float.NaN;
    private final SimpleDateFormat t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // Motion sensors fire constantly and say nothing about a hand wave.
    private static final int[] SKIP = {Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
            Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_ORIENTATION};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.bg));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("Gesture test");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(getColor(R.color.text));
        col.addView(title);
        TextView help = new TextView(this);
        help.setText("Wave your hand in front of the sensor: left→right, right→left, top→bottom, bottom→top, "
                + "slowly, a few times each, ~10 cm away. Then take a photo of this screen (or several). "
                + "Back button / Esc closes.");
        help.setTextColor(getColor(R.color.text_dim));
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        help.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        col.addView(help);

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        log.setTextColor(getColor(R.color.text));
        log.setTextIsSelectable(true);
        col.addView(log);

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        StringBuilder head = new StringBuilder("SENSORS\n");
        if (sm != null) for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
            head.append("• ").append(s.getName()).append(" | ").append(s.getVendor())
                    .append(" | type ").append(s.getType()).append(" ").append(s.getStringType())
                    .append(" | max ").append(s.getMaximumRange()).append('\n');
        }
        head.append("\nAPPS (lenovo / gesture / frame)\n");
        try {
            PackageManager pm = getPackageManager();
            for (PackageInfo pi : pm.getInstalledPackages(PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS)) {
                String n = pi.packageName.toLowerCase(Locale.ROOT);
                if (!(n.contains("lenovo") || n.contains("gesture") || n.contains("frame") || n.contains("motion")
                        || n.contains("sensor") || n.contains("ambient"))) continue;
                head.append("• ").append(pi.packageName).append('\n');
                appendComponents(head, "svc", pi.services);
                appendComponents(head, "rcv", pi.receivers);
            }
        } catch (Exception e) {
            head.append("(couldn't list: ").append(e.getMessage()).append(")\n");
        }
        head.append("\nEVENTS (newest first)\n");
        lines.append(head);
        log.setText(lines);
    }

    private static void appendComponents(StringBuilder b, String kind, ComponentInfo[] items) {
        if (items == null) return;
        for (ComponentInfo ci : items) {
            String n = ci.name.toLowerCase(Locale.ROOT);
            if (n.contains("gesture") || n.contains("sensor") || n.contains("swipe") || n.contains("motion")
                    || n.contains("proxim") || n.contains("hand")) {
                b.append("    ").append(kind).append(' ').append(ci.name).append('\n');
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sm == null) return;
        outer:
        for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
            for (int k : SKIP) if (s.getType() == k) continue outer;
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sm != null) sm.unregisterListener(this);
    }

    private void add(String s) {
        String line = t.format(new Date()) + "  " + s + "\n";
        int at = lines.indexOf("EVENTS (newest first)\n");
        if (at < 0) return;
        at += "EVENTS (newest first)\n".length();
        lines.insert(at, line);
        if (lines.length() > 60000) lines.setLength(60000);
        log.setText(lines);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        StringBuilder v = new StringBuilder();
        for (int i = 0; i < Math.min(6, e.values.length); i++) {
            if (i > 0) v.append(", ");
            v.append(Math.round(e.values[i] * 100) / 100f);
        }
        String vs = v.toString();
        if (vs.equals(last.get(e.sensor))) return; // only changes
        if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
            // Light flickers constantly; only log big jumps (a hand shading the sensor).
            float now = e.values[0];
            if (!Float.isNaN(lastLux) && Math.abs(now - lastLux) < Math.max(5f, lastLux * 0.35f)) return;
            lastLux = now;
        }
        last.put(e.sensor, vs);
        add("SENSOR " + e.sensor.getName() + " (type " + e.sensor.getType() + "): " + vs);
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            return super.dispatchKeyEvent(e);
        }
        add("KEY " + (e.getAction() == KeyEvent.ACTION_DOWN ? "down " : "up ")
                + KeyEvent.keyCodeToString(e.getKeyCode()) + " scan=" + e.getScanCode()
                + " dev=" + (e.getDevice() == null ? "?" : e.getDevice().getName()));
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (e.getDevice() != null && e.getDevice().getName().toLowerCase(Locale.ROOT).contains("mouse")) return true;
        add("MOTION " + MotionEvent.actionToString(e.getAction()) + " src=" + e.getSource()
                + " dev=" + (e.getDevice() == null ? "?" : e.getDevice().getName())
                + " x=" + e.getX() + " y=" + e.getY());
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_UP) {
            add("TOUCH " + MotionEvent.actionToString(e.getAction()) + " dev="
                    + (e.getDevice() == null ? "?" : e.getDevice().getName()));
        }
        return super.dispatchTouchEvent(e);
    }
}
