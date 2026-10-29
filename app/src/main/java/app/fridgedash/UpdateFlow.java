package app.fridgedash;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ExecutorService;

/**
 * Check → confirm → download → install.
 * {@code banner} shows status text; {@code bannerBox} is the view shown/hidden
 * around it (may be the banner itself); {@code button} (optional) is a
 * "Check for updates" control.
 */
final class UpdateFlow {
    private final Activity act;
    private final ExecutorService bg;
    private final TextView banner;
    private final View bannerBox;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Updater.Info available;
    private boolean busy;

    UpdateFlow(Activity act, ExecutorService bg, TextView banner, View bannerBox, TextView button) {
        this.act = act;
        this.bg = bg;
        this.banner = banner;
        this.bannerBox = bannerBox != null ? bannerBox : banner;
        if (button != null) {
            button.setText("Check for updates  ·  v" + Updater.installedVersionName(act));
            button.setOnClickListener(v -> check(true));
        }
        this.bannerBox.setOnClickListener(v -> {
            if (available != null && !busy) confirm(available);
        });
    }

    /** Called from onResume: quiet check at most every few hours. */
    void autoCheck() {
        if (Updater.autoCheckDue(act)) check(false);
        else if (available != null && Updater.isNewer(act, available)) showAvailable(available);
        else hide();
    }

    void checkNow() { check(true); }

    private void check(boolean userAsked) {
        if (busy) return;
        busy = true;
        if (userAsked) showBanner("Checking for updates…");
        bg.execute(() -> {
            Updater.Info info = null;
            String error = null;
            try {
                info = Updater.fetchLatest(act.getApplicationContext());
            } catch (Exception e) {
                error = e.getMessage();
            }
            final Updater.Info fInfo = info;
            final String fError = error;
            main.post(() -> {
                busy = false;
                if (act.isDestroyed()) return;
                if (fInfo != null && Updater.isNewer(act, fInfo)) {
                    available = fInfo;
                    showAvailable(fInfo);
                    if (userAsked) confirm(fInfo);
                } else {
                    available = null;
                    hide();
                    if (userAsked) {
                        toast(fError != null ? "Update check failed: " + fError
                                : "FridgeDash is up to date (v" + Updater.installedVersionName(act) + ")");
                    }
                }
            });
        });
    }

    private void confirm(Updater.Info info) {
        String msg = "Installed: v" + Updater.installedVersionName(act)
                + "\nNew: v" + info.versionName
                + (info.notes.isEmpty() ? "" : "\n\n" + info.notes);
        new AlertDialog.Builder(act)
                .setTitle("Install update?")
                .setMessage(msg)
                .setPositiveButton("Download & install", (d, w) -> startDownload(info))
                .setNegativeButton("Later", null)
                .show();
    }

    private void startDownload(Updater.Info info) {
        if (!Updater.canInstall(act)) {
            new AlertDialog.Builder(act)
                    .setTitle("One-time permission")
                    .setMessage("Android needs you to allow FridgeDash to install updates.\n\n"
                            + "On the next screen, turn on \"Allow from this source\", "
                            + "press Back, then tap the update notice again.")
                    .setPositiveButton("Open settings", (d, w) -> Ui.start(act, Updater.unknownSourcesSettings(act)))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        busy = true;
        showBanner("Downloading v" + info.versionName + "…");
        bg.execute(() -> {
            File apk = null;
            String error = null;
            try {
                apk = Updater.download(act.getApplicationContext(), info, (done, total) -> {
                    if (total <= 0) return;
                    final int pct = (int) (done * 100 / total);
                    main.post(() -> banner.setText("Downloading v" + info.versionName + "… " + pct + "%"));
                });
            } catch (Exception e) {
                error = e.getMessage();
            }
            final boolean ok = apk != null;
            final String fError = error;
            main.post(() -> {
                busy = false;
                if (act.isDestroyed()) return;
                showAvailable(info);
                if (ok) Ui.start(act, Updater.installIntent());
                else toast("Download failed: " + fError);
            });
        });
    }

    private void showAvailable(Updater.Info info) {
        showBanner("Update available: v" + info.versionName + "  ·  tap to install");
    }

    private void showBanner(String text) {
        banner.setText(text);
        bannerBox.setVisibility(View.VISIBLE);
    }

    private void hide() {
        bannerBox.setVisibility(View.GONE);
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }
}
