package app.fridgedash;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.GridView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Every installed app, plus Diagnostics and Android settings. */
public class AppsActivity extends BaseActivity {

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);

        GridView grid = findViewById(R.id.appGrid);
        grid.setSelector(android.R.color.transparent);
        adapter = new AppAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> Ui.start(this, adapter.getItem(pos).intent));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            Ui.openAppInfo(this, adapter.getItem(pos).intent);
            return true;
        });

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnDiagnostics).setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        findViewById(R.id.btnAndroidSettings).setOnClickListener(v ->
                Ui.start(this, new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        final Context app = getApplicationContext();
        bg.execute(() -> {
            final List<AppEntry> apps = Apps.loadLaunchable(app);
            main.post(() -> { if (!isDestroyed()) adapter.setApps(apps); });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdownNow();
    }
}
