package app.fridgedash;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The dashboard. In v0.1 this is an ordinary app (not the default Home),
 * opened from KISS or the Hisense app list.
 */
public class MainActivity extends Activity {

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private AppAdapter adapter;
    private LinearLayout shortcutRow;
    private TextView netStatus;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;
    private UpdateFlow updates;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        shortcutRow = findViewById(R.id.shortcutRow);
        netStatus = findViewById(R.id.netStatus);

        GridView grid = findViewById(R.id.appGrid);
        grid.setSelector(android.R.color.transparent);
        adapter = new AppAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) ->
                Ui.start(this, adapter.getItem(pos).intent));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            Ui.openAppInfo(this, adapter.getItem(pos).intent);
            return true;
        });

        findViewById(R.id.fridgeHome).setOnClickListener(v -> FridgeHome.open(this));
        findViewById(R.id.btnDiagnostics).setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                Ui.start(this, new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        updates = new UpdateFlow(this, bg, findViewById(R.id.updateBanner), findViewById(R.id.btnUpdate));

        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        watchNetwork();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadApps();
        updateNetwork();
        updates.autoCheck();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cm != null && netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }
        bg.shutdownNow();
    }

    /** Package lookups run off the UI thread; the screen fills in when they finish. */
    private void reloadApps() {
        final Context app = getApplicationContext();
        bg.execute(() -> {
            final List<AppEntry> apps = Apps.loadLaunchable(app);
            final List<AppEntry> shortcuts = Apps.loadShortcuts(app);
            main.post(() -> {
                if (isDestroyed()) return;
                adapter.setApps(apps);
                showShortcuts(shortcuts);
            });
        });
    }

    private void showShortcuts(List<AppEntry> shortcuts) {
        shortcutRow.removeAllViews();
        int gap = Ui.dp(this, 8);
        for (int i = 0; i < shortcuts.size(); i++) {
            final AppEntry e = shortcuts.get(i);
            Ui.IconTile tile = new Ui.IconTile(this, 56, 17);
            tile.bind(e);
            tile.setOnClickListener(v -> Ui.start(this, e.intent));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(gap);
            shortcutRow.addView(tile, lp);
        }
    }

    private void watchNetwork() {
        if (cm == null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { main.post(MainActivity.this::updateNetwork); }
            @Override public void onLost(Network n) { main.post(MainActivity.this::updateNetwork); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                main.post(MainActivity.this::updateNetwork);
            }
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
        String text;
        int color;
        if (caps == null) {
            text = "● Offline";
            color = getColor(R.color.warn);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            text = "● Wi-Fi connected";
            color = getColor(R.color.ok);
        } else {
            text = "● Connected";
            color = getColor(R.color.ok);
        }
        netStatus.setText(text);
        netStatus.setTextColor(color);
    }
}
