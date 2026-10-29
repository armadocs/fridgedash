package app.fridgedash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Routes a conversation through a Bluetooth headset / speakerphone ("phone
 * call" audio, HFP/SCO): its microphone in, its speaker out. Works only if the
 * fridge's Bluetooth supports the hands-free profile, which the pairing screen
 * shows as "Phone audio" / "Call audio".
 */
final class BtAudio {

    private final Context ctx;
    private final AudioManager am;
    private BroadcastReceiver receiver;
    private int previousMode = AudioManager.MODE_NORMAL;
    private boolean started;

    BtAudio(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    /** A Bluetooth device with call audio (microphone) is connected. */
    static boolean headsetConnected(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled()
                    && a.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Name of the connected Bluetooth call-audio device, or "" if none / unknown. */
    static String headsetName(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        CharSequence n = d.getProductName();
                        return n == null ? "" : n.toString();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Opens the Bluetooth call-audio link; blocks up to {@code timeoutMs}. Call off the UI thread. */
    boolean start(long timeoutMs) {
        if (am == null) return false;
        final CountDownLatch connected = new CountDownLatch(1);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (st == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown();
            }
        };
        ctx.registerReceiver(receiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        previousMode = am.getMode();
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        am.startBluetoothSco();
        am.setBluetoothScoOn(true);
        started = true;
        try {
            return connected.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    void stop() {
        if (receiver != null) {
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
        if (!started || am == null) return;
        started = false;
        try {
            am.setBluetoothScoOn(false);
            am.stopBluetoothSco();
            am.setMode(previousMode);
        } catch (Exception ignored) {
        }
    }
}
