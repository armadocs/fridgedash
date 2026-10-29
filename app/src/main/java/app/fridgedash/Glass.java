package app.fridgedash;

import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

/**
 * Shared "frosted glass" state: a heavily blurred, screen-shaped copy of the
 * wallpaper. Each GlassLayout draws the part of it that lies behind itself,
 * which looks like live background blur but costs almost nothing.
 */
final class Glass {
    private Glass() {}

    private static Bitmap backdrop;          // small, blurred, same aspect as the root view
    private static WeakReference<View> root = new WeakReference<>(null);

    static void setRoot(View v) { root = new WeakReference<>(v); }
    static View root() { return root.get(); }

    static Bitmap backdrop() { return backdrop; }

    static void setBackdrop(Bitmap b) {
        backdrop = b;
        View r = root.get();
        if (r != null) invalidateAll(r);
    }

    static void invalidateAll(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateAll(g.getChildAt(i));
        }
    }
}
