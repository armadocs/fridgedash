package app.fridgedash;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only probe of the fridge's Android system, plus controls for choosing
 * which Home activity the Refrigerator button opens. Nothing here modifies
 * other apps or system settings.
 */
public class DiagnosticsActivity extends Activity {

    private static final int REQ_STORAGE = 1;
    private static final int MAX_ACTIVITIES = 60;

    private LinearLayout root;
    private LinearLayout homeSection;
    private TextView reportView;
    private String report = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("Diagnostics", 32, true));
        root.addView(text("Read-only. Photograph these pages, or save/share the report, "
                + "to continue setup.", 17, false));

        LinearLayout actions = row();
        actions.addView(button("Save report to Download", v -> saveReport()), weighted());
        actions.addView(button("Share report", v -> shareReport()), weighted());
        actions.addView(button("Back", v -> finish()), weighted());
        root.addView(actions);

        root.addView(text("Refrigerator button", 24, true));
        homeSection = new LinearLayout(this);
        homeSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(homeSection);

        root.addView(text("Report", 24, true));
        reportView = text("Collecting…", 15, false);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        root.addView(reportView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildHomeSection();
        refreshReport();
    }

    // ---------------------------------------------------------------- home choice

    private void buildHomeSection() {
        homeSection.removeAllViews();
        PackageManager pm = getPackageManager();
        ComponentName target = FridgeHome.resolve(this);
        ComponentName saved = FridgeHome.saved(this);
        ComponentName def = FridgeHome.currentDefault(pm);

        homeSection.addView(text("The button currently opens: "
                + (target == null ? "(nothing found — falls back to the default Home)" : target.flattenToShortString())
                + (saved != null ? "  [chosen manually]" : "  [automatic]"), 17, false));
        homeSection.addView(text("Android's default Home app: "
                + (def == null ? "none selected" : def.flattenToShortString()), 17, false));

        for (ResolveInfo ri : FridgeHome.allHomeActivities(pm)) {
            final ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            String label = String.valueOf(ri.loadLabel(pm));
            boolean system = Apps.isSystem(ri.activityInfo.applicationInfo);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.tile);
            int p = Ui.dp(this, 16);
            card.setPadding(p, p, p, p);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = Ui.dp(this, 12);

            card.addView(text(label + (system ? "  (system)" : "")
                    + (cn.equals(target) ? "  ← Refrigerator button" : ""), 19, true));
            card.addView(text(cn.flattenToShortString(), 15, false));

            LinearLayout r = row();
            r.addView(button("Test launch", v -> {
                if (!FridgeHome.launch(this, cn)) {
                    Toast.makeText(this, "Launch failed", Toast.LENGTH_SHORT).show();
                }
            }), weighted());
            r.addView(button("Use for Refrigerator button", v -> {
                FridgeHome.save(this, cn);
                buildHomeSection();
            }), weighted());
            card.addView(r);
            homeSection.addView(card, clp);
        }

        if (saved != null) {
            homeSection.addView(button("Reset to automatic choice", v -> {
                FridgeHome.save(this, null);
                buildHomeSection();
            }));
        }
    }

    // ---------------------------------------------------------------- report

    private void refreshReport() {
        // Gather display info on the UI thread (needs the window), the rest in background.
        final String display = displaySection();
        final Context app = getApplicationContext();
        new Thread(() -> {
            final String r = buildReport(app, display);
            new Handler(Looper.getMainLooper()).post(() -> {
                report = r;
                reportView.setText(r);
            });
        }, "diagnostics").start();
    }

    private String displaySection() {
        StringBuilder sb = new StringBuilder();
        sb.append("== Screen ==\n");
        Display d = getWindowManager().getDefaultDisplay();
        DisplayMetrics real = new DisplayMetrics();
        d.getRealMetrics(real);
        Configuration cfg = getResources().getConfiguration();
        sb.append("Real size px: ").append(real.widthPixels).append(" x ").append(real.heightPixels).append('\n');
        sb.append("Density: ").append(real.densityDpi).append(" dpi (x").append(real.density).append(")\n");
        sb.append("Physical dpi: ").append(Math.round(real.xdpi)).append(" x ").append(Math.round(real.ydpi)).append('\n');
        sb.append("Size dp: ").append(cfg.screenWidthDp).append(" x ").append(cfg.screenHeightDp)
                .append(", smallest ").append(cfg.smallestScreenWidthDp).append('\n');
        sb.append("Orientation: ").append(cfg.orientation == Configuration.ORIENTATION_PORTRAIT ? "portrait" : "landscape")
                .append(", rotation ").append(d.getRotation() * 90).append("°\n");
        sb.append("Refresh rate: ").append(Math.round(d.getRefreshRate())).append(" Hz\n");
        sb.append("Font scale: ").append(cfg.fontScale).append('\n');
        return sb.toString();
    }

    private static String buildReport(Context ctx, String display) {
        PackageManager pm = ctx.getPackageManager();
        StringBuilder sb = new StringBuilder();
        String version = "?";
        try { version = pm.getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) {}

        sb.append("FridgeDash ").append(version).append(" diagnostics\n");
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append("\n\n");

        sb.append("== Device ==\n");
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append(" / brand ").append(Build.BRAND).append('\n');
        sb.append("Model: ").append(Build.MODEL).append(" / device ").append(Build.DEVICE)
                .append(" / product ").append(Build.PRODUCT).append('\n');
        sb.append("Hardware: ").append(Build.HARDWARE).append(" / board ").append(Build.BOARD).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Security patch: ").append(Build.VERSION.SECURITY_PATCH).append('\n');
        sb.append("Build: ").append(Build.DISPLAY).append('\n');
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n');
        sb.append("CPU ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("  64-bit ABIs: ").append(Arrays.toString(Build.SUPPORTED_64_BIT_ABIS)).append('\n');
        sb.append('\n').append(display).append('\n');

        sb.append("== Memory & storage ==\n");
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append("RAM total/free: ").append(mb(mi.totalMem)).append(" / ").append(mb(mi.availMem))
                    .append(mi.lowMemory ? "  (LOW)" : "").append('\n');
            sb.append("App heap limit: ").append(am.getMemoryClass()).append(" MB (large ")
                    .append(am.getLargeMemoryClass()).append(" MB), low-RAM device: ")
                    .append(am.isLowRamDevice()).append('\n');
        }
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            sb.append("Storage total/free: ").append(mb(fs.getTotalBytes())).append(" / ")
                    .append(mb(fs.getAvailableBytes())).append('\n');
        } catch (Exception e) {
            sb.append("Storage: ").append(e.getClass().getSimpleName()).append('\n');
        }
        sb.append('\n');

        sb.append("== Network ==\n");
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        if (caps == null) sb.append("Offline\n");
        else sb.append(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : "Other transport")
                .append(", internet capability: ")
                .append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).append('\n');
        sb.append('\n');

        sb.append("== WebView (matters for an embedded Home Assistant dashboard) ==\n");
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                PackageInfo wv = WebView.getCurrentWebViewPackage();
                sb.append(wv == null ? "none" : wv.packageName + " " + wv.versionName).append('\n');
            } else {
                sb.append("(API < 26, not queried)\n");
            }
        } catch (Throwable t) {
            sb.append("error: ").append(t.getClass().getSimpleName()).append('\n');
        }
        sb.append('\n');

        sb.append("== Google services ==\n");
        sb.append("Play Services (com.google.android.gms): ").append(Apps.isInstalled(pm, "com.google.android.gms")).append('\n');
        sb.append("Services Framework (com.google.android.gsf): ").append(Apps.isInstalled(pm, "com.google.android.gsf")).append('\n');
        sb.append("Play Store (com.android.vending): ").append(Apps.isInstalled(pm, "com.android.vending")).append('\n');
        sb.append('\n');

        sb.append("== Home screen apps ==\n");
        ComponentName def = FridgeHome.currentDefault(pm);
        ComponentName target = FridgeHome.resolve(ctx);
        Set<String> homePkgs = new LinkedHashSet<>();
        for (ResolveInfo ri : FridgeHome.allHomeActivities(pm)) {
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            sb.append("- ").append(ri.loadLabel(pm)).append(" | ").append(cn.flattenToShortString())
                    .append(" | ").append(Apps.isSystem(ri.activityInfo.applicationInfo) ? "system" : "user")
                    .append(" | prio ").append(ri.priority);
            if (cn.equals(def)) sb.append(" [DEFAULT]");
            if (cn.equals(target)) sb.append(" [FRIDGE BUTTON]");
            sb.append('\n');
            if (!FridgeHome.isIgnored(ctx, cn.getPackageName())) homePkgs.add(cn.getPackageName());
        }
        sb.append('\n');

        // Vendor (non-AOSP) system apps: the Hisense originals live here.
        sb.append("== Vendor system apps ==\n");
        List<String> vendorLines = new ArrayList<>();
        Set<String> keyPkgs = new LinkedHashSet<>(homePkgs);
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (!Apps.isSystem(ai)) continue;
            String pkg = ai.packageName;
            if (pkg.startsWith("com.android.") || pkg.equals("android")
                    || pkg.startsWith("android.") || pkg.startsWith("com.google.")) continue;
            String label = String.valueOf(ai.loadLabel(pm));
            boolean launchable = pm.getLaunchIntentForPackage(pkg) != null;
            vendorLines.add(label + " | " + pkg + (launchable ? " | launchable" : ""));
            if (label.equalsIgnoreCase("Refrigerator Control")) keyPkgs.add(pkg);
        }
        final Collator c = Collator.getInstance();
        Collections.sort(vendorLines, c);
        for (String l : vendorLines) sb.append("- ").append(l).append('\n');
        sb.append('\n');

        for (String pkg : keyPkgs) {
            sb.append("== Activities in ").append(pkg).append(" ==\n");
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                sb.append("version ").append(pi.versionName).append('\n');
                if (pi.activities == null) {
                    sb.append("(none)\n");
                } else {
                    int shown = 0;
                    for (ActivityInfo ai : pi.activities) {
                        if (shown++ >= MAX_ACTIVITIES) {
                            sb.append("… ").append(pi.activities.length - MAX_ACTIVITIES).append(" more\n");
                            break;
                        }
                        sb.append(ai.exported ? "  [exported] " : "  ").append(ai.name).append('\n');
                    }
                }
            } catch (Exception e) {
                sb.append("error: ").append(e.getClass().getSimpleName()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("== Launchable apps ==\n");
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String> lines = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            lines.add(ri.loadLabel(pm) + " | " + ri.activityInfo.packageName + "/" + ri.activityInfo.name);
        }
        Collections.sort(lines, c);
        for (String l : lines) sb.append("- ").append(l).append('\n');

        return sb.toString();
    }

    private static String mb(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }

    // ---------------------------------------------------------------- save / share

    private void saveReport() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        writeReportFile();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) writeReportFile();
            else Toast.makeText(this, "Storage permission denied; use Share or a photo instead",
                    Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void writeReportFile() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("can't create " + dir);
            File f = new File(dir, "fridgedash-report.txt");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "Saved: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareReport() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "FridgeDash diagnostics");
        send.putExtra(Intent.EXTRA_TEXT, report);
        Ui.start(this, Intent.createChooser(send, "Share report"));
    }

    // ---------------------------------------------------------------- tiny view helpers

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(bold ? R.color.text : R.color.text_dim));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        int v = Ui.dp(this, bold ? 16 : 4);
        t.setPadding(0, v, 0, Ui.dp(this, 4));
        return t;
    }

    private TextView button(String s, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTextColor(getColor(R.color.text));
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.tile_primary);
        int p = Ui.dp(this, 12);
        b.setPadding(p, p, p, p);
        b.setMinHeight(Ui.dp(this, 56));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        return r;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Ui.dp(this, 8));
        return lp;
    }
}
