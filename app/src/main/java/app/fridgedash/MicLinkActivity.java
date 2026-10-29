package app.fridgedash;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Fridge Mic" (runs on the phone): turns this phone into the fridge's
 * microphone. Tap Talk here, or the ChatGPT button on the fridge, then speak
 * to the phone; ChatGPT answers through the fridge.
 */
public class MicLinkActivity extends BaseActivity {

    private static final int REQ_MIC = 21;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout col;
    private TextView status, meter, talkButton, onOffButton;
    private EditText hostField;
    private float peak;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.bg));
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(col);
        setContentView(scroll);

        col.addView(text("Fridge Mic", 24, true));
        col.addView(note("Use this phone as the fridge's microphone. Tap Talk (here, in the notification, "
                + "or the ChatGPT button on the fridge), then speak to the phone. ChatGPT answers out loud "
                + "on the fridge. The phone only sends sound while a call is open on the fridge."));

        status = text("", 14, false);
        status.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        col.addView(status);
        meter = text("", 12, false);
        meter.setTypeface(Typeface.MONOSPACE);
        meter.setTextColor(getColor(R.color.text_dim));
        col.addView(meter);

        talkButton = button("Talk to the fridge", v -> talk(), true);
        talkButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        talkButton.setMinHeight(Ui.dp(this, 96));
        col.addView(talkButton, full());

        onOffButton = button("", v -> toggle(), false);
        col.addView(onOffButton, full());

        col.addView(section("Fridge address (optional)"));
        col.addView(note("Leave empty and the phone finds the fridge by itself. If it can't, type the address "
                + "shown on the fridge in Settings → Phone as microphone."));
        hostField = new EditText(this);
        hostField.setSingleLine(true);
        hostField.setHint("e.g. 192.168.1.40");
        hostField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostField.setTextColor(getColor(R.color.text));
        hostField.setHintTextColor(getColor(R.color.text_dim));
        hostField.setText(Prefs.linkHost(this));
        col.addView(hostField);
        col.addView(button("Save address", v -> saveHost(), false), full());

        col.addView(button("Close", v -> finish(), false), full());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.linkEnabled(this) && hasMic()) MicLinkService.start(this);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    private void talk() {
        if (!Prefs.linkEnabled(this)) { toggle(); return; }
        if (MicLinkService.sending) {
            MicLinkService.send(this, MicLinkService.ACTION_END);
        } else if (!MicLinkService.connected) {
            Toast.makeText(this, "Not connected to the fridge yet", Toast.LENGTH_SHORT).show();
        } else {
            MicLinkService.send(this, MicLinkService.ACTION_TALK);
        }
    }

    private void toggle() {
        if (Prefs.linkEnabled(this)) {
            Prefs.setLinkEnabled(this, false);
            MicLinkService.send(this, MicLinkService.ACTION_STOP);
        } else if (!hasMic()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        } else {
            Prefs.setLinkEnabled(this, true);
            RemoteMic.get(this).stopServer(); // this device is the microphone, not the fridge
            MicLinkService.start(this);
        }
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            toggle();
        } else {
            Toast.makeText(this, "The phone needs microphone access to be the fridge's microphone",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveHost() {
        Prefs.setLinkHost(this, hostField.getText().toString());
        Toast.makeText(this, Prefs.linkHost(this).isEmpty() ? "Will find the fridge automatically"
                : "Saved. Reconnecting…", Toast.LENGTH_SHORT).show();
        if (Prefs.linkEnabled(this)) MicLinkService.send(this, MicLinkService.ACTION_RECONNECT);
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        boolean on = Prefs.linkEnabled(this);
        onOffButton.setText(on ? "Phone mic for the fridge: ON   (tap to turn off)"
                : "Phone mic for the fridge: OFF   (tap to turn on)");
        if (!on) {
            status.setText("Off. Turn it on below.");
        } else if (MicLinkService.sending) {
            String s = MicLinkService.fridgeState;
            status.setText("speaking".equals(s) ? "ChatGPT is answering on the fridge…"
                    : "thinking".equals(s) ? "Thinking…"
                    : "Fridge is listening — talk to the phone");
        } else {
            status.setText(MicLinkService.status);
        }
        talkButton.setText(MicLinkService.sending ? "End call" : "Talk to the fridge");
        talkButton.setAlpha(on && MicLinkService.connected ? 1f : 0.5f);

        float lv = MicLinkService.level;
        peak = Math.max(lv, peak * 0.8f);
        int bars = Math.min(20, Math.round(peak * 20));
        StringBuilder b = new StringBuilder("mic ");
        for (int i = 0; i < 20; i++) b.append(i < bars ? '█' : '·');
        meter.setText(MicLinkService.sending ? b : "");
    }

    // ------------------------------------------------------------------ view helpers

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(), 11, true);
        t.setTextColor(0xFFF5B14C);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 6));
        return t;
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
        TextView t = text(s, 11, false);
        t.setTextColor(getColor(R.color.text_dim));
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        return t;
    }

    private TextView button(String s, View.OnClickListener l, boolean primary) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(getColor(R.color.text));
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(primary ? R.drawable.tile_primary : R.drawable.tile);
        int p = Ui.dp(this, 12);
        b.setPadding(p, p, p, p);
        b.setMinHeight(Ui.dp(this, 48));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        return lp;
    }
}
