package app.fridgedash;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Gestures & buttons": teach FridgeDash what each button or hand gesture should do.
 * Tap Record next to an action, then press the button or make the gesture.
 * When not recording, anything you do is shown at the top, so you can test.
 */
public class GestureSetupActivity extends BaseActivity implements SensorEventListener {

    private static final long RECORD_TIMEOUT_MS = 10_000;
    private static final long CAPTURE_MS = 1300;   // how long a gesture's readings are collected
    private static final long SETTLE_MS = 500;     // ignore sensor readings right after Record is tapped

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView banner;
    private SensorManager sm;
    private Bindings.Watcher watcher;

    // Recording state
    private String recording;                    // action being recorded, or null
    private long recordStart;
    private final Map<Sensor, String> baseline = new HashMap<>();
    private Sensor capSensor;
    private final List<String> capSeq = new ArrayList<>();
    private boolean listeningAll;

    private final Runnable timeout = () -> {
        if (recording != null) { stopRecording(); banner.setText("Nothing detected. Try again, a bit closer or slower."); }
    };
    private final Runnable finishCapture = this::finishSensorCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.bg));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(col);
        setContentView(scroll);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text("Gestures & buttons", 20, true), new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(button("Close", v -> finish(), false));
        col.addView(top);
        col.addView(note("Tap Record next to an action, then press the button or wave your hand in front of the "
                + "sensor the way you want to use it (one smooth movement, about 10 cm away). Each input can do one "
                + "thing. Afterwards, try your gestures: what FridgeDash recognises is shown in the box below."));

        banner = text("Try a button or gesture to test it.", 14, true);
        banner.setGravity(Gravity.CENTER);
        banner.setBackgroundResource(R.drawable.tile);
        int bp = Ui.dp(this, 14);
        banner.setPadding(bp, bp, bp, bp);
        banner.setMinHeight(Ui.dp(this, 64));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 10);
        blp.bottomMargin = Ui.dp(this, 6);
        col.addView(banner, blp);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        col.addView(note("Tip: if every direction records the same pattern, this sensor only knows \"hand near\", "
                + "not which way it moved. Then use a single wave for the action you use most (e.g. Next picture)."));
        render();
        watcher = new Bindings.Watcher(this, a -> {
            if (recording == null) banner.setText("✓ " + Bindings.label(a));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        watcher.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRecording();
        watcher.stop();
    }

    private void render() {
        list.removeAllViews();
        for (String[] a : Bindings.ACTIONS) {
            final String action = a[0];
            JSONObject b = Bindings.get(this, action);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(text(a[1], 14, true));
            TextView d = note(action.equals(recording) ? "● Waiting for a button or gesture…" : Bindings.describe(b));
            if (action.equals(recording)) d.setTextColor(0xFFF5B14C);
            texts.addView(d);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(button(action.equals(recording) ? "Cancel" : "Record", v -> {
                if (action.equals(recording)) { stopRecording(); banner.setText("Cancelled."); }
                else startRecording(action);
            }, !action.equals(recording)));
            if (b != null) {
                TextView clear = button("Clear", v -> { Bindings.clear(this, action); watcher.start(); render(); }, false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMarginStart(Ui.dp(this, 6));
                row.addView(clear, lp);
            }
            list.addView(row);
        }
    }

    // ------------------------------------------------------------------ recording

    private void startRecording(String action) {
        stopRecording();
        recording = action;
        recordStart = System.currentTimeMillis();
        baseline.clear();
        capSensor = null;
        capSeq.clear();
        watcher.stop(); // don't trigger actions while learning
        if (sm != null) {
            for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
                if (!Bindings.ignorable(s)) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
            }
            listeningAll = true;
        }
        banner.setText("Now: " + Bindings.label(action) + "\nPress the button or make the gesture…");
        main.postDelayed(timeout, RECORD_TIMEOUT_MS);
        render();
    }

    private void stopRecording() {
        main.removeCallbacks(timeout);
        main.removeCallbacks(finishCapture);
        if (listeningAll && sm != null) sm.unregisterListener(this);
        listeningAll = false;
        boolean was = recording != null;
        recording = null;
        capSensor = null;
        if (was) {
            watcher.start();
            render();
        }
    }

    private void save(JSONObject b, String what) {
        if (recording == null || b == null) return;
        String action = recording;
        Bindings.set(this, action, b);
        stopRecording();
        banner.setText("Saved: " + what + " → " + Bindings.label(action));
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (recording == null) return;
        String v = Bindings.valueKey(e);
        if (!baseline.containsKey(e.sensor) || System.currentTimeMillis() - recordStart < SETTLE_MS) {
            if (capSensor == null) baseline.put(e.sensor, v); // resting value
            return;
        }
        if (capSensor == null) {
            if (v.equals(baseline.get(e.sensor))) return;
            capSensor = e.sensor; // the first sensor that reacts is the gesture sensor
            capSeq.add(v);
            banner.setText("Detected on " + e.sensor.getName() + "… keep going");
            main.removeCallbacks(timeout);
            main.postDelayed(finishCapture, CAPTURE_MS);
            return;
        }
        if (e.sensor != capSensor) return;
        if (!capSeq.isEmpty() && capSeq.get(capSeq.size() - 1).equals(v)) return;
        if (capSeq.size() < 8) capSeq.add(v);
    }

    private void finishSensorCapture() {
        if (recording == null || capSensor == null || capSeq.isEmpty()) return;
        JSONObject b = Bindings.sensorBinding(capSensor, capSeq);
        String sig = Bindings.signature(b);
        String clash = null;
        for (String[] a : Bindings.ACTIONS) {
            if (!a[0].equals(recording) && sig.equals(Bindings.signature(Bindings.get(this, a[0])))) clash = a[1];
        }
        save(b, "gesture " + android.text.TextUtils.join(" → ", capSeq));
        if (clash != null) {
            banner.setText(banner.getText() + "\n(This looked exactly like \"" + clash + "\", which was cleared: the "
                    + "sensor may not tell directions apart.)");
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (recording != null) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) { stopRecording(); banner.setText("Cancelled."); }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                save(Bindings.keyBinding(e), "button " + KeyEvent.keyCodeToString(e.getKeyCode())
                        .replace("KEYCODE_", "").toLowerCase() + " (#" + e.getScanCode() + ")");
            }
            return true;
        }
        String a = Bindings.actionForKey(this, e);
        if (a != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) banner.setText("✓ " + Bindings.label(a));
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            banner.setText("Button " + KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "").toLowerCase()
                    + " (#" + e.getScanCode() + ") isn't assigned");
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        String dir = Bindings.motionDir(e);
        if (dir == null) return super.dispatchGenericMotionEvent(e);
        if (recording != null) {
            save(Bindings.motionBinding(e, dir), "movement " + dir);
            return true;
        }
        String a = Bindings.actionForMotion(this, e, dir);
        banner.setText(a != null ? "✓ " + Bindings.label(a) : "Movement " + dir + " isn't assigned");
        return true;
    }

    // ------------------------------------------------------------------ view helpers

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(R.color.text));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 11, false);
        t.setTextColor(getColor(R.color.text_dim));
        t.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        return t;
    }

    private TextView button(String s, View.OnClickListener l, boolean primary) {
        TextView b = text(s, 12, false);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(primary ? R.drawable.tile_primary : R.drawable.tile);
        int p = Ui.dp(this, 10);
        b.setPadding(Ui.dp(this, 16), p, Ui.dp(this, 16), p);
        b.setMinHeight(Ui.dp(this, 40));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }
}
