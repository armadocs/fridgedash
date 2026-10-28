package app.fridgedash;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only queries against PackageManager. Nothing here changes any app. */
final class Apps {
    private Apps() {}

    static final String PKG_KISS = "fr.neamar.kiss";
    static final String PKG_FDROID = "org.fdroid.fdroid";
    static final String[] PKG_HOME_ASSISTANT = {
            "io.homeassistant.companion.android.minimal", // F-Droid build
            "io.homeassistant.companion.android"          // Play build
    };

    /** All apps with a launcher icon, sorted by name (excluding FridgeDash itself). */
    static List<AppEntry> loadLaunchable(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        List<AppEntry> out = new ArrayList<>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            Drawable icon = ri.loadIcon(pm);
            out.add(new AppEntry(label, icon, AppEntry.launcherIntent(pkg, ri.activityInfo.name)));
        }
        final Collator c = Collator.getInstance();
        Collections.sort(out, (a, b) -> c.compare(a.label, b.label));
        return out;
    }

    static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean isSystem(ApplicationInfo ai) {
        return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    /** Finds an installed package whose visible name equals {@code label} (case-insensitive). */
    static String findPackageByLabel(PackageManager pm, String label) {
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            CharSequence l = ai.loadLabel(pm);
            if (l != null && l.toString().trim().equalsIgnoreCase(label)) return ai.packageName;
        }
        return null;
    }

    /** Shortcut for a package, or null if it isn't installed or has no launchable screen. */
    static AppEntry entryForPackage(PackageManager pm, String pkg, String labelOverride) {
        if (pkg == null) return null;
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return null;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String label = labelOverride != null ? labelOverride : String.valueOf(ai.loadLabel(pm));
            return new AppEntry(label, ai.loadIcon(pm), launch);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The tiles in the shortcut row. Only things actually installed are returned. */
    static List<AppEntry> loadShortcuts(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<AppEntry> out = new ArrayList<>();

        AppEntry fridge = entryForPackage(pm, findPackageByLabel(pm, "Refrigerator Control"), "Fridge controls");
        if (fridge != null) out.add(fridge);

        for (String pkg : PKG_HOME_ASSISTANT) {
            AppEntry ha = entryForPackage(pm, pkg, "Home Assistant");
            if (ha != null) { out.add(ha); break; }
        }

        AppEntry browser = browserEntry(pm);
        if (browser != null) out.add(browser);

        AppEntry fdroid = entryForPackage(pm, PKG_FDROID, "F-Droid");
        if (fdroid != null) out.add(fdroid);

        return out;
    }

    private static AppEntry browserEntry(PackageManager pm) {
        // Whatever Android considers the default browser (FOSS Browser on the fridge).
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
        ResolveInfo ri = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        String pkg = ri.activityInfo.packageName;
        if ("android".equals(pkg)) return null; // that's the chooser, no default set
        return entryForPackage(pm, pkg, "Browser");
    }
}
