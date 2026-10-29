package app.fridgedash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** The breathing circle on the call screen. Reacts to voice level; colour shows who's talking. */
public class OrbView extends View {

    static final int MODE_IDLE = 0, MODE_USER = 1, MODE_ASSISTANT = 2, MODE_THINKING = 3;

    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float level, shown;
    private int mode = MODE_IDLE;
    private long start = System.currentTimeMillis();

    public OrbView(Context c) { this(c, null); }

    public OrbView(Context c, AttributeSet a) {
        super(c, a);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
    }

    void setLevel(float l) { level = Math.max(0f, Math.min(1f, l)); }

    void setMode(int m) {
        if (mode == m) return;
        mode = m;
        start = System.currentTimeMillis();
        invalidate();
    }

    private int color() {
        switch (mode) {
            case MODE_USER: return 0xFF5ED3C4;       // teal: you're talking
            case MODE_ASSISTANT: return 0xFF9FB4FF;  // periwinkle: ChatGPT talking
            case MODE_THINKING: return 0xFFF5B14C;   // amber: thinking
            default: return 0xFFF4F6FB;              // white: listening
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, max = Math.min(w, h) / 2f;
        long t = System.currentTimeMillis() - start;

        shown += (level - shown) * 0.25f;
        level *= 0.92f;
        float breathe = (float) (0.5 + 0.5 * Math.sin(t / (mode == MODE_THINKING ? 220.0 : 700.0)));
        float base = max * 0.42f;
        float r = base * (1f + 0.35f * shown + 0.04f * breathe);

        int col = color();
        int rgb = col & 0x00FFFFFF;
        halo.setShader(new RadialGradient(cx, cy, max, new int[]{0x66000000 | rgb, 0x00000000 | rgb},
                new float[]{0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, max * (0.75f + 0.25f * shown), halo);

        core.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f,
                new int[]{0xFFFFFFFF, col}, null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, core);

        ring.setColor(0x55000000 | rgb);
        c.drawCircle(cx, cy, r + max * 0.12f * (0.4f + breathe * 0.6f), ring);

        postInvalidateOnAnimation();
    }
}
