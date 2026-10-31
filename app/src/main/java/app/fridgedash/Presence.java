package app.fridgedash;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * "Someone is near" from the device's proximity / gesture sensor, if it has one
 * that apps can read (the Lenovo Smart Frame has one next to the screen).
 */
final class Presence implements SensorEventListener {

    interface Listener { void onSomeoneNear(); }

    private final SensorManager sm;
    private final Sensor sensor;
    private final Listener listener;
    private float lastValue = Float.NaN;

    Presence(Context c, Listener l) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        listener = l;
    }

    static String describe(Context c) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        return s == null ? "" : s.getName();
    }

    void start() {
        if (sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    void stop() {
        if (sensor != null) sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float v = e.values[0];
        boolean near = v < sensor.getMaximumRange() && v < 5f;
        // React to a change towards "near" (and to any change on sensors that only report distance).
        if (near && (Float.isNaN(lastValue) || v != lastValue)) listener.onSomeoneNear();
        lastValue = v;
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}
}
