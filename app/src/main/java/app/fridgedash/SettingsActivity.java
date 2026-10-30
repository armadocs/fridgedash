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
    private EditText keyField, modelField, cityField, extraField;
    private TextView langButton, speedButton, modelButton, voiceStatus;
    private TextView homeButton;
    private TextView inputButton;
    private EditText briefUrlField, briefTokenField;
    private TextView briefStatus;
    private EditText notionField;
    private TextView notionStatus;
    private TextView gainButton;
    private TextView remoteStatus, remoteButton;
    private TextView artIdleButton, artIntervalButton, orientButton, albumsStatus, collectionButton;
    private EditText albumsField;
    private final Runnable remoteTick = new Runnable() {
        @Override public void run() {
            refreshRemote();
            main.postDelayed(this, 1000);
        }
    };
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

        col.addView(text("Your city (for weather and local answers)", 12, false));
        cityField = new EditText(this);
        cityField.setSingleLine(true);
        cityField.setHint(Tools.homeName(this).isEmpty() ? "e.g. Belgrade" : "Automatic: " + Tools.homeName(this));
        cityField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cityField.setTextColor(getColor(R.color.text));
        cityField.setHintTextColor(getColor(R.color.text_dim));
        cityField.setText(Prefs.homeCity(this));
        col.addView(cityField);
        col.addView(note("Leave empty to work it out automatically. ChatGPT can check the weather and search the web "
                + "(news, sports, prices, opening hours); searches use your OpenAI key and cost about a cent each."));

        langButton = button("", v -> {
            String l = Prefs.language(this);
            Prefs.setLanguage(this, "auto".equals(l) ? "sr" : "sr".equals(l) ? "en" : "auto");
            refreshVoice();
        }, false);
        col.addView(langButton, full());
        voiceButton = button("", v -> pickVoice(), false);
        col.addView(voiceButton, full());
        LinearLayout vrow = row();
        speedButton = button("", v -> {
            float cur = Prefs.speed(this), next = Prefs.SPEEDS[0];
            for (int i = 0; i < Prefs.SPEEDS.length; i++)
                if (Math.abs(Prefs.SPEEDS[i] - cur) < 0.01f) next = Prefs.SPEEDS[(i + 1) % Prefs.SPEEDS.length];
            Prefs.setSpeed(this, next);
            refreshVoice();
        }, false);
        vrow.addView(speedButton, weighted());
        vrow.addView(button("▶ Hear this voice", v -> previewVoice(), true), weighted());
        col.addView(vrow);
        modelButton = button("", v -> pickModel(), false);
        col.addView(modelButton, full());
        voiceStatus = note("Pick a voice and tap ▶ to hear it speak your language (uses your key, well under a cent).");
        col.addView(voiceStatus);

        col.addView(text("Your instructions for ChatGPT (optional)", 12, false));
        extraField = new EditText(this);
        extraField.setMinLines(2);
        extraField.setMaxLines(6);
        extraField.setHint("e.g. Zovi me Boris. Budi kratak i duhovit. We are a family of four.");
        extraField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        extraField.setTextColor(getColor(R.color.text));
        extraField.setHintTextColor(getColor(R.color.text_dim));
        extraField.setText(Prefs.extraInstructions(this));
        col.addView(extraField);
        col.addView(note("Personality, how to address you, facts about your home: ChatGPT follows this in every call."));
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

        // ---------------------------------------------------------- Phone as microphone
        section("Phone as microphone");
        col.addView(note("Install FridgeDash on a phone, open \"Fridge Mic\" there and turn it on. Then the "
                + "ChatGPT button here uses the phone's microphone over Wi-Fi, and ChatGPT answers through the "
                + "fridge's speaker. You can also start a call from the phone."));
        remoteStatus = text("", 12, false);
        remoteStatus.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        col.addView(remoteStatus);
        remoteButton = button("", v -> {
            boolean on = !Prefs.remoteMicServer(this);
            Prefs.setRemoteMicServer(this, on);
            if (on) RemoteMic.get(this).startServer(); else RemoteMic.get(this).stopServer();
            refreshRemote();
            refreshInput();
        }, false);
        col.addView(remoteButton, full());
        col.addView(button("Use THIS device as the microphone for a fridge (Fridge Mic)",
                v -> startActivity(new Intent(this, MicLinkActivity.class)), false), full());

        // ---------------------------------------------------------- Daily brief
        section("Daily brief");
        col.addView(note("Grok saves the brief as brief.json in your private GitHub repo; the fridge reads it "
                + "every 5 minutes. Paste a read-only GitHub key (fine-grained token with Contents: Read-only "
                + "on armadocs/daily-brief)."));
        col.addView(text("Brief file link", 12, false));
        briefUrlField = new EditText(this);
        briefUrlField.setSingleLine(true);
        briefUrlField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        briefUrlField.setTextColor(getColor(R.color.text));
        String bu = Prefs.briefUrl(this);
        briefUrlField.setText(bu.isEmpty() ? Brief.DEFAULT_URL : bu);
        col.addView(briefUrlField);
        col.addView(text("GitHub read key", 12, false));
        briefTokenField = new EditText(this);
        briefTokenField.setSingleLine(true);
        briefTokenField.setHint("github_pat_…");
        briefTokenField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        briefTokenField.setTextColor(getColor(R.color.text));
        briefTokenField.setHintTextColor(getColor(R.color.text_dim));
        briefTokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        briefTokenField.setTypeface(Typeface.MONOSPACE);
        briefTokenField.setText(Prefs.briefToken(this));
        col.addView(briefTokenField);
        LinearLayout briefRow = row();
        briefRow.addView(button("Paste key", v -> {
            String t = clipboardText();
            int at = t.indexOf("github_pat_");
            if (at < 0) at = t.indexOf("ghp_");
            if (at > 0) t = t.substring(at);
            int sp = t.indexOf(' ');
            if (sp > 0) t = t.substring(0, sp);
            briefTokenField.setText(t.trim());
            saveAndTestBrief();
        }, true), weighted());
        briefRow.addView(button("Save & load now", v -> saveAndTestBrief(), true), weighted());
        col.addView(briefRow);
        briefStatus = note(Prefs.briefUrl(this).isEmpty() ? "Not connected yet." : "Connected.");
        col.addView(briefStatus);

        col.addView(text("Notion (shared to-do list, recommended)", 13, true));
        col.addView(note("When Notion is connected, the fridge shows the \"Daily Brief\" database instead of the "
                + "file, and ticking an item off updates Notion (and so your phone and Grok). Paste the "
                + "FridgeDash integration key (starts with ntn_), then Connect."));
        notionField = new EditText(this);
        notionField.setSingleLine(true);
        notionField.setHint("ntn_…");
        notionField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        notionField.setTextColor(getColor(R.color.text));
        notionField.setHintTextColor(getColor(R.color.text_dim));
        notionField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        notionField.setTypeface(Typeface.MONOSPACE);
        notionField.setText(Prefs.notionToken(this));
        col.addView(notionField);
        LinearLayout notionRow = row();
        notionRow.addView(button("Paste key", v -> {
            String t = clipboardText();
            int at = t.indexOf("ntn_");
            if (at < 0) at = t.indexOf("secret_");
            if (at > 0) t = t.substring(at);
            int sp = t.indexOf(' ');
            if (sp > 0) t = t.substring(0, sp);
            notionField.setText(t.trim());
        }, true), weighted());
        notionRow.addView(button("Connect Notion", v -> connectNotion(), true), weighted());
        notionRow.addView(button("Disconnect", v -> {
            Prefs.setNotionDb(this, "");
            notionStatus.setText("Disconnected. The fridge uses the brief file again.");
        }, false), weighted());
        col.addView(notionRow);
        col.addView(button("Test Notion write (ticks & unticks one item)", v -> testNotionWrite(), false), full());
        notionStatus = note(Notion.configured(this) ? "✓ Connected to the Daily Brief database." : "Not connected.");
        col.addView(notionStatus);

        // ---------------------------------------------------------- Screen
        section("Screen");
        wallButton = button("", v -> {
            Prefs.setDailyWallpaper(this, !Prefs.dailyWallpaper(this));
            refreshWallpaperButton();
        }, false);
        col.addView(wallButton, full());
        refreshWallpaperButton();
        col.addView(button("Get today's photo now", v -> fetchPhotoNow(), false), full());

        orientButton = button("", v -> {
            String o = Prefs.orientation(this);
            Prefs.setOrientation(this, "auto".equals(o) ? "landscape" : "landscape".equals(o) ? "portrait" : "auto");
            refreshOrient();
            applyOrientation();
        }, false);
        col.addView(orientButton, full());
        refreshOrient();

        sizeButton = button("", v -> cycleSize(), false);
        col.addView(sizeButton, full());
        refreshSize();

        // ---------------------------------------------------------- Art mode
        section("Art mode");
        col.addView(note("When nobody uses the screen, famous paintings fill it (public-domain masterpieces from the "
                + "Art Institute of Chicago and the Met, a new one every few minutes). Touch, a key, the mouse or "
                + "someone coming close brings the dashboard back. Left/right arrow keys flip paintings. The screen stays on while this is enabled."));
        artIdleButton = button("", v -> {
            Prefs.setArtIdleMin(this, nextOf(Prefs.ART_IDLE, Prefs.artIdleMin(this)));
            refreshArt();
        }, false);
        col.addView(artIdleButton, full());
        artIntervalButton = button("", v -> {
            Prefs.setArtIntervalMin(this, nextOf(Prefs.ART_INTERVAL, Prefs.artIntervalMin(this)));
            refreshArt();
        }, false);
        col.addView(artIntervalButton, full());
        col.addView(text("Google Photos shared albums (one link per line)", 12, false));
        albumsField = new EditText(this);
        albumsField.setMinLines(2);
        albumsField.setMaxLines(6);
        albumsField.setHint("https://photos.app.goo.gl/…");
        albumsField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        albumsField.setTextColor(getColor(R.color.text));
        albumsField.setHintTextColor(getColor(R.color.text_dim));
        albumsField.setText(Prefs.photoAlbums(this));
        col.addView(albumsField);
        col.addView(note("In Google Photos: open the album → Share → Create link → copy. Paste it here (email it to "
                + "yourself and copy it in this device's browser). New photos in the album appear within a few hours. "
                + "Unofficial: Google could change their page and break this."));
        LinearLayout albumRow = row();
        albumRow.addView(button("Paste", v -> {
            String clip = clipboardText();
            if (clip.isEmpty()) return;
            String cur = albumsField.getText().toString().trim();
            albumsField.setText(cur.isEmpty() ? clip.trim() : cur + "\n" + clip.trim());
        }, false), weighted());
        albumRow.addView(button("Save & check albums", v -> checkAlbums(), true), weighted());
        col.addView(albumRow);
        albumsStatus = note("");
        col.addView(albumsStatus);
        collectionButton = button("", v -> {
            java.util.List<String> all = ArtActivity.collections(this);
            int i = all.indexOf(ArtActivity.currentCollection(this));
            Prefs.setArtCollection(this, all.get((i + 1) % all.size()));
            refreshArt();
        }, false);
        col.addView(collectionButton, full());
        col.addView(note("On the frame: ← → change the picture, ↑ ↓ switch between your albums and the paintings."));
        col.addView(button("Show art now", v -> startActivity(new Intent(this, ArtActivity.class)), true), full());
        col.addView(button("Gestures & buttons (teach what each one does)",
                v -> startActivity(new Intent(this, GestureSetupActivity.class)), true), full());
        col.addView(button("Sensor details (for troubleshooting)",
                v -> startActivity(new Intent(this, GestureTestActivity.class)), false), full());
        String sensor = Presence.describe(this);
        col.addView(note(sensor.isEmpty()
                ? "No proximity sensor found on this device: touch, a key or the mouse wakes the dashboard."
                : "Proximity sensor found (" + sensor + "): coming close wakes the dashboard."));
        refreshArt();

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
        RemoteMic.get(this).startServer();
        main.post(remoteTick);
        homeButton.setText(FridgeHome.isFridgeDashDefault(this)
                ? "Home button opens: FridgeDash  (tap to change)"
                : "Home button opens: Hisense  (tap to make it FridgeDash)");
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(remoteTick);
        // Save typed values when leaving.
        Prefs.setApiKey(this, keyField.getText().toString());
        Prefs.setModel(this, modelField.getText().toString());
        Prefs.setExtraInstructions(this, extraField.getText().toString());
        Prefs.setPhotoAlbums(this, albumsField.getText().toString());
        Prefs.setHomeCity(this, cityField.getText().toString());
        Prefs.setBriefUrl(this, briefUrlField.getText().toString());
        Prefs.setBriefToken(this, briefTokenField.getText().toString());
        Prefs.setNotionToken(this, notionField.getText().toString());
    }

    private String clipboardText() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cb == null ? null : cb.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return "";
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        return t == null ? "" : t.toString().trim();
    }

    private void testNotionWrite() {
        notionStatus.setText("Testing…");
        final Context app = getApplicationContext();
        bg.execute(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                Brief b = Notion.fetch(app);
                Brief.Item target = null;
                for (Brief.Section sct : b.sections) for (Brief.Item it : sct.items)
                    if (target == null && !it.done) target = it;
                if (target == null) {
                    sb.append("No open item to test with.");
                } else {
                    sb.append("Item: ").append(target.title).append("\n");
                    Notion.setDone(app, target.pageId, true);
                    sb.append("✓ Ticked Done. ");
                    Brief after = Notion.fetch(app);
                    boolean seen = false;
                    for (Brief.Section sct : after.sections) for (Brief.Item it : sct.items)
                        if (it.pageId.equals(target.pageId) && it.done) seen = true;
                    sb.append(seen ? "Notion confirms it's Done. " : "But Notion still shows it open! ");
                    Notion.setDone(app, target.pageId, false);
                    sb.append("✓ Unticked again.");
                }
            } catch (Throwable e) {
                sb.append("✗ ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
            }
            final String m = sb.toString();
            main.post(() -> { if (!isDestroyed()) notionStatus.setText(m); });
        });
    }

    private void connectNotion() {
        final String token = notionField.getText().toString().trim();
        if (token.isEmpty()) {
            notionStatus.setText("Paste the Notion key first.");
            return;
        }
        Prefs.setNotionToken(this, token);
        notionStatus.setText("Connecting to Notion…");
        final Context app = getApplicationContext();
        bg.execute(() -> {
            String msg;
            try {
                Notion.ConnectResult r = Notion.connect(token);
                Notion.saveConnection(app, r);
                int imported = 0;
                if (r.created) {
                    Brief file = Brief.cached(app);
                    if (file != null && !file.live) imported = Notion.importBrief(token, r.dbId, file);
                }
                Brief b = Notion.fetch(app);
                int open = 0;
                for (Brief.Section sct : b.sections) for (Brief.Item it : sct.items) if (!it.done) open++;
                msg = "✓ Connected to \"" + r.dbTitle + "\""
                        + (r.created ? " (new, in \"" + r.parentTitle + "\""
                            + (imported > 0 ? ", imported " + imported + " items" : "") + ")" : "")
                        + ". " + open + " open items in " + b.sections.size() + " sections.\n"
                        + "Columns: " + r.cols.describe() + "."
                        + (r.addedDone ? "\nAdded a \"Done\" checkbox column; tell Grok to use it." : "");
            } catch (Exception e) {
                msg = "✗ " + e.getMessage();
            }
            final String m = msg;
            main.post(() -> { if (!isDestroyed()) notionStatus.setText(m); });
        });
    }

    private void saveAndTestBrief() {
        Prefs.setBriefUrl(this, briefUrlField.getText().toString());
        Prefs.setBriefToken(this, briefTokenField.getText().toString());
        Prefs.raw(this).edit().remove("brief_etag").apply();
        briefStatus.setText("Loading…");
        final Context app = getApplicationContext();
        bg.execute(() -> {
            String msg;
            try {
                Brief b = Brief.fetch(app);
                if (b == null) b = Brief.cached(app);
                int items = 0;
                if (b != null) for (Brief.Section sct : b.sections) items += sct.items.size();
                msg = b == null ? "Nothing loaded." : "✓ Loaded the brief for " + (b.date.isEmpty() ? "?" : b.date)
                        + ": " + b.sections.size() + " sections, " + items + " items.";
            } catch (Exception e) {
                msg = "✗ " + e.getMessage();
            }
            final String m = msg;
            main.post(() -> { if (!isDestroyed()) briefStatus.setText(m); });
        });
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

    private void pickVoice() {
        String[] items = new String[Prefs.VOICES.length];
        int checked = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = Prefs.VOICES[i] + " — " + Prefs.VOICE_INFO[i];
            if (Prefs.VOICES[i].equals(Prefs.voice(this))) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Voice (tap one to hear it)")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    Prefs.setVoice(this, Prefs.VOICES[which]);
                    refreshVoice();
                    previewVoice();
                })
                .setPositiveButton("Done", null)
                .show();
    }

    private void pickModel() {
        String[] items = new String[Prefs.MODELS.length];
        int checked = -1;
        String cur = modelField.getText().toString().trim();
        for (int i = 0; i < items.length; i++) {
            items[i] = Prefs.MODELS[i] + " — " + Prefs.MODEL_INFO[i];
            if (Prefs.MODELS[i].equals(cur)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Voice model")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    modelField.setText(Prefs.MODELS[which]);
                    Prefs.setModel(this, Prefs.MODELS[which]);
                    refreshVoice();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean previewing;

    private void previewVoice() {
        if (previewing) return;
        previewing = true;
        Prefs.setModel(this, modelField.getText().toString());
        Prefs.setExtraInstructions(this, extraField.getText().toString());
        voiceStatus.setText("Getting a sample of \"" + Prefs.voice(this) + "\"…");
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String r = VoicePreview.play(app);
            main.post(() -> {
                previewing = false;
                if (!isDestroyed()) voiceStatus.setText(r);
            });
        }, "voice-preview").start();
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

    private void refreshOrient() {
        String o = Prefs.orientation(this);
        orientButton.setText("Screen orientation: " + ("landscape".equals(o) ? "Landscape" : "portrait".equals(o)
                ? "Portrait" : "Automatic") + "   (tap to change)");
    }

    private static int nextOf(int[] options, int cur) {
        for (int i = 0; i < options.length; i++) if (options[i] == cur) return options[(i + 1) % options.length];
        return options[0];
    }

    private void checkAlbums() {
        Prefs.setPhotoAlbums(this, albumsField.getText().toString());
        java.util.List<String> links = Photos.links(this);
        if (links.isEmpty()) {
            albumsStatus.setText(Prefs.photoAlbums(this).isEmpty() ? "No albums." 
                    : "✗ That doesn't look like a Google Photos share link (photos.app.goo.gl/… or photos.google.com/share/…).");
            refreshArt();
            return;
        }
        albumsStatus.setText("Checking " + links.size() + " album(s)…");
        final android.content.Context app = getApplicationContext();
        bg.execute(() -> {
            StringBuilder sb = new StringBuilder();
            for (String l : links) {
                try {
                    Photos.Album a = Photos.album(app, l, true);
                    sb.append("✓ ").append(a.name.isEmpty() ? "Album" : a.name).append(": ")
                            .append(a.items.size()).append(" photos\n");
                } catch (Exception e) {
                    sb.append("✗ ").append(l.length() > 40 ? l.substring(0, 40) + "…" : l).append(": ")
                            .append(e.getMessage()).append('\n');
                }
            }
            main.post(() -> {
                if (isDestroyed()) return;
                albumsStatus.setText(sb.toString().trim());
                refreshArt();
            });
        });
    }

    private void refreshArt() {
        int idle = Prefs.artIdleMin(this), every = Prefs.artIntervalMin(this);
        artIdleButton.setText("Show art when idle: " + (idle == 0 ? "Off" : "after " + idle + " min") + "   (tap to change)");
        artIntervalButton.setText("New picture every: " + every + " min   (tap to change)");
        collectionButton.setText("Showing: " + ArtActivity.collectionName(this, ArtActivity.currentCollection(this))
                + "   (tap to change)");
    }

    private void refreshRemote() {
        RemoteMic rm = RemoteMic.get(this);
        boolean on = Prefs.remoteMicServer(this) && !Prefs.linkEnabled(this);
        remoteButton.setText("Accept a phone as microphone: " + (on ? "ON" : "OFF") + "   (tap to change)");
        String ip = RemoteMic.localAddress();
        if (Prefs.linkEnabled(this)) {
            remoteStatus.setText("This device is set up as a microphone (Fridge Mic), so it doesn't accept one.");
        } else if (!on) {
            remoteStatus.setText("Off.");
        } else if (rm.connected()) {
            remoteStatus.setText("✓ Connected: " + rm.clientName() + " (" + rm.clientAddress() + ")");
        } else {
            remoteStatus.setText("Waiting for a phone…  This fridge's address: "
                    + (ip.isEmpty() ? "no Wi-Fi" : ip) + "  (port " + RemoteMic.PORT + ")");
        }
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
        String l = Prefs.language(this);
        langButton.setText("Language: " + ("sr".equals(l) ? "Always Serbian" : "en".equals(l) ? "Always English"
                : "Automatic (answers in the language you speak)") + "   (tap to change)");
        voiceButton.setText("Voice: " + Prefs.voice(this) + "   (tap to choose)");
        float sp = Prefs.speed(this);
        speedButton.setText("Speed: " + (sp < 0.95f ? "slower" : sp < 1.05f ? "normal" : sp < 1.15f ? "faster" : "fastest"));
        modelButton.setText("Model: " + Prefs.model(this) + "   (tap to choose)");
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
