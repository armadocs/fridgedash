package app.fridgedash;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

/** A LinearLayout drawn as a frosted-glass card over the wallpaper. */
public class GlassLayout extends LinearLayout {

    private float radius;
    private boolean pill;
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private final int[] rootLoc = new int[2];
    private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;

    private final ViewTreeObserver.OnPreDrawListener moveWatcher = () -> {
        // Re-draw when this card moves (scrolling, layout changes) so the
        // blurred backdrop stays aligned with the wallpaper behind it.
        getLocationInWindow(loc);
        if (loc[0] != lastX || loc[1] != lastY) {
            lastX = loc[0];
            lastY = loc[1];
            invalidate();
        }
        return true;
    };

    public GlassLayout(Context c) { this(c, null); }

    public GlassLayout(Context c, AttributeSet attrs) {
        super(c, attrs);
        setWillNotDraw(false);
        float d = getResources().getDisplayMetrics().density;
        int tintColor = 0x5C101628; // rgba(16,22,40,0.36)
        radius = 18 * d;
        if (attrs != null) {
            TypedArray a = c.obtainStyledAttributes(attrs, R.styleable.GlassLayout);
            radius = a.getDimension(R.styleable.GlassLayout_glassRadius, radius);
            pill = a.getBoolean(R.styleable.GlassLayout_glassPill, false);
            tintColor = a.getColor(R.styleable.GlassLayout_glassTint, tintColor);
            a.recycle();
        }
        tint.setColor(tintColor);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, d * 0.6f));
        stroke.setColor(0x33FFFFFF);
        pressed.setColor(0x22FFFFFF);
    }

    public void setTintColor(int color) {
        tint.setColor(color);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(moveWatcher);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(moveWatcher);
        super.onDetachedFromWindow();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate(); // pressed highlight
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float r = pill ? h / 2f : radius;
        rect.set(0, 0, w, h);
        path.reset();
        path.addRoundRect(rect, r, r, Path.Direction.CW);

        c.save();
        c.clipPath(path);
        Bitmap b = Glass.backdrop();
        View root = Glass.root();
        if (b != null && root != null && root.getWidth() > 0) {
            getLocationInWindow(loc);
            root.getLocationInWindow(rootLoc);
            float sx = root.getWidth() / (float) b.getWidth();
            float sy = root.getHeight() / (float) b.getHeight();
            c.save();
            c.translate(-(loc[0] - rootLoc[0]), -(loc[1] - rootLoc[1]));
            c.scale(sx, sy);
            c.drawBitmap(b, 0, 0, bmpPaint);
            c.restore();
        }
        c.drawRect(rect, tint);
        if (isPressed()) c.drawRect(rect, pressed);
        c.restore();

        float half = stroke.getStrokeWidth() / 2f;
        rect.inset(half, half);
        c.drawRoundRect(rect, r - half, r - half, stroke);

        super.onDraw(c);
    }
}
