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
    private Bindings.Watcher gestures;
    private long startedAt;
    private String currentImage = "";
    private boolean loading;
    /** Paintings shown so far (for "previous"), newest last. */
    private final java.util.ArrayList<Art.Piece> history = new java.util.ArrayList<>();
    private int pos = -1;

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
        gestures = new Bindings.Watcher(this, this::perform);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startedAt = System.currentTimeMillis();
        // If the proximity sensor carries learned gestures, waving shouldn't also close art mode.
        if (!Bindings.usesSensorType(this, android.hardware.Sensor.TYPE_PROXIMITY)) presence.start();
        gestures.start();
        main.removeCallbacks(rotate);
        main.post(rotate);
    }

    @Override
    protected void onPause() {
        super.onPause();
        presence.stop();
        gestures.stop();
        main.removeCallbacks(rotate);
        // Art mode is only for idle time: leaving it always returns to the dashboard.
        if (!isFinishing()) finish();
    }

    private void loadNext() {
        if (pos < history.size() - 1) { pos++; loadPiece(history.get(pos), false); return; }
        loadPiece(null, true);
    }

    // ------------------------------------------------------------------ collections (albums + paintings)

    static final String PAINTINGS = "paintings";

    /** Album links in Settings order, then the paintings. */
    static java.util.List<String> collections(android.content.Context c) {
        java.util.List<String> out = new java.util.ArrayList<>(Photos.links(c));
        out.add(PAINTINGS);
        return out;
    }

    static String currentCollection(android.content.Context c) {
        java.util.List<String> all = collections(c);
        String cur = Prefs.artCollection(c);
        return all.contains(cur) ? cur : all.get(0); // default: first album if there is one
    }

    static String collectionName(android.content.Context c, String key) {
        if (PAINTINGS.equals(key)) return "Famous paintings";
        java.util.List<String> links = Photos.links(c);
        java.util.List<String> names = Photos.cachedNames(c);
        int i = links.indexOf(key);
        return i >= 0 && i < names.size() ? names.get(i) : "Album";
    }

    /** Up/down: next or previous collection. */
    private void switchCollection(int dir) {
        java.util.List<String> all = collections(this);
        if (all.size() < 2) { caption.setText("Only one collection. Add Google Photos albums in Settings → Art mode."); return; }
        int i = all.indexOf(currentCollection(this));
        String next = all.get(((i + dir) % all.size() + all.size()) % all.size());
        Prefs.setArtCollection(this, next);
        history.clear();
        pos = -1;
        caption.setText("▸ " + collectionName(this, next) + " …");
        loadPiece(null, true);
    }

    private void loadPrevious() {
        if (pos > 0) { pos--; loadPiece(history.get(pos), false); }
    }

    /** Loads the given painting, or a new random one (added to the history). */
    private void loadPiece(Art.Piece wanted, boolean addToHistory) {
        if (loading) return;
        loading = true;
        final int w = Math.max(root.getWidth(), 800), h = Math.max(root.getHeight(), 800);
        final android.content.Context app = getApplicationContext();
        final String avoid = currentImage;
        new Thread(() -> {
            Object[] r = null;
            String err = null;
            try {
                if (wanted == null) {
                    String col = currentCollection(app);
                    if (PAINTINGS.equals(col)) {
                        r = Art.next(app, w, h, avoid);
                    } else {
                        Photos.Album album = Photos.album(app, col, false);
                        r = Art.pickFrom(album.items, w, h, avoid);
                    }
                } else {
                    StringBuilder errs = new StringBuilder();
                    Bitmap b = Art.load(wanted, w, h, errs);
                    if (b != null) r = new Object[]{wanted, b};
                    else err = errs.toString();
                }
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
                    if (history.isEmpty()) caption.setText("Couldn't load: " + fe + " (tap to go back)");
                    return;
                }
                if (addToHistory) {
                    history.add((Art.Piece) fr[0]);
                    if (history.size() > 30) history.remove(0);
                    pos = history.size() - 1;
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
        StringBuilder c = new StringBuilder(p.title.isEmpty() ? p.museum : p.title); // photos: album name
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        String bound = Bindings.actionForKey(this, ev);
        if (bound != null) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) perform(bound);
            return true;
        }
        // Arrow keys (and gestures, once mapped to them) flip through paintings without leaving art mode.
        int k = ev.getKeyCode();
        boolean next = k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_PAGE_DOWN || k == KeyEvent.KEYCODE_MEDIA_NEXT;
        boolean prev = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_PAGE_UP || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        if (up || down) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                startedAt = System.currentTimeMillis();
                main.removeCallbacks(rotate);
                switchCollection(down ? 1 : -1);
                main.postDelayed(rotate, Prefs.artIntervalMin(this) * 60_000L);
            }
            return true;
        }
        if (next || prev) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                startedAt = System.currentTimeMillis();
                main.removeCallbacks(rotate);
                if (next) loadNext(); else loadPrevious();
                main.postDelayed(rotate, Prefs.artIntervalMin(this) * 60_000L);
            }
            return true;
        }
        wake();
        return true;
    }

    /** A learned button or gesture (Settings → Gestures & buttons). */
    private void perform(String action) {
        startedAt = System.currentTimeMillis();
        switch (action) {
            case Bindings.NEXT: case Bindings.PREV:
                main.removeCallbacks(rotate);
                if (Bindings.NEXT.equals(action)) loadNext(); else loadPrevious();
                main.postDelayed(rotate, Prefs.artIntervalMin(this) * 60_000L);
                break;
            case Bindings.ALBUM_NEXT: case Bindings.ALBUM_PREV:
                main.removeCallbacks(rotate);
                switchCollection(Bindings.ALBUM_NEXT.equals(action) ? 1 : -1);
                main.postDelayed(rotate, Prefs.artIntervalMin(this) * 60_000L);
                break;
            case Bindings.DASHBOARD:
                startedAt = 0;
                wake();
                break;
            case Bindings.CHATGPT:
                startActivity(new android.content.Intent(this, VoiceActivity.class));
                finish();
                break;
            default:
                break; // "Show art": already showing
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        String dir = Bindings.motionDir(ev);
        String bound = dir == null ? null : Bindings.actionForMotion(this, ev, dir);
        if (bound != null) { perform(bound); return true; }
        // Mouse movement; ignore the first moments so the click that opened art mode doesn't close it.
        if (System.currentTimeMillis() - startedAt > 1500) wake();
        return true;
    }
}
