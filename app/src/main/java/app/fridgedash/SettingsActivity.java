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

        col.addView(button("Test microphone (3 seconds)", v -> testMic(), false), full());
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

    private void refreshVoice() {
        voiceButton.setText("Voice: " + Prefs.voice(this) + "   (tap to change)");
    }

    private void refreshBarge() {
        bargeButton.setText("Interrupt ChatGPT by speaking: " + (Prefs.bargeIn(this) ? "ON" : "OFF"));
    }

    private void testMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_TEST);
            return;
        }
        micStatus.setText("Recording… say something");
        bg.execute(() -> {
            String result;
            AudioRecord r = null;
            try {
                int rate = 16000;
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("No microphone found");
                r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
                if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone couldn't start");
                r.startRecording();
                short[] buf = new short[rate / 10];
                float peak = 0, quiet = 1;
                for (int i = 0; i < 30; i++) {
                    int n = r.read(buf, 0, buf.length);
                    if (n > 0) {
                        float l = VoiceSession.rms(buf, n);
                        peak = Math.max(peak, l);
                        quiet = Math.min(quiet, l);
                    }
                }
                int pk = Math.round(peak * 100), q = Math.round(quiet * 100);
                if (peak < 0.01f) result = "Microphone opened but heard nothing (level " + pk + "%). It may be missing or disabled.";
                else result = "✓ Microphone works. Loudest " + pk + "%, background " + q + "%.";
            } catch (Exception e) {
                result = "✗ " + e.getMessage();
            } finally {
                if (r != null) { try { r.stop(); } catch (Exception ignored) {} r.release(); }
            }
            final String fr = result;
            main.post(() -> { if (!isDestroyed()) micStatus.setText(fr); });
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
