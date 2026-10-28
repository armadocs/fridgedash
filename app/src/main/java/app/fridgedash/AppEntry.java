package app.fridgedash;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Drawable;

/** A launchable activity shown in the app grid or as a shortcut tile. */
final class AppEntry {
    final String label;
    final Drawable icon;
    final Intent intent;

    AppEntry(String label, Drawable icon, Intent intent) {
        this.label = label;
        this.icon = icon;
        this.intent = intent;
    }

    static Intent launcherIntent(String pkg, String cls) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(new ComponentName(pkg, cls));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
