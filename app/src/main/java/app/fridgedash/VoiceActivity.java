package app.fridgedash;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Talk to ChatGPT. Two ways in:
 *  - Microphone: a hands-free voice call.
 *  - Keyboard (for fridges without a working mic): type, and ChatGPT answers out loud.
 * Leaving the screen ends the session.
 */
public class VoiceActivity extends BaseActivity implements VoiceSession.Listener {

    private static final int REQ_MIC = 7;

    /** The open call screen, so a phone on the network can start or end a call. */
    private static java.lang.ref.WeakReference<VoiceActivity> current;

    static boolean isOpen() {
        VoiceActivity a = current == null ? null : current.get();
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    static void finishCurrent() {
        VoiceActivity a = current == null ? null : current.get();
        if (a != null && !a.isFinishing()) a.finish();
    }

    private boolean remoteMic;

    private VoiceSession session;
    private TextView status, transcript, micMeter;
    private float meterPeak;
    private ScrollView transcriptScroll;
    private OrbView orb;
    private ImageView muteIcon;
    private EditText input;
    private boolean keyboard;
    private boolean endedWithError;
    private float loudest;          // loudest mic level heard this call
    private boolean heardSpeech;    // the server detected speech at least once
    private boolean askingPermission; // the permission dialog pauses us; don't hang up for that

    /** Conversation shown on screen: alternating "you" / "ChatGPT" turns. */
    private final List<String[]> turns = new ArrayList<>(); // {who, text}

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable silenceHint = () -> {
        if (session != null && !heardSpeech && !session.isMuted() && !keyboard) {
            status.setText(remoteMic
                    ? "Nothing heard yet. Speak toward the phone, or check Fridge Mic on it."
                    : loudest < 0.02f
                    ? "I can't hear the microphone at all. Try Settings → Test microphone, or switch to Keyboard."
                    : "Hearing sound but no speech yet. Try speaking closer to the screen.");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        current = new java.lang.ref.WeakReference<>(this);

        status = findViewById(R.id.status);
        micMeter = findViewById(R.id.micMeter);
        transcript = findViewById(R.id.transcript);
        transcriptScroll = findViewById(R.id.transcriptScroll);
        orb = findViewById(R.id.orb);
        muteIcon = findViewById(R.id.muteIcon);
        input = findViewById(R.id.messageInput);

        Bitmap b = Glass.backdrop();
        if (b != null) ((ImageView) findViewById(R.id.backdrop)).setImageBitmap(b);

        keyboard = Prefs.useKeyboard(this);
        if (keyboard) {
            findViewById(R.id.inputRow).setVisibility(View.VISIBLE);
            findViewById(R.id.btnMute).setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = orb.getLayoutParams();
            lp.width = lp.height = Ui.dp(this, 170);
            orb.setLayoutParams(lp);
            transcript.setGravity(android.view.Gravity.START);
            findViewById(R.id.btnSend).setOnClickListener(v -> sendTyped());
            input.setOnEditorActionListener((v, actionId, ev) -> {
                boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && ev.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                    sendTyped();
                    return true;
                }
                return false;
            });
        }

        findViewById(R.id.btnEnd).setOnClickListener(v -> finish());
        findViewById(R.id.btnMute).setOnClickListener(v -> toggleMute());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Glass.setRoot(findViewById(R.id.root));
        if (session == null && !endedWithError && !askingPermission) begin();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Never keep the microphone or the connection open in the background.
        if (askingPermission) return;
        endSession();
        if (!endedWithError) finish();
    }

    @Override
    protected void onDestroy() {
        if (current != null && current.get() == this) current = null;
        endSession();
        super.onDestroy();
    }

    private void begin() {
        String key = Prefs.apiKey(this);
        if (key.isEmpty()) {
            Toast.makeText(this, "Add your OpenAI API key in Settings first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }
        remoteMic = !keyboard && RemoteMic.get(this).connected();
        if (!keyboard && !remoteMic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Allow microphone access to talk");
            askingPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        session = new VoiceSession(key, Prefs.model(this), Prefs.voice(this), Prefs.bargeIn(this),
                Prefs.micSource(this), !keyboard, this, this);
        session.start();
    }

    private void endSession() {
        ui.removeCallbacks(silenceHint);
        if (session != null) {
            session.stop();
            session = null;
        }
    }

    private void sendTyped() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (session == null || !session.sendText(text)) {
            Toast.makeText(this, "Still connecting, try again in a moment", Toast.LENGTH_SHORT).show();
            return;
        }
        input.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        turns.add(new String[]{"You", text});
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_MIC) return;
        askingPermission = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            begin();
        } else {
            endedWithError = true;
            status.setText("Microphone permission denied. You can allow it in Android settings → Apps → FridgeDash.");
        }
    }

    private void toggleMute() {
        if (session == null) return;
        boolean m = !session.isMuted();
        session.setMuted(m);
        muteIcon.setImageResource(m ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        findViewById(R.id.btnMute).setContentDescription(m ? "Unmute microphone" : "Mute microphone");
        if (m) status.setText("Muted");
    }

    private void render() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String[] t : turns) {
            if (sb.length() > 0) sb.append("\n\n");
            if (keyboard) {
                int start = sb.length();
                sb.append(t[0]).append("\n");
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan("You".equals(t[0]) ? 0xFF5ED3C4 : 0xFF9FB4FF),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append(t[1]);
        }
        transcript.setText(sb);
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ------------------------------------------------------------------ VoiceSession.Listener

    @Override
    public void onState(VoiceSession.State s) {
        if (session != null && session.isMuted() && s == VoiceSession.State.LISTENING) {
            status.setText("Muted");
            return;
        }
        switch (s) {
            case CONNECTING:
                status.setText("Connecting…");
                orb.setMode(OrbView.MODE_THINKING);
                break;
            case LISTENING:
                if (keyboard) {
                    status.setText("Type a question below");
                } else {
                    status.setText(remoteMic ? "Listening — talk to the phone" : "Listening — go ahead");
                    if (!heardSpeech) { ui.removeCallbacks(silenceHint); ui.postDelayed(silenceHint, 12000); }
                }
                orb.setMode(OrbView.MODE_IDLE);
                break;
            case HEARING:
                heardSpeech = true;
                ui.removeCallbacks(silenceHint);
                status.setText("Listening…");
                orb.setMode(OrbView.MODE_USER);
                break;
            case THINKING:
                status.setText("Thinking…");
                orb.setMode(OrbView.MODE_THINKING);
                break;
            case SPEAKING:
                status.setText("Speaking");
                orb.setMode(OrbView.MODE_ASSISTANT);
                break;
            case ENDED:
                if (!endedWithError) status.setText("Ended");
                orb.setMode(OrbView.MODE_IDLE);
                break;
        }
    }

    @Override
    public void onAssistantText(String fullTextSoFar, boolean newTurn) {
        if (keyboard) {
            if (newTurn || turns.isEmpty() || !"ChatGPT".equals(turns.get(turns.size() - 1)[0])) {
                turns.add(new String[]{"ChatGPT", fullTextSoFar});
            } else {
                turns.get(turns.size() - 1)[1] = fullTextSoFar;
            }
            render();
        } else {
            transcript.setText(fullTextSoFar);
            transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    public void onMicLevel(float level, boolean paused) {
        meterPeak = Math.max(level, meterPeak * 0.85f);
        int bars = Math.min(20, Math.round(meterPeak * 20));
        StringBuilder b = new StringBuilder(remoteMic ? "phone mic " : "mic ");
        for (int i = 0; i < 20; i++) b.append(i < bars ? '█' : '·');
        b.append(String.format(java.util.Locale.US, " %3d%%", Math.round(level * 100)));
        if (paused) b.append("  (paused)");
        micMeter.setText(b);
    }

    @Override
    public void onLevel(float level) {
        loudest = Math.max(loudest, level);
        orb.setLevel(level);
    }

    @Override
    public void onError(String message, boolean fatal) {
        if (fatal) {
            endedWithError = true;
            status.setText(message);
            orb.setMode(OrbView.MODE_IDLE);
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }
}
