package app.fridgedash;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

/**
 * Lays every screen out on a fixed virtual canvas (540dp wide by default), so
 * the design looks the same whatever density the fridge panel reports.
 */
public class BaseActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        try {
            WindowManager wm = (WindowManager) newBase.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            Display d = wm.getDefaultDisplay();
            DisplayMetrics real = new DisplayMetrics();
            d.getRealMetrics(real);
            int shortSide = Math.min(real.widthPixels, real.heightPixels);
            int targetWidthDp = Prefs.uiWidthDp(newBase);
            int dpi = Math.round(shortSide * 160f / targetWidthDp);
            if (dpi < 80 || dpi > 640) return;
            Configuration cfg = new Configuration();
            cfg.densityDpi = dpi;
            applyOverrideConfiguration(cfg);
        } catch (Exception ignored) {
            // Fall back to the panel's own density.
        }
    }
}
