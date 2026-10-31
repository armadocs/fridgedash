package app.fridgedash;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * One live voice conversation with OpenAI's Realtime API over a WebSocket.
 *
 * Audio in: microphone → 24 kHz mono PCM16 → input_audio_buffer.append.
 * Audio out: response.output_audio.delta → AudioTrack.
 * Turn-taking: the server detects when you stop talking (semantic VAD).
 *
 * By default the mic is paused while ChatGPT is speaking (plus a short tail),
 * so a fridge speaker next to the mic can't make it answer itself. With
 * "barge-in" on, the mic stays open and speaking interrupts the reply.
 */
final class VoiceSession {

    enum State { CONNECTING, LISTENING, HEARING, THINKING, SPEAKING, ENDED }

    interface Listener {
        void onState(State s);
        void onAssistantText(String fullTextSoFar, boolean newTurn);
        void onLevel(float level);
        /** Microphone level after boost, ~10×/s, and whether it's currently paused. */
        void onMicLevel(float level, boolean paused);
        void onError(String message, boolean fatal);
        /** You said "stop" / "bye": the goodbye has finished playing, close the call screen. */
        void onHangUp();
    }

    static final int RATE = 24000;
    private static final long TAIL_MS = 450;          // keep mic paused briefly after playback
    private static final long IDLE_HANGUP_MS = 120_000; // hang up after 2 min of nobody talking

    private final String apiKey, model, voice;
    private final boolean bargeIn;
    private final int preferredSource;
    private final boolean useMic;   // false = keyboard mode: you type, ChatGPT still speaks
    private final android.content.Context ctx;
    private BtAudio bt;              // set while a Bluetooth headset/speakerphone carries the call
    private volatile boolean usingBt;
    private volatile RemoteMic remote;  // set while a phone on the network is the microphone
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private OkHttpClient http;
    private volatile WebSocket ws;
    private volatile boolean running;
    private volatile boolean muted;
    private volatile State state = State.CONNECTING;

    // Microphone
    private AudioRecord rec;
    private int recRate;
    private int recChannels = 1;     // >1 when the deep scan found sound on one channel of a mic array
    private int recChannel = 0;
    private int micGain = 1;
    private Thread micThread;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;

    // Speaker
    private AudioTrack track;
    private Thread spkThread;
    private final LinkedBlockingQueue<Chunk> playQ = new LinkedBlockingQueue<>();
    private volatile long framesWritten;     // all frames given to the speaker (speech + silence)
    private volatile long speechEndFrame;    // frame where the last real speech ends
    private static final byte[] SILENCE = new byte[RATE / 20 * 2]; // 50 ms
    private volatile long lastPlaybackEnd;
    private volatile boolean flushRequested;
    private volatile String playingItemId;
    private volatile long itemStartFrame;

    private volatile boolean responseActive;
    private volatile long hearingSince;
    private volatile long hangUpAt;      // >0 once ChatGPT decided to end the call
    private static final long MAX_TURN_MS = 15_000; // background noise can stop "end of speech" detection
    private volatile long lastActivity;
    private final StringBuilder transcript = new StringBuilder();
    private boolean newTurnPending = true;

    private static final class Chunk {
        final String itemId;
        final byte[] pcm;
        Chunk(String itemId, byte[] pcm) { this.itemId = itemId; this.pcm = pcm; }
    }

    VoiceSession(String apiKey, String model, String voice, boolean bargeIn, int preferredSource,
                 boolean useMic, android.content.Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.useMic = useMic;
        this.preferredSource = preferredSource;
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.bargeIn = bargeIn;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        running = true;
        lastActivity = System.currentTimeMillis();
        setState(State.CONNECTING);
        http = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build();
        Request req = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=" + model)
                .header("Authorization", "Bearer " + apiKey)
                .build();
        ws = http.newWebSocket(req, new Socket());
    }

    void stop() {
        if (!running) return;
        running = false;
        setState(State.ENDED);
        WebSocket w = ws;
        if (w != null) w.close(1000, "bye");
        // Let the audio threads finish their current 100 ms step before releasing.
        joinQuietly(micThread);
        joinQuietly(spkThread);
        releaseAudio();
        if (remote != null) { remote.state("ended"); remote.end(); remote = null; }
        if (bt != null) { bt.stop(); bt = null; }
        if (http != null) {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
    }

    /** Keyboard mode: send a typed question; the answer comes back as speech + text. */
    boolean sendText(String text) {
        if (!running || ws == null || state == State.CONNECTING) return false;
        try {
            JSONObject content = new JSONObject().put("type", "input_text").put("text", text);
            JSONObject item = new JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put("content", new org.json.JSONArray().put(content));
            if (responseActive || isAssistantAudible()) interrupt();
            send(new JSONObject().put("type", "conversation.item.create").put("item", item).toString());
            send("{\"type\":\"response.create\"}");
            lastActivity = System.currentTimeMillis();
            setState(State.THINKING);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    boolean usesMic() { return useMic; }

    void setMuted(boolean m) { muted = m; }
    boolean isMuted() { return muted; }

    // ------------------------------------------------------------------ websocket

    private final class Socket extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            if (!running) { webSocket.close(1000, null); return; }
            RemoteMic rm = RemoteMic.get(ctx);
            if (useMic && rm.connected()) remote = rm;
            send(sessionUpdate());
            if (remote != null) {
                final String msg = "Using microphone: " + rm.clientName();
                main.post(() -> listener.onError(msg, false));
            } else if (useMic && BtAudio.headsetConnected(ctx)) {
                bt = new BtAudio(ctx);
                usingBt = bt.start(6000);
                String name = BtAudio.headsetName(ctx);
                final String msg = usingBt
                        ? "Using Bluetooth" + (name.isEmpty() ? "" : ": " + name)
                        : "Bluetooth device found, but its microphone didn't connect. Using the fridge instead.";
                main.post(() -> listener.onError(msg, false));
                if (!usingBt) { bt.stop(); bt = null; }
            }
            if (!running) { if (bt != null) { bt.stop(); bt = null; } return; }
            if (!startAudio()) return;
            setState(State.LISTENING);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                handle(new JSONObject(text));
            } catch (JSONException ignored) {
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            if (!running) return;
            String msg;
            int code = response != null ? response.code() : 0;
            if (code == 401) msg = "OpenAI rejected the API key. Check it in Settings.";
            else if (code == 403) msg = "This API key isn't allowed to use realtime voice (HTTP 403).";
            else if (code == 404) msg = "Model \"" + model + "\" not found. Check the model in Settings.";
            else if (code == 429) msg = "OpenAI says: too many requests or no credit left (HTTP 429).";
            else if (code > 0) msg = "Connection failed (HTTP " + code + ").";
            else msg = "Connection lost: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            fail(msg);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            if (running) fail("Call ended by the server" + (reason == null || reason.isEmpty() ? "." : ": " + reason));
        }
    }

    private void handle(JSONObject ev) {
        String type = ev.optString("type");
        switch (type) {
            case "input_audio_buffer.speech_started":
                lastActivity = System.currentTimeMillis();
                hearingSince = lastActivity;
                if (bargeIn && isAssistantAudible()) interrupt();
                setState(State.HEARING);
                break;
            case "input_audio_buffer.speech_stopped":
                lastActivity = System.currentTimeMillis();
                setState(State.THINKING);
                break;
            case "response.created":
                responseActive = true;
                newTurnPending = true;
                break;
            case "response.output_audio.delta": {
                byte[] pcm = Base64.decode(ev.optString("delta"), Base64.DEFAULT);
                playQ.offer(new Chunk(ev.optString("item_id"), pcm));
                lastActivity = System.currentTimeMillis();
                setState(State.SPEAKING);
                break;
            }
            case "response.output_audio_transcript.delta": {
                boolean fresh = newTurnPending;
                if (fresh) { transcript.setLength(0); newTurnPending = false; }
                transcript.append(ev.optString("delta"));
                final String t = transcript.toString();
                main.post(() -> listener.onAssistantText(t, fresh));
                break;
            }
            case "response.output_item.done": {
                JSONObject item = ev.optJSONObject("item");
                if (item != null && "function_call".equals(item.optString("type"))) {
                    String name = item.optString("name");
                    if ("end_call".equals(name)) requestHangUp();
                    else if (Tools.handles(name)) runTool(name, item.optString("call_id"), item.optString("arguments"));
                }
                break;
            }
            case "response.function_call_arguments.done":
                if ("end_call".equals(ev.optString("name"))) requestHangUp();
                break;
            case "response.done": {
                responseActive = false;
                JSONObject resp = ev.optJSONObject("response");
                if (resp != null && "failed".equals(resp.optString("status"))) {
                    JSONObject details = resp.optJSONObject("status_details");
                    JSONObject err = details == null ? null : details.optJSONObject("error");
                    String m = err == null ? "The reply failed." : err.optString("message", "The reply failed.");
                    main.post(() -> listener.onError(m, false));
                }
                if (playQ.isEmpty() && state != State.SPEAKING) setState(State.LISTENING);
                break;
            }
            case "error": {
                JSONObject err = ev.optJSONObject("error");
                String m = err == null ? "Unknown error" : err.optString("message", "Unknown error");
                String code = err == null ? "" : err.optString("code");
                // Harmless races from interrupting (nothing left to cancel/truncate).
                if (code.contains("cancel") || code.contains("truncat") || m.contains("cancel")) break;
                boolean fatal = code.contains("invalid_api_key") || code.contains("model_not_found")
                        || code.contains("insufficient_quota");
                if (fatal) fail(m);
                else main.post(() -> listener.onError(m, false));
                break;
            }
            default:
                break;
        }
    }

    private String sessionUpdate() {
        try {
            JSONObject pcm = new JSONObject().put("type", "audio/pcm").put("rate", RATE);
            JSONObject input = new JSONObject()
                    .put("format", pcm)
                    .put("turn_detection", !useMic ? JSONObject.NULL
                            : remote != null
                            // Phone mic: clean audio, so a plain "0.7 s of quiet = your turn is over" works best.
                            ? new JSONObject().put("type", "server_vad").put("threshold", 0.5)
                                    .put("prefix_padding_ms", 300).put("silence_duration_ms", 700)
                            : new JSONObject().put("type", "semantic_vad"));
            JSONObject output = new JSONObject()
                    .put("format", new JSONObject().put("type", "audio/pcm").put("rate", RATE))
                    .put("voice", voice)
                    .put("speed", Prefs.speed(ctx));
            JSONObject session = new JSONObject()
                    .put("type", "realtime")
                    .put("instructions", instructions(ctx))
                    .put("output_modalities", new org.json.JSONArray().put("audio"))
                    .put("tools", Tools.definitions().put(new JSONObject()
                            .put("type", "function")
                            .put("name", "end_call")
                            .put("description", "Hang up and close the voice screen. Use when the user says "
                                    + "stop, that's all, goodbye, bye, thanks that's it, close, or in Serbian: stani, "
                                    + "ćao, zdravo (as goodbye), doviđenja, to je sve, hvala to je to, ugasi, zatvori.")
                            .put("parameters", new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject()))))
                    .put("tool_choice", "auto")
                    .put("audio", new JSONObject().put("input", input).put("output", output));
            return new JSONObject().put("type", "session.update").put("session", session).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String instructions(android.content.Context c) {
        String home = Tools.homeName(c);
        SimpleDateFormat f = new SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a zzzz", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        String extra = Prefs.extraInstructions(c);
        return "You are ChatGPT, talking by voice through the screen on a kitchen refrigerator. "
                + languageRule(Prefs.language(c)) + " "
                + "Speak naturally and keep answers short (a few sentences) unless asked for more detail. "
                + "People may be cooking or busy, so be clear and practical. "
                + "When the user says stop, bye, that's all or otherwise ends the conversation, say a very short "
                + "goodbye (two or three words) and call end_call. "
                + "If they only want you to stop talking mid-answer but keep going, just stop and wait. "
                + "You can look things up: use get_weather for weather and web_search for news, sports, prices, "
                + "opening hours or anything current. Never say you have no internet or real-time access; look it up. "
                + "Before a web_search, say a few words like \"Let me check.\" "
                + (home.isEmpty() ? "" : "The fridge is at the user's home in " + home + ". ")
                + "Right now it is " + f.format(new Date()) + "."
                + (extra.isEmpty() ? "" : "\n\nThe user's own instructions (follow them): " + extra);
    }

    /** How to handle language; Serbian gets explicit pronunciation guidance (models drift to an English accent). */
    static String languageRule(String lang) {
        String serbian = "speak Serbian (srpski, ekavica) exactly like a native speaker from Belgrade: natural Serbian "
                + "pronunciation, stress and intonation, never an English or foreign accent. Use Serbian words and "
                + "grammar, not Croatian or Bosnian. Write any text in Latin script (latinica).";
        if ("sr".equals(lang)) {
            return "Always " + serbian + " Answer in Serbian even if the user mixes in English, unless they ask you to switch.";
        }
        if ("en".equals(lang)) return "Always speak English.";
        return "Answer in the language the user speaks. When it is Serbian, " + serbian;
    }

    private void send(String json) {
        WebSocket w = ws;
        if (w != null && running) w.send(json);
    }

    // ------------------------------------------------------------------ audio

    boolean usesRemoteMic() { return remote != null; }

    private boolean startAudio() {
        if (useMic && remote == null) {
            rec = openMic();
            if (rec == null) {
                fail("Couldn't open the microphone. Check Settings → Test microphone.");
                return false;
            }
        }
        if (rec != null) try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Exception ignored) {
        }

        int minOut = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usingBt ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minOut, RATE)) // ~0.5 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        // Wake the amplifier before the first answer arrives.
        byte[] warm = new byte[RATE / 2 * 2]; // 0.5 s
        int ww = track.write(warm, 0, warm.length);
        if (ww > 0) framesWritten += ww / 2;

        spkThread = new Thread(this::speakerLoop, "voice-speaker");
        spkThread.start();
        if (rec != null) {
            rec.startRecording();
            micThread = new Thread(this::micLoop, "voice-mic");
            micThread.start();
        } else if (remote != null) {
            remote.begin();
            micThread = new Thread(this::micLoop, "voice-remote-mic");
            micThread.start();
        }
        return true;
    }

    private AudioRecord openMic() {
        if (!usingBt) {
            MicScan.Config cfg = MicScan.Config.decode(Prefs.micScan(ctx));
            if (cfg != null) {
                AudioRecord r = MicScan.open(cfg);
                if (r != null) {
                    micGain = Prefs.micGain(ctx);
                    recRate = cfg.rate;
                    recChannels = cfg.channels;
                    recChannel = cfg.channel;
                    return r;
                }
            }
        }
        int[] rates = {24000, 48000, 16000, 44100};
        int[] sources = usingBt
                ? new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC}
                : preferredSource >= 0
                ? new int[]{preferredSource, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            for (int rate : rates) {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                try {
                    AudioRecord r = new AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 5 * 2));
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        recRate = rate;
                        return r;
                    }
                    r.release();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private void micLoop() {
        try {
            if (remote != null) remoteLoop(); else micLoopInner();
        } catch (Throwable t) {
            if (running) fail("Microphone stopped: " + t.getClass().getSimpleName());
        }
    }

    private void speakerLoop() {
        try {
            speakerLoopInner();
        } catch (Throwable t) {
            if (running) fail("Speaker stopped: " + t.getClass().getSimpleName());
        }
    }

    private void micLoopInner() {
        short[] buf = new short[recRate / 10 * recChannels]; // 100 ms
        short[] mono = new short[recRate / 10];
        int filled = 0;
        long lastData = System.currentTimeMillis();
        boolean everData = false;
        while (running && !Thread.currentThread().isInterrupted()) {
            // Non-blocking: some fridges open the mic but never deliver sound,
            // and a blocking read would hang forever.
            int got = rec.read(buf, filled, buf.length - filled, AudioRecord.READ_NON_BLOCKING);
            long now = System.currentTimeMillis();
            if (got > 0) {
                filled += got;
                lastData = now;
                everData = true;
            } else {
                if (now - lastData > 3000) {
                    fail(everData ? "The microphone stopped sending sound."
                            : "The microphone isn't sending any sound. This fridge may not have a working microphone.");
                    return;
                }
                try { Thread.sleep(15); } catch (InterruptedException e) { return; }
                continue;
            }
            if (filled < buf.length) continue;
            int n = filled;
            filled = 0;
            short[] src = buf;
            if (recChannels > 1) {
                n = MicScan.extract(buf, n, recChannels, recChannel, mono);
                src = mono;
            }
            if (micGain > 1) applyGain(src, n, micGain);
            final short[] samples = src;
            float level = rms(samples, n);

            boolean paused = muted || (!bargeIn && (isAssistantAudible() || now - lastPlaybackEnd < TAIL_MS));
            final boolean pausedNow = paused;
            main.post(() -> listener.onMicLevel(level, pausedNow));
            if (!paused) {
                main.post(() -> { if (state != State.SPEAKING) listener.onLevel(level); });
                byte[] pcm = toPcm24k(samples, n, recRate);
                send("{\"type\":\"input_audio_buffer.append\",\"audio\":\""
                        + Base64.encodeToString(pcm, Base64.NO_WRAP) + "\"}");
            }

            if (now - lastActivity > IDLE_HANGUP_MS && !isAssistantAudible()) {
                main.post(() -> listener.onError("Hung up after 2 minutes of silence.", true));
                main.post(this::stop);
                return;
            }
        }
    }

    /** Same as micLoopInner, but the audio comes from a phone over Wi-Fi (already 24 kHz). */
    private void remoteLoop() {
        long lastData = System.currentTimeMillis();
        while (running && !Thread.currentThread().isInterrupted()) {
            RemoteMic rm = remote;
            if (rm == null) return;
            byte[] pcm = rm.poll(200);
            long now = System.currentTimeMillis();
            if (pcm == null) {
                if (!rm.connected()) {
                    fail("The phone microphone disconnected. Check Fridge Mic on the phone.");
                    return;
                }
                if (now - lastData > 6000) {
                    fail("The phone isn't sending sound. Open Fridge Mic on the phone and allow the microphone.");
                    return;
                }
                if (idleHangup(now)) return;
                continue;
            }
            lastData = now;
            float level = rmsBytes(pcm, 0, pcm.length);
            boolean paused = muted || (!bargeIn && (isAssistantAudible() || now - lastPlaybackEnd < TAIL_MS));
            main.post(() -> listener.onMicLevel(level, paused));
            if (!paused) {
                main.post(() -> { if (state != State.SPEAKING) listener.onLevel(level); });
                send("{\"type\":\"input_audio_buffer.append\",\"audio\":\""
                        + Base64.encodeToString(pcm, Base64.NO_WRAP) + "\"}");
            }
            if (state == State.HEARING && hearingSince > 0 && now - hearingSince > MAX_TURN_MS) {
                // Never heard the end of the sentence (noisy kitchen?): answer what we have.
                hearingSince = 0;
                send("{\"type\":\"input_audio_buffer.commit\"}");
                send("{\"type\":\"response.create\"}");
                setState(State.THINKING);
            }
            if (idleHangup(now)) return;
        }
    }

    private boolean idleHangup(long now) {
        if (now - lastActivity > IDLE_HANGUP_MS && !isAssistantAudible()) {
            main.post(() -> listener.onError("Hung up after 2 minutes of silence.", true));
            main.post(this::stop);
            return true;
        }
        return false;
    }

    private void speakerLoopInner() {
        final int maxChunk = RATE / 10 * 2; // write ≤100 ms at a time so flushes are quick
        while (running && !Thread.currentThread().isInterrupted()) {
            if (flushRequested) doFlush();
            Chunk c;
            try {
                c = playQ.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (c == null) {
                keepAwake();
                long h = hangUpAt;
                if (h > 0 && ((!isAssistantAudible() && !responseActive) || System.currentTimeMillis() - h > 8000)) {
                    hangUpAt = 0;
                    main.post(listener::onHangUp);
                }
                if (state == State.SPEAKING && !isAssistantAudible()) {
                    lastPlaybackEnd = System.currentTimeMillis();
                    if (!responseActive) setState(State.LISTENING);
                }
                continue;
            }
            if (!c.itemId.equals(playingItemId)) {
                playingItemId = c.itemId;
                itemStartFrame = framesWritten;
            }
            for (int off = 0; off < c.pcm.length && running && !flushRequested; off += maxChunk) {
                int len = Math.min(maxChunk, c.pcm.length - off);
                int w = track.write(c.pcm, off, len);
                if (w > 0) {
                    framesWritten += w / 2;
                    speechEndFrame = framesWritten;
                }
                final float level = rmsBytes(c.pcm, off, len);
                main.post(() -> listener.onLevel(level));
            }
        }
    }

    /** Is ChatGPT's audio still queued or playing? */
    private boolean isAssistantAudible() {
        if (!playQ.isEmpty()) return true;
        AudioTrack t = track;
        if (t == null) return false;
        long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        return head < speechEndFrame;
    }

    /**
     * Feeds a little silence while nobody is talking so the fridge's amplifier
     * stays powered; otherwise it sleeps and swallows the start of each answer.
     * Keeps at most ~100 ms queued so real speech isn't delayed.
     */
    private void keepAwake() {
        long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        if (framesWritten - head < RATE / 10) {
            int w = track.write(SILENCE, 0, SILENCE.length);
            if (w > 0) framesWritten += w / 2;
        }
    }

    /** Runs a lookup off the network thread, then hands the result back so ChatGPT can answer. */
    private void runTool(String name, String callId, String args) {
        lastActivity = System.currentTimeMillis();
        setState(State.THINKING);
        new Thread(() -> {
            String result = Tools.run(ctx, name, args);
            if (!running) return;
            try {
                JSONObject item = new JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", callId)
                        .put("output", result);
                send(new JSONObject().put("type", "conversation.item.create").put("item", item).toString());
            } catch (JSONException e) {
                return;
            }
            // Wait for the turn that asked for the lookup to finish before asking for the answer.
            for (int i = 0; i < 60 && responseActive && running; i++) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
            lastActivity = System.currentTimeMillis();
            send("{\"type\":\"response.create\"}");
        }, "voice-tool-" + name).start();
    }

    private void requestHangUp() {
        if (hangUpAt == 0) hangUpAt = System.currentTimeMillis();
    }

    /** You started talking while ChatGPT was speaking (barge-in mode). */
    private void interrupt() {
        playQ.clear();
        flushRequested = true;
        if (responseActive) send("{\"type\":\"response.cancel\"}");
    }

    private void doFlush() {
        long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long playedMs = Math.max(0, head - itemStartFrame) * 1000 / RATE;
        String item = playingItemId;
        track.pause();
        track.flush();
        track.play();
        framesWritten = 0;
        speechEndFrame = 0;
        itemStartFrame = 0;
        playingItemId = null;
        playQ.clear();
        flushRequested = false;
        lastPlaybackEnd = System.currentTimeMillis();
        if (item != null && !item.isEmpty()) {
            // Tell the model how much of its answer was actually heard.
            send("{\"type\":\"conversation.item.truncate\",\"item_id\":\"" + item
                    + "\",\"content_index\":0,\"audio_end_ms\":" + playedMs + "}");
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        t.interrupt();
        try { t.join(400); } catch (InterruptedException ignored) {}
    }

    private void releaseAudio() {
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
        try { if (aec != null) aec.release(); } catch (Exception ignored) {}
        try { if (ns != null) ns.release(); } catch (Exception ignored) {}
        try { if (track != null) { track.pause(); track.flush(); track.release(); } } catch (Exception ignored) {}
        rec = null;
        track = null;
    }

    // ------------------------------------------------------------------ helpers

    private void setState(State s) {
        if (state == s) return;
        state = s;
        RemoteMic rm = remote;
        if (rm != null) rm.state(s.name().toLowerCase(Locale.US));
        main.post(() -> listener.onState(s));
    }

    private void fail(String msg) {
        main.post(() -> listener.onError(msg, true));
        main.post(this::stop);
    }

    /** Boosts quiet audio; soft-limits instead of hard clipping. */
    static void applyGain(short[] b, int n, int gain) {
        for (int i = 0; i < n; i++) {
            double v = b[i] * (double) gain / 32768.0;
            if (v > 0.8) v = 0.8 + 0.2 * Math.tanh((v - 0.8) / 0.2);
            else if (v < -0.8) v = -0.8 + 0.2 * Math.tanh((v + 0.8) / 0.2);
            b[i] = (short) Math.round(v * 32767.0);
        }
    }

    /** Linear resample to 24 kHz and encode as little-endian PCM16. */
    static byte[] toPcm24k(short[] in, int n, int inRate) {
        int outLen = (int) ((long) n * RATE / inRate);
        byte[] out = new byte[outLen * 2];
        for (int i = 0; i < outLen; i++) {
            short s;
            if (inRate == RATE) {
                s = in[i];
            } else {
                double pos = (double) i * inRate / RATE;
                int idx = (int) pos;
                double frac = pos - idx;
                int a = in[Math.min(idx, n - 1)], b = in[Math.min(idx + 1, n - 1)];
                s = (short) Math.round(a + (b - a) * frac);
            }
            out[2 * i] = (byte) (s & 0xFF);
            out[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    static float rms(short[] b, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) b[i] * b[i];
        return (float) Math.min(1.0, Math.sqrt(sum / Math.max(1, n)) / 6000.0);
    }

    private static float rmsBytes(byte[] b, int off, int len) {
        double sum = 0;
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            int s = (short) ((b[off + 2 * i] & 0xFF) | (b[off + 2 * i + 1] << 8));
            sum += (double) s * s;
        }
        return (float) Math.min(1.0, Math.sqrt(sum / Math.max(1, n)) / 6000.0);
    }
}
