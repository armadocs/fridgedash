package app.fridgedash;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** FridgeDash settings. Everything here is stored only on the fridge. */
public class SettingsActivity extends BaseActivity {

    private static final int REQ_MIC_TEST = 11;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout col;
    private EditText keyField, modelField;
    private TextView homeButton;
    private TextView inputButton;
    private TextView gainButton;
    private TextView keyStatus, micStatus, voiceButton, bargeButton, wallButton, sizeButton, updateStatus;
    private boolean keyVisible;
    private UpdateFlow updates;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.bg));
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(col);
        setContentView(scroll);

        LinearLayout top = row();
        TextView title = text("Settings", 22, true);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(button("Done", v -> finish(), false), wrap());
        col.addView(top);

        // ---------------------------------------------------------- ChatGPT voice
        section("ChatGPT voice");
        col.addView(note("Your OpenAI API key is stored only on this fridge. Paste it from the clipboard "
                + "(for example: email it to yourself, open the email in the browser here, copy it). "
                + "Tip: set a monthly budget limit at platform.openai.com."));

        keyField = new EditText(this);
        keyField.setSingleLine(true);
        keyField.setHint("sk-…");
        keyField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        keyField.setTextColor(getColor(R.color.text));
        keyField.setHintTextColor(getColor(R.color.text_dim));
        keyField.setText(Prefs.apiKey(this));
        setKeyVisible(false);
        col.addView(keyField);

        LinearLayout keyRow = row();
        keyRow.addView(button("Paste", v -> pasteKey(), true), weighted());
        keyRow.addView(button("Show / hide", v -> setKeyVisible(!keyVisible), false), weighted());
        keyRow.addView(button("Save & test", v -> saveAndTestKey(), true), weighted());
        col.addView(keyRow);
        keyStatus = note(Prefs.apiKey(this).isEmpty() ? "No key saved yet." : "A key is saved.");
        col.addView(keyStatus);

        inputButton = button("", v -> cycleInput(), false);
        col.addView(inputButton, full());
        col.addView(note("Keyboard: type your question, ChatGPT answers out loud. Automatic uses the "
                + "microphone only if \"Test microphone\" found a working one."));
        refreshInput();

        voiceButton = button("", v -> cycleVoice(), false);
        col.addView(voiceButton, full());
        refreshVoice();

        bargeButton = button("", v -> {
            Prefs.setBargeIn(this, !Prefs.bargeIn(this));
            refreshBarge();
        }, false);
        col.addView(bargeButton, full());
        col.addView(note("Off is safer on a fridge: the microphone pauses while ChatGPT talks, so it can't "
                + "hear itself. Turn on to interrupt by speaking (works best if the fridge has echo cancelling)."));
        refreshBarge();

        LinearLayout testRow = row();
        testRow.addView(button("Test microphone", v -> testMic(), false), weighted());
        testRow.addView(button("Test speaker", v -> testSpeaker(), false), weighted());
        col.addView(testRow);
        col.addView(button("Deep microphone scan (≈35 s, clap the whole time)", v -> deepScan(), false), full());
        col.addView(button("Record 5 s & play back (hear what ChatGPT hears)", v -> recordPlayback(), true), full());
        gainButton = button("", v -> cycleGain(), false);
        col.addView(gainButton, full());
        refreshGain();
        col.addView(note("Boost makes the fridge's quiet built-in microphone louder for ChatGPT. "
                + "If it doesn't react when you talk, raise it; if it reacts to every kitchen noise, lower it."));
        micStatus = note("");
        col.addView(micStatus);

        col.addView(text("Model (advanced)", 12, false));
        modelField = new EditText(this);
        modelField.setSingleLine(true);
        modelField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        modelField.setTextColor(getColor(R.color.text));
        modelField.setText(Prefs.model(this));
        col.addView(modelField);

        // ---------------------------------------------------------- Screen
        section("Screen");
        wallButton = button("", v -> {
            Prefs.setDailyWallpaper(this, !Prefs.dailyWallpaper(this));
            refreshWallpaperButton();
        }, false);
        col.addView(wallButton, full());
        refreshWallpaperButton();
        col.addView(button("Get today's photo now", v -> fetchPhotoNow(), false), full());

        sizeButton = button("", v -> cycleSize(), false);
        col.addView(sizeButton, full());
        refreshSize();

        // ---------------------------------------------------------- FridgeDash
        section("FridgeDash");
        TextView updateButton = button("", null, true);
        col.addView(updateButton, full());
        updateStatus = note("");
        updateStatus.setVisibility(View.GONE);
        col.addView(updateStatus);
        updates = new UpdateFlow(this, bg, updateStatus, null, updateButton);

        homeButton = button("", v -> FridgeHome.openHomeSettings(this), false);
        col.addView(homeButton, full());
        col.addView(note("Choose \"FridgeDash\" there to open FridgeDash with the Home button. "
                + "To go back to the original screen, choose \"Home\" (the blue house) in the same menu. "
                + "Test the Fridge button first."));

        col.addView(button("Refrigerator button & diagnostics", v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)), false), full());
        col.addView(button("All apps", v -> startActivity(new Intent(this, AppsActivity.class)), false), full());
        col.addView(button("Android settings", v -> Ui.start(this,
                new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)), false), full());
    }

    @Override
    protected void onResume() {
        super.onResume();
        homeButton.setText(FridgeHome.isFridgeDashDefault(this)
                ? "Home button opens: FridgeDash  (tap to change)"
                : "Home button opens: Hisense  (tap to make it FridgeDash)");
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Save typed values when leaving.
        Prefs.setApiKey(this, keyField.getText().toString());
        Prefs.setModel(this, modelField.getText().toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdownNow();
    }

    // ------------------------------------------------------------------ API key

    private void setKeyVisible(boolean visible) {
        keyVisible = visible;
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (visible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        keyField.setTypeface(Typeface.MONOSPACE);
        keyField.setSelection(keyField.getText().length());
    }

    private void pasteKey() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cb == null ? null : cb.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        String key = t == null ? "" : t.toString().trim();
        // Keep just the key if extra text was copied with it.
        int at = key.indexOf("sk-");
        if (at > 0) key = key.substring(at);
        int space = key.indexOf(' ');
        if (space > 0) key = key.substring(0, space);
        keyField.setText(key);
        setKeyVisible(false);
        saveAndTestKey();
    }

    private void saveAndTestKey() {
        final String key = keyField.getText().toString().trim();
        final String model = modelField.getText().toString().trim();
        Prefs.setApiKey(this, key);
        Prefs.setModel(this, model);
        if (key.isEmpty()) {
            keyStatus.setText("No key saved.");
            return;
        }
        keyStatus.setText("Testing the key…");
        final String m = Prefs.model(this);
        bg.execute(() -> {
            String result;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.openai.com/v1/models/" + m).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Authorization", "Bearer " + key);
                int code = c.getResponseCode();
                c.disconnect();
                if (code == 200) result = "✓ Key works and \"" + m + "\" is available. You can start a call.";
                else if (code == 401) result = "✗ OpenAI rejected this key (401). Check it was copied completely.";
                else if (code == 404) result = "Key accepted, but model \"" + m + "\" wasn't found. Check the model name.";
                else if (code == 429) result = "✗ Rate limited or no credit on this account (429).";
                else result = "Unexpected answer from OpenAI: HTTP " + code;
            } catch (Exception e) {
                result = "✗ Couldn't reach OpenAI: " + e.getMessage();
            }
            final String r = result;
            main.post(() -> { if (!isDestroyed()) keyStatus.setText(r); });
        });
    }

    // ------------------------------------------------------------------ voice options

    private void cycleVoice() {
        String cur = Prefs.voice(this);
        int i = 0;
        for (int k = 0; k < Prefs.VOICES.length; k++) if (Prefs.VOICES[k].equals(cur)) i = k;
        Prefs.setVoice(this, Prefs.VOICES[(i + 1) % Prefs.VOICES.length]);
        refreshVoice();
    }

    private void cycleInput() {
        String m = Prefs.inputMode(this);
        Prefs.setInputMode(this, "auto".equals(m) ? "keyboard" : "keyboard".equals(m) ? "mic" : "auto");
        refreshInput();
    }

    private void refreshInput() {
        String m = Prefs.inputMode(this);
        String now = Prefs.useKeyboard(this) ? "keyboard" : "microphone";
        inputButton.setText("Talk to ChatGPT with: " + ("auto".equals(m) ? "Automatic (" + now + ")"
                : "keyboard".equals(m) ? "Keyboard" : "Microphone") + "   (tap to change)");
    }

    private void cycleGain() {
        int cur = Prefs.micGain(this), next = Prefs.MIC_GAINS[0];
        for (int i = 0; i < Prefs.MIC_GAINS.length; i++)
            if (Prefs.MIC_GAINS[i] == cur) next = Prefs.MIC_GAINS[(i + 1) % Prefs.MIC_GAINS.length];
        Prefs.setMicGain(this, next);
        refreshGain();
    }

    private void refreshGain() {
        gainButton.setText("Microphone boost: ×" + Prefs.micGain(this) + "   (tap to change)");
    }

    private void refreshVoice() {
        voiceButton.setText("Voice: " + Prefs.voice(this) + "   (tap to change)");
    }

    private void refreshBarge() {
        bargeButton.setText("Interrupt ChatGPT by speaking: " + (Prefs.bargeIn(this) ? "ON" : "OFF"));
    }

    private static final int[] MIC_SOURCES = {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.UNPROCESSED};
    private static final String[] MIC_NAMES = {"MIC", "VOICE_RECOGNITION", "VOICE_COMMUNICATION", "CAMCORDER", "UNPROCESSED"};

    /** Tries every microphone input for ~1.5 s each; remembers the best one for calls. */
    private void testMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_TEST);
            return;
        }
        micStatus.setText("Keep talking for about 8 seconds…");
        final Context app = getApplicationContext();
        // Own thread: a stuck microphone must not block other settings actions.
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            // With a Bluetooth headset/speakerphone connected, test its microphone instead.
            BtAudio bt = null;
            boolean viaBt = false;
            if (BtAudio.headsetConnected(app)) {
                bt = new BtAudio(app);
                viaBt = bt.start(6000);
                String name = BtAudio.headsetName(app);
                sb.append(viaBt ? "Testing Bluetooth microphone" + (name.isEmpty() ? "" : " (" + name + ")") + "\n"
                        : "Bluetooth device found but its call audio didn't connect; testing the fridge.\n");
                if (!viaBt) { bt.stop(); bt = null; }
            }
            int bestSource = -1;
            float best = 0;
            int rate = 16000;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                sb.append("✗ Android reports no microphone input at all.");
            } else {
                for (int k = 0; k < MIC_SOURCES.length; k++) {
                    AudioRecord r = null;
                    String line;
                    try {
                        r = new AudioRecord(MIC_SOURCES[k], rate, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
                        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                            line = "can't open";
                        } else {
                            r.startRecording();
                            short[] buf = new short[rate / 10];
                            float peak = 0;
                            long frames = 0;
                            long t0 = System.currentTimeMillis();
                            while (System.currentTimeMillis() - t0 < 1500) {
                                int n = r.read(buf, 0, buf.length, AudioRecord.READ_NON_BLOCKING);
                                if (n > 0) {
                                    frames += n;
                                    peak = Math.max(peak, VoiceSession.rms(buf, n));
                                } else {
                                    try { Thread.sleep(20); } catch (InterruptedException ie) { break; }
                                }
                            }
                            if (frames == 0) {
                                line = "opens, but no sound data at all";
                            } else {
                                line = Math.round(peak * 100) + "%";
                                if (peak > best) { best = peak; bestSource = MIC_SOURCES[k]; }
                            }
                        }
                    } catch (Exception e) {
                        line = "error";
                    } finally {
                        if (r != null) { try { r.stop(); } catch (Exception ignored) {} r.release(); }
                    }
                    sb.append(MIC_NAMES[k]).append(": ").append(line).append('\n');
                }
                if (best >= 0.02f) {
                    Prefs.setMicSource(app, bestSource);
                    Prefs.setMicViaBt(app, viaBt);
                } else if (!viaBt) {
                    Prefs.setMicSource(app, -1);
                }
                if (best >= 0.02f) {
                    sb.insert(0, "✓ Microphone works. Calls will use the loudest input.\n");
                } else {
                    sb.insert(0, "✗ No input delivered any sound. This fridge most likely has no working "
                            + "microphone (or Hisense blocks it).\n");
                }
            }
            if (bt != null) bt.stop();
            final String fr = sb.toString().trim();
            main.post(() -> { if (!isDestroyed()) { micStatus.setText(fr); refreshInput(); } });
        }, "mic-test").start();
    }

    /** Records 5 s exactly as a call would (scan setup + boost), shows a level timeline, plays it back. */
    private void recordPlayback() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_TEST);
            return;
        }
        final Context app = getApplicationContext();
        micStatus.setText("Recording 5 seconds… stay quiet 1 s, then talk normally facing the screen.");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            MicScan.Config cfg = MicScan.Config.decode(Prefs.micScan(app));
            if (cfg == null) cfg = new MicScan.Config(MediaRecorder.AudioSource.MIC, 16000, 1, 0);
            int gain = Prefs.micGain(app);
            android.media.AudioRecord r = MicScan.open(cfg);
            if (r == null) {
                sb.append("✗ Couldn't open the microphone with the saved setup.");
            } else {
                int total = cfg.rate * 5;
                short[] mono = new short[total];
                short[] buf = new short[cfg.rate / 10 * cfg.channels];
                short[] one = new short[cfg.rate / 10];
                float[] slots = new float[50];   // 100 ms each
                int got = 0;
                try {
                    r.startRecording();
                    long t0 = System.currentTimeMillis();
                    while (got < total && System.currentTimeMillis() - t0 < 7000) {
                        int n = r.read(buf, 0, buf.length, android.media.AudioRecord.READ_NON_BLOCKING);
                        if (n <= 0) { Thread.sleep(15); continue; }
                        int fr = cfg.channels > 1 ? MicScan.extract(buf, n, cfg.channels, cfg.channel, one) : n;
                        short[] src = cfg.channels > 1 ? one : buf;
                        if (gain > 1) VoiceSession.applyGain(src, fr, gain);
                        int take = Math.min(fr, total - got);
                        System.arraycopy(src, 0, mono, got, take);
                        int slot = Math.min(49, got / (cfg.rate / 10));
                        slots[slot] = Math.max(slots[slot], VoiceSession.rms(src, take));
                        got += take;
                    }
                } catch (Exception e) {
                    sb.append("Recording error: ").append(e).append('\n');
                } finally {
                    try { r.stop(); } catch (Exception ignored) {}
                    r.release();
                }
                // Timeline: one character per 100 ms.
                String levels = " ▁▂▃▄▅▆▇█";
                StringBuilder line = new StringBuilder();
                int loud = 0;
                float quietAvg = 0;
                for (int i = 0; i < 50; i++) {
                    int k = Math.min(8, Math.round(slots[i] * 8));
                    line.append(levels.charAt(k));
                    if (slots[i] > 0.05f) loud++;
                    if (i < 10) quietAvg += slots[i] / 10f;
                }
                sb.append("Boost ×").append(gain).append(", ").append(got * 1000 / cfg.rate).append(" ms recorded\n");
                sb.append("Level over 5 s (quiet → talk):\n[").append(line).append("]\n");
                sb.append("Loud moments: ").append(loud).append(" of 50 · background ")
                        .append(Math.round(quietAvg * 100)).append("%\n");
                sb.append(loud >= 5 ? "→ The mic is reacting to sound over time.\n"
                        : loud > 0 ? "→ Only a few spikes: probably clicks, not your voice.\n"
                        : "→ Nothing above background.\n");
                final String partial = sb.toString() + "Playing back…";
                main.post(() -> { if (!isDestroyed()) micStatus.setText(partial); });
                sb.append(playPcm(mono, got, cfg.rate));
            }
            final String m = sb.toString();
            main.post(() -> { if (!isDestroyed()) micStatus.setText(m); });
        }, "rec-playback").start();
    }

    /** Plays mono PCM the same way ChatGPT's voice is played (24 kHz stream). Returns a status line. */
    private static String playPcm(short[] pcm, int n, int rate) {
        android.media.AudioTrack t = null;
        try {
            byte[] data = VoiceSession.toPcm24k(pcm, n, rate);
            int r24 = VoiceSession.RATE;
            t = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(r24).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(r24) // 0.5 s
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build();
            t.play();
            byte[] lead = new byte[r24 * 6 / 10 * 2];
            t.write(lead, 0, lead.length);              // wake the amplifier
            int chunk = r24 / 10 * 2;
            for (int off = 0; off < data.length; off += chunk) {
                t.write(data, off, Math.min(chunk, data.length - off));
            }
            long frames = (lead.length + data.length) / 2;
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 4000
                    && (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) < frames) {
                Thread.sleep(50);
            }
            return "Played back " + (data.length / 2 * 1000 / r24) + " ms. Did you hear yourself?";
        } catch (Exception e) {
            return "✗ Playback failed: " + e;
        } finally {
            if (t != null) { try { t.stop(); } catch (Exception ignored) {} t.release(); }
        }
    }

    private void deepScan() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_TEST);
            return;
        }
        micStatus.setText("Starting… clap and talk near the screen until it finishes.");
        final Context app = getApplicationContext();
        new Thread(() -> {
            String report = MicScan.run(app, line -> main.post(() -> { if (!isDestroyed()) micStatus.setText(line); }));
            main.post(() -> { if (!isDestroyed()) { micStatus.setText(report); refreshInput(); } });
        }, "mic-scan").start();
    }

    /** Plays a 1-second tone through the same channel ChatGPT's voice uses. */
    private void testSpeaker() {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int vol = am == null ? -1 : am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int max = am == null ? -1 : am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        bg.execute(() -> {
            String msg;
            try {
                int rate = VoiceSession.RATE;
                int lead = rate * 6 / 10;  // 0.6 s of silence wakes the amplifier first
                int beep = rate;           // 1 s tone
                short[] tone = new short[lead + beep];
                for (int i = 0; i < beep; i++) {
                    double env = Math.min(1.0, Math.min(i, beep - i) / 2400.0);
                    tone[lead + i] = (short) (Math.sin(2 * Math.PI * 660 * i / rate) * 12000 * env);
                }
                android.media.AudioTrack t = new android.media.AudioTrack.Builder()
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(tone.length * 2)
                        .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                        .build();
                t.write(tone, 0, tone.length);
                long started = System.currentTimeMillis();
                t.play();
                // Wait until it has really finished playing (not a fixed time), up to 5 s.
                while (System.currentTimeMillis() - started < 5000
                        && (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) < tone.length) {
                    Thread.sleep(50);
                }
                long took = System.currentTimeMillis() - started;
                Thread.sleep(200);
                t.release();
                msg = "Played a beep (" + took + " ms incl. 600 ms wake-up). Media volume " + vol + " of " + max
                        + (vol == 0 ? " (MUTED: raise the volume)" : "") + ". Did you hear it?";
            } catch (Exception e) {
                msg = "✗ Couldn't play sound: " + e.getMessage();
            }
            final String m = msg;
            main.post(() -> { if (!isDestroyed()) micStatus.setText(m); });
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_MIC_TEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) testMic();
            else micStatus.setText("Microphone permission denied.");
        }
    }

    // ------------------------------------------------------------------ screen options

    private void refreshWallpaperButton() {
        wallButton.setText("Daily photo wallpaper (Bing): " + (Prefs.dailyWallpaper(this) ? "ON" : "OFF"));
    }

    private void fetchPhotoNow() {
        Toast.makeText(this, "Downloading today's photo…", Toast.LENGTH_SHORT).show();
        final Context app = getApplicationContext();
        bg.execute(() -> {
            String msg;
            try {
                Wallpaper.refresh(app);
                msg = "New photo saved. Go back to see it.";
            } catch (Exception e) {
                msg = "Couldn't get the photo: " + e.getMessage();
            }
            final String m = msg;
            main.post(() -> Toast.makeText(app, m, Toast.LENGTH_LONG).show());
        });
    }

    private void cycleSize() {
        int cur = Prefs.uiWidthDp(this);
        int next = Prefs.UI_WIDTHS[0];
        for (int i = 0; i < Prefs.UI_WIDTHS.length; i++) {
            if (Prefs.UI_WIDTHS[i] == cur) next = Prefs.UI_WIDTHS[(i + 1) % Prefs.UI_WIDTHS.length];
        }
        Prefs.setUiWidthDp(this, next);
        new AlertDialog.Builder(this)
                .setMessage("Screen size changed. FridgeDash will restart to apply it.")
                .setPositiveButton("OK", (d, w) -> {
                    Intent i = new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                })
                .show();
        refreshSize();
    }

    private void refreshSize() {
        int w = Prefs.uiWidthDp(this);
        String label = w <= 480 ? "Largest" : w <= 540 ? "Large (default)" : w <= 600 ? "Medium" : "Small";
        sizeButton.setText("Text & button size: " + label + "   (tap to change)");
    }

    // ------------------------------------------------------------------ view helpers

    private void section(String s) {
        TextView t = text(s.toUpperCase(), 11, true);
        t.setTextColor(0xFFF5B14C);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 6));
        col.addView(t);
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(R.color.text));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 10, false);
        t.setTextColor(getColor(R.color.text_dim));
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        return t;
    }

    private TextView button(String s, View.OnClickListener l, boolean primary) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(getColor(R.color.text));
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(primary ? R.drawable.tile_primary : R.drawable.tile);
        int p = Ui.dp(this, 10);
        b.setPadding(p, p, p, p);
        b.setMinHeight(Ui.dp(this, 44));
        b.setClickable(true);
        b.setFocusable(true);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        return r;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Ui.dp(this, 6));
        return lp;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        return lp;
    }
}
