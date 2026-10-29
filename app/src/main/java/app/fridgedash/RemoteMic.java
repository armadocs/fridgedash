package app.fridgedash;

import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The fridge side of "Remote mic": another device on the same Wi-Fi (the
 * FridgeDash app on a phone, or later a small ESP32/ESP8266 microphone) sends
 * its microphone audio here, and ChatGPT on the fridge listens to that instead
 * of the fridge's own (non-working) microphone.
 *
 * Protocol (TCP port 47800, see docs/remote-mic.md):
 *   device → fridge: one text line "FDMIC1 rate=24000 name=Pixel 4\n",
 *                    then frames [type:1][length:2, big-endian][payload]
 *                    'A' = PCM16 little-endian mono audio, 'T' = start a call,
 *                    'E' = end the call, 'P' = keep-alive.
 *   fridge → device: text lines "HELLO FridgeDash", "START", "STOP",
 *                    "STATE listening|hearing|thinking|speaking|ended", "PING".
 * Audio is only accepted between START and STOP, i.e. while a call is open.
 */
final class RemoteMic {

    static final int PORT = 47800;
    static final String SERVICE_TYPE = "_fridgedash-mic._tcp.";
    private static final int MAX_QUEUE = 30; // ~3 s of audio; older audio is dropped

    private static RemoteMic instance;

    static synchronized RemoteMic get(Context c) {
        if (instance == null) instance = new RemoteMic(c.getApplicationContext());
        return instance;
    }

    /** Name this device announces itself under (the phone skips its own). */
    static String serviceName() { return "FridgeDash " + Build.MODEL; }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<byte[]> audio = new LinkedBlockingQueue<>();

    private volatile boolean serving;
    private ServerSocket server;
    private volatile Client client;
    private volatile boolean capturing;
    private NsdManager nsd;
    private NsdManager.RegistrationListener reg;

    private RemoteMic(Context app) { this.app = app; }

    // ------------------------------------------------------------------ server

    /** Starts listening for a phone/mic module (no-op if already running or turned off). */
    synchronized void startServer() {
        if (serving || !Prefs.remoteMicServer(app) || Prefs.linkEnabled(app)) return;
        serving = true;
        Thread t = new Thread(this::acceptLoop, "remote-mic-server");
        t.setDaemon(true);
        t.start();
        Thread ping = new Thread(this::pingLoop, "remote-mic-ping");
        ping.setDaemon(true);
        ping.start();
    }

    synchronized void stopServer() {
        serving = false;
        unregister();
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        server = null;
        Client c = client;
        if (c != null) c.close();
        client = null;
    }

    boolean isServing() { return serving; }

    private void acceptLoop() {
        while (serving) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                synchronized (this) { server = s; }
                register();
                while (serving) {
                    Socket sock = s.accept();
                    Client old = client;
                    Client c = new Client(sock);
                    client = c;
                    if (old != null) old.close(); // newest device wins
                    Thread t = new Thread(c, "remote-mic-client");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                if (!serving) return;
                sleep(3000); // port busy or network change: try again
            } finally {
                unregister();
            }
        }
    }

    private void pingLoop() {
        while (serving) {
            sleep(5000);
            Client c = client;
            if (c != null && c.alive) c.line("PING");
        }
    }

    private void register() {
        try {
            nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(serviceName());
            info.setServiceType(SERVICE_TYPE);
            info.setPort(PORT);
            reg = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo s, int e) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
                @Override public void onServiceRegistered(NsdServiceInfo s) {}
                @Override public void onServiceUnregistered(NsdServiceInfo s) {}
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (Exception ignored) {
            reg = null; // the phone can still connect by IP address
        }
    }

    private synchronized void unregister() {
        try { if (nsd != null && reg != null) nsd.unregisterService(reg); } catch (Exception ignored) {}
        reg = null;
    }

    // ------------------------------------------------------------------ used by VoiceSession

    boolean connected() {
        Client c = client;
        return c != null && c.alive && c.helloDone;
    }

    String clientName() {
        Client c = client;
        return c == null ? "" : c.name;
    }

    String clientAddress() {
        Client c = client;
        return c == null ? "" : c.address;
    }

    /** Asks the device to start sending audio. */
    void begin() {
        audio.clear();
        capturing = true;
        Client c = client;
        if (c != null) c.line("START");
    }

    void end() {
        capturing = false;
        audio.clear();
        Client c = client;
        if (c != null) c.line("STOP");
    }

    /** Tells the device what ChatGPT is doing, so the phone can show it. */
    void state(String s) {
        Client c = client;
        if (c != null) c.line("STATE " + s);
    }

    /** Next ~100 ms of 24 kHz PCM16 audio, or null if none arrived in time. */
    byte[] poll(long ms) {
        try {
            return audio.poll(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ------------------------------------------------------------------ one connected device

    private final class Client implements Runnable {
        final Socket sock;
        final String address;
        volatile boolean alive = true;
        volatile boolean helloDone;
        volatile String name = "device";
        int rate = VoiceSession.RATE;
        private OutputStream out;

        Client(Socket s) {
            sock = s;
            InetAddress a = s.getInetAddress();
            address = a == null ? "" : a.getHostAddress();
        }

        @Override
        public void run() {
            try {
                sock.setTcpNoDelay(true);
                sock.setSoTimeout(15000); // the device sends a keep-alive every 5 s
                out = sock.getOutputStream();
                DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
                String hello = readLine(in);
                if (hello == null || !hello.startsWith("FDMIC1")) { close(); return; }
                parseHello(hello);
                helloDone = true;
                line("HELLO FridgeDash");
                if (capturing) line("START"); // a call is already open
                while (alive) {
                    int type = in.readUnsignedByte();
                    int len = in.readUnsignedShort();
                    byte[] payload = new byte[len];
                    in.readFully(payload);
                    if (type == 'A') onAudio(payload);
                    else if (type == 'T') main.post(RemoteMic.this::openCall);
                    else if (type == 'E') main.post(VoiceActivity::finishCurrent);
                    // 'P' and anything unknown: just a keep-alive
                }
            } catch (IOException ignored) {
                // Disconnected or timed out.
            } finally {
                close();
            }
        }

        private void parseHello(String hello) {
            for (String part : hello.substring(6).trim().split(" (?=\\w+=)")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String k = part.substring(0, eq), v = part.substring(eq + 1).trim();
                if ("rate".equals(k)) {
                    try { rate = Math.max(8000, Math.min(48000, Integer.parseInt(v))); } catch (NumberFormatException ignored) {}
                } else if ("name".equals(k) && !v.isEmpty()) {
                    name = v.length() > 40 ? v.substring(0, 40) : v;
                }
            }
        }

        private void onAudio(byte[] pcm) {
            if (!capturing || pcm.length < 2) return;
            byte[] at24k = pcm;
            if (rate != VoiceSession.RATE) {
                int n = pcm.length / 2;
                short[] s = new short[n];
                for (int i = 0; i < n; i++) s[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
                at24k = VoiceSession.toPcm24k(s, n, rate);
            }
            while (audio.size() >= MAX_QUEUE) audio.poll();
            audio.offer(at24k);
        }

        synchronized void line(String s) {
            if (!alive || out == null) return;
            try {
                out.write((s + "\n").getBytes("UTF-8"));
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            alive = false;
            try { sock.close(); } catch (IOException ignored) {}
            if (client == this) client = null;
        }
    }

    private void openCall() {
        if (VoiceActivity.isOpen()) return;
        Intent i = new Intent(app, VoiceActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { app.startActivity(i); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ helpers

    static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        while (b.length() < 300) {
            int c = in.read();
            if (c < 0) return b.length() == 0 ? null : b.toString();
            if (c == '\n') return b.toString().trim();
            b.append((char) c);
        }
        return b.toString().trim();
    }

    /** This device's Wi-Fi address, so you can type it into the phone if auto-find fails. */
    static String localAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
