package app.fridgedash;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Forecast screen: now, the next 24 hours and 7 days. Opened by tapping the weather on the home screen. */
public class WeatherActivity extends BaseActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF070B14);
        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.FIT_XY);
        Bitmap b = Glass.backdrop();
        if (b != null) back.setImageBitmap(b);
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(this);
        dim.setBackgroundColor(0x66050812);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(28), dp(24), dp(32));
        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        Weather w = Weather.cached(this);
        render(w);
        if (w == null || w.stale()) refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Glass.setRoot(root);
    }

    private void refresh() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Weather w = null;
            try { w = Weather.fetch(app); } catch (Exception ignored) {}
            final Weather fw = w;
            main.post(() -> {
                if (isDestroyed()) return;
                if (fw != null) render(fw);
                else if (Weather.cached(this) == null) render(null);
            });
        }, "weather").start();
    }

    private void render(Weather w) {
        col.removeAllViews();

        // Top bar: place + close
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView place = text(w == null ? "Weather" : w.place, 17, true);
        top.addView(place, new LinearLayout.LayoutParams(0, -2, 1f));
        GlassLayout close = new GlassLayout(this);
        close.setPill(true);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        close.setContentDescription("Close");
        ImageView x = new ImageView(this);
        x.setImageResource(R.drawable.ic_close);
        close.addView(x, new LinearLayout.LayoutParams(dp(16), dp(16)));
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        col.addView(top);

        if (w == null) {
            TextView t = text("Loading the forecast… (needs Wi-Fi)", 13, false);
            t.setPadding(0, dp(40), 0, 0);
            col.addView(t);
            return;
        }

        // Now
        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        now.setPadding(0, dp(16), 0, dp(4));
        TextView icon = text(Weather.icon(w.code, w.isDay), 60, false);
        icon.setIncludeFontPadding(false);
        now.addView(icon);
        TextView temp = text(w.temp + "°", 76, false);
        temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        temp.setIncludeFontPadding(false);
        temp.setPadding(dp(14), 0, 0, 0);
        now.addView(temp);
        col.addView(now);
        col.addView(text(Weather.label(w.code) + " · High " + w.todayMax + "° · Low " + w.todayMin + "°", 14, true));
        TextView details = text("Feels like " + w.feels + "° · Wind " + w.wind + " km/h · Humidity " + w.humidity + "%"
                + (w.days.isEmpty() || w.days.get(0).sunrise.isEmpty() ? ""
                : "\nSunrise " + w.days.get(0).sunrise + " · Sunset " + w.days.get(0).sunset), 11, false);
        details.setTextColor(0xDBF4F6FB);
        details.setLineSpacing(0, 1.25f);
        details.setPadding(0, dp(4), 0, dp(16));
        col.addView(details);

        // Next 24 hours
        GlassLayout hoursCard = card();
        hoursCard.addView(label("NEXT 24 HOURS"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout hrow = new LinearLayout(this);
        for (int i = 0; i < w.hours.size(); i++) {
            Weather.Hour h = w.hours.get(i);
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setPadding(dp(4), dp(6), dp(4), dp(2));
            TextView t = text(i == 0 ? "Now" : h.time, 10, i == 0);
            t.setTextColor(0xDBF4F6FB);
            c.addView(t);
            TextView ic = text(Weather.icon(h.code, h.day), 22, false);
            ic.setPadding(0, dp(6), 0, dp(4));
            c.addView(ic);
            c.addView(text(h.temp + "°", 14, true));
            TextView rain = text(h.rain >= 20 ? h.rain + "%" : " ", 9, false);
            rain.setTextColor(0xFF8EC9FF);
            c.addView(rain);
            hrow.addView(c, new LinearLayout.LayoutParams(dp(48), -2));
        }
        hs.addView(hrow);
        hoursCard.addView(hs);
        col.addView(hoursCard, cardLp());

        // 7 days
        GlassLayout daysCard = card();
        daysCard.addView(label("7 DAYS"));
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Weather.Day d : w.days) { lo = Math.min(lo, d.min); hi = Math.max(hi, d.max); }
        SimpleDateFormat dow = new SimpleDateFormat("EEE", Locale.getDefault());
        for (int i = 0; i < w.days.size(); i++) {
            Weather.Day d = w.days.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            row.addView(text(i == 0 ? "Today" : i == 1 ? "Tomorrow" : dow.format(d.date), 13, i == 0),
                    new LinearLayout.LayoutParams(dp(82), -2));
            row.addView(text(Weather.icon(d.code, true), 20, false), new LinearLayout.LayoutParams(dp(36), -2));
            TextView rain = text(d.rain >= 20 ? "☂ " + d.rain + "%" : "", 10, false);
            rain.setTextColor(0xFF8EC9FF);
            row.addView(rain, new LinearLayout.LayoutParams(dp(52), -2));
            TextView min = text(d.min + "°", 13, false);
            min.setTextColor(0xB3F4F6FB);
            min.setGravity(Gravity.END);
            row.addView(min, new LinearLayout.LayoutParams(dp(34), -2));
            row.addView(rangeBar(lo, hi, d.min, d.max), new LinearLayout.LayoutParams(0, dp(6), 1f));
            TextView max = text(d.max + "°", 13, true);
            row.addView(max, new LinearLayout.LayoutParams(dp(34), -2));
            daysCard.addView(row);
        }
        col.addView(daysCard, cardLp());

        TextView foot = text("Updated " + new SimpleDateFormat("H:mm", Locale.US).format(new Date(w.fetched))
                + " · Open-Meteo · tap to refresh", 10, false);
        foot.setTextColor(0x99F4F6FB);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(14), 0, 0);
        foot.setOnClickListener(v -> { foot.setText("Refreshing…"); refresh(); });
        col.addView(foot, new LinearLayout.LayoutParams(-1, -2));
        Glass.invalidateAll(root);
    }

    /** A temperature bar placed within the week's coldest–warmest range. */
    private View rangeBar(int lo, int hi, int min, int max) {
        FrameLayout track = new FrameLayout(this);
        track.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(dp(3));
        View t = new View(this);
        t.setBackground(bg);
        track.addView(t, new FrameLayout.LayoutParams(-1, -1));
        View fill = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF7FC8FF, 0xFFFFC56B});
        g.setCornerRadius(dp(3));
        fill.setBackground(g);
        track.addView(fill, new FrameLayout.LayoutParams(0, -1));
        track.addOnLayoutChangeListener((v, l, tp, r, bt, ol, ot, or, ob) -> {
            int w = r - l - dp(16);
            if (w <= 0) return;
            float span = Math.max(1, hi - lo);
            int start = Math.round(w * (min - lo) / span);
            int width = Math.max(dp(6), Math.round(w * (max - min) / span));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            if (lp.leftMargin != start || lp.width != width) {
                lp.leftMargin = start;
                lp.width = width;
                fill.post(() -> fill.setLayoutParams(lp));
            }
        });
        return track;
    }

    // ------------------------------------------------------------------ view helpers

    private GlassLayout card() {
        GlassLayout g = new GlassLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setPadding(dp(16), dp(12), dp(16), dp(12));
        return g;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        return lp;
    }

    private TextView label(String s) {
        TextView t = text(s, 10, true);
        t.setTextColor(0xB3F4F6FB);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xFFF4F6FB);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int dp(float v) { return Ui.dp(this, v); }
}
