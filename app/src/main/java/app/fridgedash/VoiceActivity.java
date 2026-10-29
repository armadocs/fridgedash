package app.fridgedash;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Full-screen voice call with ChatGPT. Leaving the screen ends the call. */
public class VoiceActivity extends BaseActivity implements VoiceSession.Listener {

    private static final int REQ_MIC = 7;

    private VoiceSession session;
    private TextView status, transcript;
    private ScrollView transcriptScroll;
    private OrbView orb;
    private ImageView muteIcon;
    private boolean endedWithError;
    private boolean askingPermission; // the permission dialog pauses us; don't hang up for that

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        status = findViewById(R.id.status);
        transcript = findViewById(R.id.transcript);
        transcriptScroll = findViewById(R.id.transcriptScroll);
        orb = findViewById(R.id.orb);
        muteIcon = findViewById(R.id.muteIcon);

        Bitmap b = Glass.backdrop();
        if (b != null) ((ImageView) findViewById(R.id.backdrop)).setImageBitmap(b);

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
        // Never keep the microphone open in the background.
        if (askingPermission) return;
        endSession();
        if (!endedWithError) finish();
    }

    @Override
    protected void onDestroy() {
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Allow microphone access to talk");
            askingPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        transcript.setText("");
        session = new VoiceSession(key, Prefs.model(this), Prefs.voice(this), Prefs.bargeIn(this), this);
        session.start();
    }

    private void endSession() {
        if (session != null) {
            session.stop();
            session = null;
        }
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
                status.setText("Listening — go ahead");
                orb.setMode(OrbView.MODE_IDLE);
                break;
            case HEARING:
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
                if (!endedWithError) status.setText("Call ended");
                orb.setMode(OrbView.MODE_IDLE);
                break;
        }
    }

    @Override
    public void onAssistantText(String fullTextSoFar, boolean newTurn) {
        transcript.setText(fullTextSoFar);
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onLevel(float level) {
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
