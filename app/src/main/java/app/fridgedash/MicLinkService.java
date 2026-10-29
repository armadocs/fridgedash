package app.fridgedash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The phone side of "Remote mic": keeps a connection to FridgeDash on the
 * fridge and, only while a ChatGPT call is open there, sends this phone's
 * microphone audio to it. Runs as a foreground service (with a notification)
 * so it keeps working while the phone shows other apps.
 */
public class MicLinkService extends Service {

    static final String ACTION_TALK = "app.fridgedash.TALK";
    static final String ACTION_END = "app.fridgedash.END";
    static final String ACTION_STOP = "app.fridgedash.STOP";
    static final String ACTION_RECONNECT = "app.fridgedash.RECONNECT";
    private static final String CHANNEL = "mic_link";
    private static final int NOTE_ID = 47;
    private static final int RATE = VoiceSession.RATE;

    // Status shown by MicLinkActivity (same process, polled).
    static volatile String status = "Off";
    static volatile String fridgeState = "";
    static volatile boolean connected;
    static volatile boolean sending;
    static volatile float level;
    static volatile boolean running;

    private volatile Socket sock;
    private OutputStream out;
    private Thread worker;
    private volatile Thread capture;
    private volatile boolean capturing;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;

    static void start(Context c) {
        Intent i = new Intent(c, MicLinkService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void send(Context c, String action) {
        Intent i = new Intent(c, MicLinkService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTE_ID, notification("Looking for the fridge…"));
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Prefs.setLinkEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            acquireLocks();
            worker = new Thread(this::loop, "mic-link");
            worker.start();
        }
        if (ACTION_RECONNECT.equals(action)) new Thread(this::closeSocket, "mic-link-reset").start();
        else if (ACTION_TALK.equals(action)) frame('T', null, 0);
        else if (ACTION_END.equals(action)) frame('E', null, 0);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopCapture();
        closeSocket();
        if (worker != null) worker.interrupt();
        releaseLocks();
        connected = false;
        status = "Off";
        fridgeState = "";
        super.onDestroy();
    }

    // ------------------------------------------------------------------ connection

    private void loop() {
        while (running) {
            String host = Prefs.linkHost(this);
            if (host.isEmpty()) {
                setStatus("Looking for the fridge on Wi-Fi…");
                host = discover(10_000);
            }
            if (host == null || host.isEmpty()) {
                setStatus("Can't find the fridge. Is FridgeDash open on it? You can also type its address below.");
                pause(5000);
                continue;
            }
            try {
                setStatus("Connecting to " + host + "…");
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, RemoteMic.PORT), 5000);
                s.setTcpNoDelay(true);
                s.setSoTimeout(15000); // the fridge pings every 5 s
                sock = s;
                synchronized (this) { out = s.getOutputStream(); }
                writeRaw(("FDMIC1 rate=" + RATE + " name=" + Build.MODEL + "\n").getBytes("UTF-8"));
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                String line;
                while (running && (line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("HELLO")) {
                        connected = true;
                        setStatus("Connected to the fridge (" + host + ")");
                    } else if (line.equals("START")) {
                        startCapture();
                    } else if (line.equals("STOP")) {
                        stopCapture();
                        fridgeState = "";
                        refreshNotification();
                    } else if (line.startsWith("STATE ")) {
                        fridgeState = line.substring(6);
                        refreshNotification();
                    } else if (line.equals("PING")) {
                        frame('P', null, 0);
                    }
                }
            } catch (IOException e) {
                // fall through to reconnect
            } finally {
                stopCapture();
                closeSocket();
                connected = false;
                fridgeState = "";
            }
            if (running) {
                setStatus("Lost the fridge. Reconnecting…");
                pause(3000);
            }
        }
    }

    /** Finds FridgeDash on the local network (it announces itself). */
    private String discover(long timeoutMs) {
        NsdManager nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return null;
        final String[] found = {null};
        final CountDownLatch done = new CountDownLatch(1);
        final String self = RemoteMic.serviceName();
        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            boolean resolving;
            @Override public void onStartDiscoveryFailed(String t, int e) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo s) {}
            @Override public synchronized void onServiceFound(NsdServiceInfo s) {
                if (resolving || s.getServiceName().startsWith(self)) return;
                resolving = true;
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) { resolving = false; }
                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        if (i.getHost() != null) found[0] = i.getHost().getHostAddress();
                        done.countDown();
                    }
                });
            }
        };
        try {
            nsd.discoverServices(RemoteMic.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dl);
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try { nsd.stopServiceDiscovery(dl); } catch (Exception ignored) {}
        }
        return found[0];
    }

    // ------------------------------------------------------------------ microphone

    private synchronized void startCapture() {
        if (capturing) return;
        capturing = true;
        capture = new Thread(this::captureLoop, "mic-link-capture");
        capture.start();
        refreshNotification();
    }

    private void stopCapture() {
        capturing = false;
        Thread t = capture;
        capture = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        sending = false;
        level = 0;
    }

    private void captureLoop() {
        AudioRecord rec = null;
        int rate = 0;
        // VOICE_COMMUNICATION turns on the phone's echo cancelling and noise suppression.
        int[] sources = {MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC};
        int[] rates = {RATE, 48000, 16000};
        outer:
        for (int src : sources) {
            for (int r : rates) {
                int min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                try {
                    AudioRecord a = new AudioRecord(src, r, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, r / 5 * 2));
                    if (a.getState() == AudioRecord.STATE_INITIALIZED) { rec = a; rate = r; break outer; }
                    a.release();
                } catch (Exception ignored) {
                }
            }
        }
        if (rec == null) {
            setStatus("Couldn't open this phone's microphone. Allow microphone access for FridgeDash.");
            capturing = false;
            return;
        }
        try {
            rec.startRecording();
            sending = true;
            short[] buf = new short[rate / 10]; // 100 ms
            while (capturing && running) {
                int filled = 0;
                while (filled < buf.length && capturing) {
                    int got = rec.read(buf, filled, buf.length - filled);
                    if (got <= 0) break;
                    filled += got;
                }
                if (filled <= 0) { pause(20); continue; }
                level = VoiceSession.rms(buf, filled);
                byte[] pcm = VoiceSession.toPcm24k(buf, filled, rate);
                frame('A', pcm, pcm.length);
            }
        } catch (Exception ignored) {
        } finally {
            sending = false;
            level = 0;
            try { rec.stop(); } catch (Exception ignored) {}
            rec.release();
        }
    }

    // ------------------------------------------------------------------ sending

    private void frame(char type, byte[] payload, int len) {
        byte[] f = new byte[3 + len];
        f[0] = (byte) type;
        f[1] = (byte) ((len >> 8) & 0xFF);
        f[2] = (byte) (len & 0xFF);
        if (len > 0) System.arraycopy(payload, 0, f, 3, len);
        if (type == 'A') {
            writeRaw(f);
        } else {
            // Control frames may come from the main thread: never block it on the network.
            new Thread(() -> writeRaw(f), "mic-link-send").start();
        }
    }

    private synchronized void writeRaw(byte[] b) {
        if (out == null) return;
        try {
            out.write(b);
            out.flush();
        } catch (IOException e) {
            closeSocket();
        }
    }

    private synchronized void closeSocket() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
        sock = null;
        out = null;
    }

    // ------------------------------------------------------------------ notification & locks

    private void setStatus(String s) {
        status = s;
        refreshNotification();
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || !running) return;
        String text = !connected ? status
                : capturing ? "Fridge is listening to this phone" + (fridgeState.isEmpty() ? "" : " · " + fridgeState)
                : "Ready — tap Talk to start ChatGPT on the fridge";
        nm.notify(NOTE_ID, notification(text));
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Fridge microphone",
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MicLinkActivity.class), flags);
        PendingIntent talk = PendingIntent.getService(this, 1,
                new Intent(this, MicLinkService.class).setAction(ACTION_TALK), flags);
        PendingIntent end = PendingIntent.getService(this, 2,
                new Intent(this, MicLinkService.class).setAction(ACTION_END), flags);
        b.setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Fridge microphone")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, capturing ? "End call" : "Talk",
                        capturing ? end : talk).build());
        return b.build();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fridgedash:miclink");
                wake.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fridgedash:miclink");
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
