package app.fridgedash;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Daily wallpaper: Bing's photo of the day in its 1080x1920 portrait version.
 * Downloaded at most once a day and kept on the fridge, so it still shows
 * without Wi-Fi. A bundled landscape is used until the first download.
 */
final class Wallpaper {
    private Wallpaper() {}

    private static final String BING_JSON =
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US";
    private static final String KEY_DAY = "wallpaper_day";
    private static final String KEY_CREDIT = "wallpaper_credit";
    private static final int BLUR_DOWNSCALE = 12;

    static final class Images {
        Bitmap sharp;
        Bitmap blurred;
        String credit;
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "wallpaper.jpg");
    }

    static String credit(Context c) {
        return Prefs.raw(c).getString(KEY_CREDIT, "");
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static boolean refreshDue(Context c) {
        return Prefs.dailyWallpaper(c) && !today().equals(Prefs.raw(c).getString(KEY_DAY, ""));
    }

    /** Network: call off the UI thread. Returns true when a new photo was saved. */
    static boolean refresh(Context c) throws IOException {
        JSONObject img;
        try {
            byte[] json = get(BING_JSON, 256 * 1024);
            JSONArray images = new JSONObject(new String(json, "UTF-8")).getJSONArray("images");
            img = images.getJSONObject(0);
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected photo-of-the-day data");
        }
        String base = img.optString("urlbase");
        String fallback = img.optString("url");
        byte[] photo;
        try {
            photo = get("https://www.bing.com" + base + "_1080x1920.jpg", 8 * 1024 * 1024);
        } catch (IOException portraitMissing) {
            photo = get("https://www.bing.com" + fallback, 8 * 1024 * 1024);
        }
        // Make sure it really is an image before replacing the current one.
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(photo, 0, photo.length, o);
        if (o.outWidth <= 0) throw new IOException("Downloaded photo is not an image");

        File tmp = new File(c.getFilesDir(), "wallpaper.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(photo);
        }
        if (!tmp.renameTo(file(c))) throw new IOException("Couldn't save the photo");

        String title = img.optString("title");
        String copyright = img.optString("copyright");
        String credit = title.isEmpty() ? copyright : title + " · " + copyright;
        Prefs.raw(c).edit().putString(KEY_DAY, today()).putString(KEY_CREDIT, credit).apply();
        return true;
    }

    /** Decodes the current wallpaper cropped to w×h, plus its blurred copy. Off the UI thread. */
    static Images load(Context c, int w, int h) {
        Images out = new Images();
        File f = file(c);
        Bitmap src = null;
        if (Prefs.dailyWallpaper(c) && f.exists()) src = decode(f.getPath(), null, c, w, h);
        if (src == null) {
            src = decode(null, c, c, w, h);
            out.credit = "";
        } else {
            out.credit = credit(c);
        }
        if (src == null) return out;

        out.sharp = centerCrop(src, w, h, Bitmap.Config.RGB_565);
        if (src != out.sharp) src.recycle();

        int bw = Math.max(8, w / BLUR_DOWNSCALE), bh = Math.max(8, h / BLUR_DOWNSCALE);
        Bitmap small = Bitmap.createScaledBitmap(out.sharp, bw, bh, true).copy(Bitmap.Config.ARGB_8888, true);
        blur(small, 3, 3);
        out.blurred = small;
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static Bitmap decode(String path, Context resCtx, Context c, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        if (path != null) BitmapFactory.decodeFile(path, o);
        else BitmapFactory.decodeResource(c.getResources(), R.drawable.default_wallpaper, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inScaled = false;
        return path != null ? BitmapFactory.decodeFile(path, o)
                : BitmapFactory.decodeResource(c.getResources(), R.drawable.default_wallpaper, o);
    }

    private static Bitmap centerCrop(Bitmap src, int w, int h, Bitmap.Config cfg) {
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        Bitmap out = Bitmap.createBitmap(w, h, cfg);
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Simple separable box blur, several passes ≈ gaussian. Fine for tiny bitmaps. */
    static void blur(Bitmap b, int radius, int passes) {
        int w = b.getWidth(), h = b.getHeight();
        int[] a = new int[w * h], t = new int[w * h];
        b.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            pass(a, t, w, h, radius, true);
            pass(t, a, w, h, radius, false);
        }
        b.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void pass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.min(len - 1, Math.max(0, i + k));
                    int px = horizontal ? src[line * w + j] : src[j * w + line];
                    rs += (px >> 16) & 0xFF;
                    gs += (px >> 8) & 0xFF;
                    bs += px & 0xFF;
                    n++;
                }
                int idx = horizontal ? line * w + i : i * w + line;
                dst[idx] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
            }
        }
    }

    private static byte[] get(String url, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "FridgeDash");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > limit) throw new IOException("Response too large");
                }
                return bos.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
