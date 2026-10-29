package app.fridgedash;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.widget.Toast;

import java.util.List;

/**
 * Finds and opens the original Hisense Home screen.
 *
 * Order of attempts:
 *  1. A Home activity the user picked in Diagnostics (saved preference).
 *  2. The best automatic guess: a system app that registers as a Home screen,
 *     ignoring Android's own placeholder (com.android.settings FallbackHome),
 *     FridgeDash itself and KISS.
 *  3. A plain "go Home" intent, which Android routes to the default Home app
 *     (the Hisense Home, as long as it stays the default).
 */
final class FridgeHome {
    private FridgeHome() {}

    private static final String PREFS = "fridgedash";
    private static final String KEY = "fridge_home_component";

    static Intent homeQuery() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    static List<ResolveInfo> allHomeActivities(PackageManager pm) {
        return pm.queryIntentActivities(homeQuery(), 0);
    }

    /** The Home app Android currently uses as default, or null if none is set. */
    static ComponentName currentDefault(PackageManager pm) {
        ResolveInfo ri = pm.resolveActivity(homeQuery(), PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        // "android/...ResolverActivity" means no default is chosen.
        if ("android".equals(ri.activityInfo.packageName)) return null;
        return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
    }

    static boolean isIgnored(Context ctx, String pkg) {
        return pkg.equals(ctx.getPackageName())
                || pkg.equals("com.android.settings")
                || pkg.equals("com.android.provision")
                || pkg.equals(Apps.PKG_KISS);
    }

    static ComponentName saved(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = p.getString(KEY, null);
        return s == null ? null : ComponentName.unflattenFromString(s);
    }

    static void save(Context ctx, ComponentName cn) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, cn == null ? null : cn.flattenToString())
                .apply();
    }

    static ComponentName autoDetect(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        ComponentName fallback = null;
        for (ResolveInfo ri : allHomeActivities(pm)) {
            String pkg = ri.activityInfo.packageName;
            if (isIgnored(ctx, pkg)) continue;
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            if (Apps.isSystem(ri.activityInfo.applicationInfo)) return cn;
            if (fallback == null) fallback = cn;
        }
        return fallback;
    }

    /** The component the Refrigerator button will open (saved choice wins over auto-detect). */
    static ComponentName resolve(Context ctx) {
        ComponentName s = saved(ctx);
        if (s != null && exists(ctx.getPackageManager(), s)) return s;
        return autoDetect(ctx);
    }

    static boolean exists(PackageManager pm, ComponentName cn) {
        try {
            pm.getActivityInfo(cn, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean launch(Context ctx, ComponentName cn) {
        Intent i = homeQuery().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** Called by the big Refrigerator button. */
    static void open(Context ctx) {
        ComponentName cn = resolve(ctx);
        if (cn != null && launch(ctx, cn)) return;

        // Fallback: ordinary Home intent, but only if that isn't FridgeDash itself
        // (when FridgeDash is the Home app it would just reopen us).
        ComponentName def = currentDefault(ctx.getPackageManager());
        if (def != null && !def.getPackageName().equals(ctx.getPackageName())) {
            try {
                ctx.startActivity(homeQuery().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        Toast.makeText(ctx, "Couldn't open the Hisense home screen. Open Settings → "
                + "Refrigerator button & diagnostics to pick it.", Toast.LENGTH_LONG).show();
    }

    static boolean isFridgeDashDefault(Context ctx) {
        ComponentName def = currentDefault(ctx.getPackageManager());
        return def != null && def.getPackageName().equals(ctx.getPackageName());
    }

    /** Opens Android's "Home app" chooser (Default apps). */
    static void openHomeSettings(Context ctx) {
        String[] actions = {
                android.provider.Settings.ACTION_HOME_SETTINGS,
                "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
                android.provider.Settings.ACTION_SETTINGS
        };
        for (String a : actions) {
            try {
                ctx.startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
            }
        }
    }
}
