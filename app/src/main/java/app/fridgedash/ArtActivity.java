package app.fridgedash;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * Art mode: a famous painting fills the screen while nobody is around.
 * Any touch, key, mouse movement or the proximity sensor brings the dashboard back.
 */
public class ArtActivity extends BaseActivity implements Presence.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private final ImageView[] mattes = new ImageView[2];
    private final ImageView[] arts = new ImageView[2];
    private int front;
    private TextView caption;
    private Presence presence;
    private long startedAt;
    private String currentImage = "";
    private boolean loading;

    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            loadNext();
            main.postDelayed(this, Prefs.artIntervalMin(ArtActivity.this) * 60_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0D);
        for (int i = 0; i < 2; i++) {
            mattes[i] = new ImageView(this);
            mattes[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            mattes[i].setColorFilter(0x99000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
            mattes[i].setAlpha(0f);
            root.addView(mattes[i], new FrameLayout.LayoutParams(-1, -1));
        }
        for (int i = 0; i < 2; i++) {
            arts[i] = new ImageView(this);
            arts[i].setScaleType(ImageView.ScaleType.FIT_CENTER);
            arts[i].setAdjustViewBounds(false);
            int pad = Ui.dp(this, 26);
            arts[i].setPadding(pad, pad, pad, Ui.dp(this, 48));
            arts[i].setElevation(Ui.dp(this, 8));
            arts[i].setAlpha(0f);
            root.addView(arts[i], new FrameLayout.LayoutParams(-1, -1));
        }

        // Caption and a small clock, low-key along the bottom.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 14));
        caption = new TextView(this);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        caption.setTextColor(0xCCFFFFFF);
        caption.setShadowLayer(6f, 0f, 1f, 0xCC000000); // readable over a full-screen painting
        caption.setMaxLines(1);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setText("Loading a painting…");
        bar.addView(caption, new LinearLayout.LayoutParams(0, -2, 1f));
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("H:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setTextColor(0xDDFFFFFF);
        clock.setShadowLayer(6f, 0f, 1f, 0xCC000000);
        bar.addView(clock);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        hideSystemBars();
        presence = new Presence(this, this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startedAt = System.currentTimeMillis();
        presence.start();
        main.removeCallbacks(rotate);
        main.post(rotate);
    }

    @Override
    protected void onPause() {
        super.onPause();
        presence.stop();
        main.removeCallbacks(rotate);
        // Art mode is only for idle time: leaving it always returns to the dashboard.
        if (!isFinishing()) finish();
    }

    private void loadNext() {
        if (loading) return;
        loading = true;
        final int w = Math.max(root.getWidth(), 800), h = Math.max(root.getHeight(), 800);
        final android.content.Context app = getApplicationContext();
        final String avoid = currentImage;
        new Thread(() -> {
            Object[] r = null;
            String err = null;
            try {
                r = Art.next(app, w, h, avoid);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final Object[] fr = r;
            final String fe = err;
            final Bitmap matte = fr == null ? null : Art.matte((Bitmap) fr[1]);
            main.post(() -> {
                loading = false;
                if (isDestroyed()) return;
                if (fr == null) {
                    if (currentImage.isEmpty()) caption.setText("Couldn't load art: " + fe + " (tap to go back)");
                    return;
                }
                show((Art.Piece) fr[0], (Bitmap) fr[1], matte);
            });
        }, "art").start();
    }

    private void show(Art.Piece p, Bitmap bmp, Bitmap matte) {
        int back = 1 - front;
        // Shaped like the screen: fill it edge to edge (a little trimmed). Otherwise show it whole on a matte.
        double screen = root.getWidth() / (double) Math.max(1, root.getHeight());
        boolean fill = Art.fits(bmp.getWidth() / (double) bmp.getHeight(), screen);
        arts[back].setScaleType(fill ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        int pad = fill ? 0 : Ui.dp(this, 26);
        arts[back].setPadding(pad, pad, pad, fill ? 0 : Ui.dp(this, 48));
        arts[back].setImageBitmap(bmp);
        mattes[back].setImageBitmap(matte);
        arts[back].animate().alpha(1f).setDuration(1600).start();
        mattes[back].animate().alpha(1f).setDuration(1600).start();
        arts[front].animate().alpha(0f).setDuration(1600).start();
        mattes[front].animate().alpha(0f).setDuration(1600).start();
        front = back;
        currentImage = p.image;
        StringBuilder c = new StringBuilder(p.title);
        if (!p.artist.isEmpty()) c.append(" · ").append(p.artist);
        if (!p.date.isEmpty()) c.append(", ").append(p.date);
        caption.setText(c);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ------------------------------------------------------------------ wake up

    private void wake() {
        if (!isFinishing()) {
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    @Override
    public void onSomeoneNear() {
        if (System.currentTimeMillis() - startedAt > 3000) wake(); // ignore the sensor's first reading
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) { wake(); return true; }

    @Override public boolean dispatchKeyEvent(KeyEvent ev) { wake(); return true; }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        // Mouse movement; ignore the first moments so the click that opened art mode doesn't close it.
        if (System.currentTimeMillis() - startedAt > 1500) wake();
        return true;
    }
}
