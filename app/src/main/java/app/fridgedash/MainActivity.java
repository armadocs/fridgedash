package app.fridgedash;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The home screen: clock, today's brief, and the dock with the ChatGPT button. */
public class MainActivity extends BaseActivity {

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private View root;
    private ImageView wallpaper;
    private TextView creditText;
    private ImageView wifiIcon;
    private UpdateFlow updates;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;
    private int loadedW, loadedH;
    private BriefView briefView;
    private Brief brief;
    private boolean briefBusy;
    private static final long BRIEF_REFRESH_MS = 2 * 60 * 1000;
    private final Runnable briefTimer = new Runnable() {
        @Override public void run() {
            refreshBrief();
            main.postDelayed(this, BRIEF_REFRESH_MS);
        }
    };
    private boolean wallpaperBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        wallpaper = findViewById(R.id.wallpaper);
        creditText = findViewById(R.id.creditText);
        wifiIcon = findViewById(R.id.wifiIcon);

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.dockFridge).setOnClickListener(v -> FridgeHome.open(this));
        findViewById(R.id.dockHome).setOnClickListener(v -> openHomeAssistant());
        findViewById(R.id.dockTalk).setOnClickListener(v ->
                startActivity(new Intent(this, VoiceActivity.class)));
        findViewById(R.id.dockCalendar).setOnClickListener(v ->
                Ui.start(this, new Intent(Intent.ACTION_VIEW, Uri.parse("https://calendar.google.com/calendar/r/day"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        findViewById(R.id.dockApps).setOnClickListener(v ->
                startActivity(new Intent(this, AppsActivity.class)));

        briefView = new BriefView(this, findViewById(R.id.todayColumn));
        briefView.setListener(this::syncToggle);

        View bannerBox = findViewById(R.id.updateBannerBox);
        updates = new UpdateFlow(this, bg, findViewById(R.id.updateBanner), bannerBox, null);

        // Size is known after the first layout; load the wallpaper then (and when it changes).
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (v.getWidth() != loadedW || v.getHeight() != loadedH) loadWallpaper();
        });

        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        watchNetwork();
        Tools.warmUp(this);                // finds the home city once, for weather questions
        RemoteMic.get(this).startServer(); // lets a phone be the microphone (Settings → Phone as microphone)
    }

    @Override
    protected void onResume() {
        super.onResume();
        Glass.setRoot(root);
        updateNetwork();
        updates.autoCheck();
        if (loadedW > 0) loadWallpaper(); // picks up a setting change
        if (Wallpaper.refreshDue(this)) refreshWallpaper();
        showBrief(null);
        main.removeCallbacks(briefTimer);
        main.post(briefTimer);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(briefTimer);
    }

    // ------------------------------------------------------------------ daily brief

    private void showBrief(String status) {
        if (brief == null) brief = Brief.cached(this);
        if (brief != null) {
            briefView.show(brief, status);
        } else if (!Brief.configured(this)) {
            briefView.showMessage("Today", "Your daily brief will appear here. Connect it in Settings → Daily brief.");
        } else {
            briefView.showMessage("Today", status != null ? status : "Loading your daily brief…");
        }
    }

    /** Writes a check-off to Notion; on failure, undoes it on screen. */
    private void syncToggle(Brief b, Brief.Item it, boolean done) {
        if (!Notion.configured(this)) return;
        if (it.pageId.isEmpty()) {
            // Still showing the old file-based brief: say so instead of failing silently.
            b.setDone(this, it, !done);
            briefView.show(b, "loading from Notion…");
            Toast.makeText(this, "Still loading your list from Notion. Try again in a moment.", Toast.LENGTH_LONG).show();
            brief = null;
            refreshBrief();
            return;
        }
        final android.content.Context app = getApplicationContext();
        bg.execute(() -> {
            String err = null;
            try {
                Notion.setDone(app, it.pageId, done);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(Brief.cacheFile(app))) {
                    out.write(b.toJson().getBytes("UTF-8"));
                }
            } catch (Throwable e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final String e = err;
            if (e == null) main.post(() -> {
                if (!isDestroyed()) Toast.makeText(this, done ? "✓ Done in Notion" : "Reopened in Notion", Toast.LENGTH_SHORT).show();
            });
            if (e != null) main.post(() -> {
                if (isDestroyed()) return;
                b.setDone(app, it, !done);
                briefView.show(b, "not saved");
                Toast.makeText(this, "Couldn't update Notion: " + e, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void refreshBrief() {
        if (briefBusy || !Brief.configured(this)) return;
        briefBusy = true;
        final android.content.Context app = getApplicationContext();
        bg.execute(() -> {
            Brief fresh = null;
            String err = null;
            try {
                fresh = Brief.load(app);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final Brief f = fresh;
            final String e = err;
            main.post(() -> {
                briefBusy = false;
                if (isDestroyed()) return;
                if (f != null) brief = f;
                String when = new java.text.SimpleDateFormat("H:mm", java.util.Locale.US).format(new java.util.Date());
                showBrief(e == null ? "updated " + when
                        : (brief != null ? "offline copy (" + e + ")" : "Couldn't load: " + e));
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cm != null && netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }
        bg.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        // This is a home screen: Back does nothing rather than closing it.
    }

    private void openHomeAssistant() {
        for (String pkg : Apps.PKG_HOME_ASSISTANT) {
            AppEntry e = Apps.entryForPackage(getPackageManager(), pkg, null);
            if (e != null) { Ui.start(this, e.intent); return; }
        }
        Toast.makeText(this, "Home Assistant app isn't installed", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ wallpaper

    private void loadWallpaper() {
        final int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0 || wallpaperBusy) return;
        wallpaperBusy = true;
        final Context app = getApplicationContext();
        bg.execute(() -> {
            Wallpaper.Images imgs = null;
            try {
                imgs = Wallpaper.load(app, w, h);
            } catch (Throwable ignored) {
                // Out of memory or a bad file: keep the plain background.
            }
            final Wallpaper.Images fImgs = imgs;
            main.post(() -> {
                wallpaperBusy = false;
                if (isDestroyed() || fImgs == null || fImgs.sharp == null) return;
                loadedW = w;
                loadedH = h;
                wallpaper.setImageBitmap(fImgs.sharp);
                Glass.setBackdrop(fImgs.blurred);
                showCredit(fImgs.credit);
            });
        });
    }

    private void refreshWallpaper() {
        final Context app = getApplicationContext();
        bg.execute(() -> {
            boolean changed;
            try {
                changed = Wallpaper.refresh(app);
            } catch (Exception e) {
                changed = false; // offline or Bing unreachable: keep yesterday's photo
            }
            if (changed) main.post(this::loadWallpaper);
        });
    }

    private void showCredit(String credit) {
        if (credit == null || credit.isEmpty()) {
            creditText.setText(Prefs.dailyWallpaper(this) ? "Photo of the day loads with Wi-Fi" : "FridgeDash");
        } else {
            // "Title · Place (© Photographer/Agency)": keep it short for the chip.
            int paren = credit.indexOf(" (©");
            creditText.setText(paren > 0 ? credit.substring(0, paren) : credit);
        }
    }

    // ------------------------------------------------------------------ network

    private void watchNetwork() {
        if (cm == null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                main.post(() -> {
                    updateNetwork();
                    refreshBrief();
                    if (Wallpaper.refreshDue(MainActivity.this)) refreshWallpaper();
                });
            }
            @Override public void onLost(Network n) { main.post(MainActivity.this::updateNetwork); }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
    }

    private void updateNetwork() {
        if (cm == null) return;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        boolean online = caps != null;
        wifiIcon.setColorFilter(online ? 0xFF5ED3C4 : 0xFFF0A44B);
        wifiIcon.setAlpha(online ? 1f : 0.6f);
        wifiIcon.setContentDescription(online ? "Wi-Fi connected" : "Offline");
    }
}
