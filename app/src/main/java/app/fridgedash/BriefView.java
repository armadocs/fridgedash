package app.fridgedash;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Draws the brief as glass cards into a vertical LinearLayout (sizes match the mockup at 540dp). */
final class BriefView {

    private static final int TEXT = 0xFFF4F6FB;
    private static final int DIM = 0xB8E8ECF5;
    private static final int AMBER = 0xFFF5B14C, TEAL = 0xFF5ED3C4, BLUE = 0xFF9FB4FF, GREY = 0xFFB8C0CC;

    interface Listener {
        /** An item was ticked/unticked on screen (already updated locally). */
        void onToggle(Brief b, Brief.Item it, boolean done);
    }

    private final Context ctx;
    private final LinearLayout col;
    private boolean parkingOpen;
    private Listener listener;
    private Brief current;
    private String currentStatus;

    void setListener(Listener l) { listener = l; }

    BriefView(Context ctx, LinearLayout col) {
        this.ctx = ctx;
        this.col = col;
    }

    /** Shows a message card instead of a brief (not set up, error, loading). */
    void showMessage(String heading, String message) {
        col.removeAllViews();
        col.addView(header(heading, null, null));
        GlassLayout card = card();
        card.addView(text(message, 11, DIM, false));
        col.addView(card, cardLp());
    }

    void show(Brief b, String status) {
        current = b;
        currentStatus = status;
        col.removeAllViews();
        int total = b.taskCount();
        String progress = total > 0 ? b.doneCount(ctx) + " of " + total + " done" : null;
        col.addView(header("Today", subtitle(b, status), progress));

        List<Brief.Section> pairable = new ArrayList<>();
        for (Brief.Section s : b.sections) {
            switch (s.kind) {
                case "tasks":
                    flushPairs(pairable);
                    col.addView(tasksCard(b, s), cardLp());
                    break;
                case "later":
                    flushPairs(pairable);
                    col.addView(laterCard(s), cardLp());
                    break;
                default:
                    pairable.add(s);
            }
        }
        flushPairs(pairable);
    }

    private String subtitle(Brief b, String status) {
        StringBuilder sb = new StringBuilder(b.title.isEmpty() ? "Daily brief" : b.title);
        if (b.live) sb.append(" · synced with Notion");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!b.date.isEmpty() && !b.date.equals(today)) sb.append(" · from ").append(b.date);
        if (status != null && !status.isEmpty()) sb.append(" · ").append(status);
        return sb.toString();
    }

    /** Info/events sections go two per row, like the mockup. */
    private void flushPairs(List<Brief.Section> list) {
        for (int i = 0; i < list.size(); i += 2) {
            if (i + 1 < list.size()) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                c.setMarginStart(dp(12));
                row.addView(sectionCard(list.get(i)), a);
                row.addView(sectionCard(list.get(i + 1)), c);
                col.addView(row, cardLp());
            } else {
                col.addView(sectionCard(list.get(i)), cardLp());
            }
        }
        list.clear();
    }

    // ------------------------------------------------------------------ cards

    private View header(String title, String sub, String progress) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 20, TEXT, true);
        left.addView(t);
        if (sub != null) {
            TextView s = text(sub, 10, DIM, false);
            s.setPadding(0, dp(3), 0, 0);
            left.addView(s);
        }
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (progress != null) {
            GlassLayout pill = pill();
            TextView p = text(progress, 11, AMBER, true);
            pill.addView(p);
            row.addView(pill);
        }
        return row;
    }

    private GlassLayout tasksCard(Brief b, Brief.Section s) {
        GlassLayout card = card();
        card.addView(sectionTitle(s.title, AMBER));
        for (int i = 0; i < s.items.size(); i++) {
            final Brief.Item it = s.items.get(i);
            final boolean done = b.isDone(ctx, it);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(9), 0, dp(9));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription((done ? "Done: " : "Not done: ") + it.title);

            FrameLayout box = new FrameLayout(ctx);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setStroke(dp(2), done ? AMBER : 0xD9F5B14C);
            bg.setColor(done ? AMBER : 0x0AFFFFFF);
            box.setBackground(bg);
            if (done) {
                ImageView check = new ImageView(ctx);
                check.setImageResource(R.drawable.ic_check);
                check.setColorFilter(0xFF1A1206);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER);
                box.addView(check, clp);
            }
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(26), dp(26));
            blp.setMarginEnd(dp(10));
            row.addView(box, blp);

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(it.title, 12, done ? 0x80E8ECF5 : TEXT, true);
            if (done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            texts.addView(title);
            if (!it.detail.isEmpty()) {
                TextView d = text(it.detail, 9.5f, DIM, false);
                d.setPadding(0, dp(3), 0, 0);
                d.setLineSpacing(0, 1.2f);
                texts.addView(d);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(v -> {
                b.setDone(ctx, it, !done);
                if (listener != null) listener.onToggle(b, it, !done);
                show(b, currentStatus);
            });

            if (i > 0) card.addView(divider());
            card.addView(row);
        }
        return card;
    }

    private GlassLayout sectionCard(Brief.Section s) {
        boolean events = "events".equals(s.kind);
        int accent = events ? BLUE : TEAL;
        GlassLayout card = card();
        card.addView(sectionTitle(s.title, accent));
        for (Brief.Item it : s.items) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, 0);
            String[] chip = events ? dateChip(it.when) : null;
            if (chip != null) {
                LinearLayout c = new LinearLayout(ctx);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                c.setPadding(0, dp(4), 0, dp(4));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(8));
                bg.setColor(0x249FB4FF);
                c.setBackground(bg);
                TextView day = text(chip[0], 7.5f, BLUE, true);
                day.setLetterSpacing(0.08f);
                c.addView(day);
                c.addView(text(chip[1], 11, TEXT, true));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.setMarginEnd(dp(8));
                row.addView(c, clp);
            }
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(text(it.title, 11, TEXT, true));
            if (!it.detail.isEmpty()) {
                TextView d = text(it.detail, 9, DIM, false);
                d.setPadding(0, dp(2), 0, 0);
                d.setLineSpacing(0, 1.2f);
                texts.addView(d);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(row);
        }
        return card;
    }

    private GlassLayout laterCard(Brief.Section s) {
        GlassLayout card = card();
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(s.title + "  ·  " + s.items.size(), 11, TEXT, true);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(text(parkingOpen ? "Hide" : "Show", 10, GREY, false));
        card.addView(head);
        if (parkingOpen && current != null) {
            for (Brief.Item it : s.items) {
                final boolean done = current.isDone(ctx, it);
                TextView line = text((done ? "☑  " : "☐  ") + it.title + (it.detail.isEmpty() ? "" : " — " + it.detail),
                        9.5f, done ? 0x80E8ECF5 : DIM, false);
                if (done) line.setPaintFlags(line.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                line.setPadding(0, dp(8), 0, dp(2));
                line.setLineSpacing(0, 1.2f);
                line.setClickable(true);
                line.setOnClickListener(v -> {
                    Brief b = current;
                    b.setDone(ctx, it, !done);
                    if (listener != null) listener.onToggle(b, it, !done);
                    show(b, currentStatus);
                });
                card.addView(line);
            }
        }
        card.setOnClickListener(v -> {
            parkingOpen = !parkingOpen;
            LinearLayout parent = (LinearLayout) v.getParent();
            int idx = parent.indexOfChild(v);
            parent.removeViewAt(idx);
            parent.addView(laterCard(s), idx, cardLp());
        });
        return card;
    }

    /** "THU" + "9:00" for a time, or "FRI" + "10/30" for a date-only value. */
    private static String[] dateChip(String when) {
        if (when == null || when.isEmpty()) return null;
        String[] fmts = {"yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        for (String f : fmts) {
            try {
                SimpleDateFormat in = new SimpleDateFormat(f, Locale.US);
                in.setLenient(false);
                Date d = in.parse(when);
                if (d == null) continue;
                String day = new SimpleDateFormat("EEE", Locale.US).format(d).toUpperCase(Locale.US);
                boolean hasTime = f.contains("HH");
                String second = hasTime ? new SimpleDateFormat("H:mm", Locale.US).format(d)
                        : new SimpleDateFormat("M/d", Locale.US).format(d);
                return new String[]{day, second};
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ small parts

    private GlassLayout card() {
        GlassLayout g = new GlassLayout(ctx);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setPadding(dp(14), dp(14), dp(14), dp(10));
        return g;
    }

    private GlassLayout pill() {
        GlassLayout g = new GlassLayout(ctx);
        g.setPill(true);
        g.setPadding(dp(11), dp(6), dp(11), dp(6));
        return g;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        return lp;
    }

    private View sectionTitle(String title, int color) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dlp.setMarginEnd(dp(6));
        row.addView(dot, dlp);
        TextView t = text(title.toUpperCase(Locale.getDefault()), 11, color, true);
        t.setLetterSpacing(0.1f);
        row.addView(t);
        return row;
    }

    private View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(0x1AFFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        return v;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }
}
