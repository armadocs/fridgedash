package app.fridgedash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Famous public-domain paintings for art mode, from open museum collections:
 * the Art Institute of Chicago (main) and the Metropolitan Museum of Art (backup).
 * The list is refreshed weekly; images are downloaded one at a time.
 */
final class Art {

    static final class Piece {
        String title = "", artist = "", date = "", museum = "", image = "";
        double ratio;   // width / height of the painting (0 = unknown)
    }

    /** Within 25% of the screen's shape: fills the screen with only a little trimmed off the edges. */
    static final double FIT = Math.log(1.25);

    static boolean fits(double ratio, double screen) {
        return ratio > 0 && Math.abs(Math.log(ratio / screen)) <= FIT;
    }

    private static final long LIST_MAX_AGE = 7L * 24 * 3600 * 1000;
    private static final String UA = "FridgeDash art mode (personal home display)";
    /** Image servers sit behind bot protection that turns away non-browser requests. */
    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final String KEY_LIST_V = "art_list_v";
    private static OkHttpClient http;
    private static final Random rnd = new Random();

    private static synchronized OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(40, TimeUnit.SECONDS).build();
        }
        return http;
    }

    private static File listFile(Context c) { return new File(c.getFilesDir(), "art_list.json"); }

    // ------------------------------------------------------------------ the list

    /** The collection list (downloads it when missing or a week old). Network: off the UI thread. */
    static List<Piece> list(Context c) throws Exception {
        File f = listFile(c);
        List<Piece> cached = f.exists() ? read(f) : new ArrayList<>();
        boolean current = Prefs.raw(c).getInt(KEY_LIST_V, 0) == 3; // v3 = both museums + shapes
        if (current && !cached.isEmpty() && System.currentTimeMillis() - f.lastModified() < LIST_MAX_AGE) return cached;
        List<Piece> fresh = new ArrayList<>();
        StringBuilder errors = new StringBuilder();
        try { fresh.addAll(chicago()); } catch (Exception e) { errors.append("Art Institute: ").append(e.getMessage()).append(". "); }
        // Always mix in The Met, so one museum blocking images can't leave the screen empty.
        try { fresh.addAll(met()); } catch (Exception e) { errors.append("The Met: ").append(e.getMessage()).append(". "); }
        if (fresh.isEmpty()) {
            if (!cached.isEmpty()) return cached;
            throw new IOException(errors.length() == 0 ? "no paintings found" : errors.toString().trim());
        }
        JSONArray a = new JSONArray();
        for (Piece p : fresh) {
            a.put(new JSONObject().put("t", p.title).put("a", p.artist).put("d", p.date)
                    .put("m", p.museum).put("i", p.image).put("r", p.ratio));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        }
        Prefs.raw(c).edit().putInt(KEY_LIST_V, 3).apply();
        return fresh;
    }

    private static List<Piece> read(File f) {
        List<Piece> out = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            JSONArray a = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Piece p = new Piece();
                p.title = o.optString("t"); p.artist = o.optString("a"); p.date = o.optString("d");
                p.museum = o.optString("m"); p.image = o.optString("i"); p.ratio = o.optDouble("r", 0);
                if (!p.image.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Art Institute of Chicago: public-domain paintings the museum highlights ("boosted"). */
    private static List<Piece> chicago() throws Exception {
        List<Piece> out = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            try {
                List<Piece> got = chicagoPage(page);
                out.addAll(got);
                if (got.size() < 50) break;
            } catch (Exception e) {
                if (out.isEmpty()) throw e;
                break;
            }
        }
        return out;
    }

    private static List<Piece> chicagoPage(int page) throws Exception {
        JSONObject query = new JSONObject().put("bool", new JSONObject().put("must", new JSONArray()
                .put(new JSONObject().put("term", new JSONObject().put("is_public_domain", true)))
                .put(new JSONObject().put("term", new JSONObject().put("is_boosted", true)))
                .put(new JSONObject().put("term", new JSONObject().put("artwork_type_id", 1))))); // 1 = Painting
        JSONObject body = new JSONObject()
                .put("query", query)
                .put("fields", new JSONArray().put("id").put("title").put("artist_title").put("date_display")
                        .put("image_id").put("thumbnail"))
                .put("limit", 100)
                .put("page", page);
        Request req = new Request.Builder()
                .url("https://api.artic.edu/api/v1/artworks/search")
                .header("AIC-User-Agent", UA)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                .build();
        JSONObject j = new JSONObject(text(req));
        JSONArray data = j.getJSONArray("data");
        List<Piece> out = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            String id = o.optString("image_id", "");
            if (id.isEmpty() || "null".equals(id)) continue;
            Piece p = new Piece();
            p.title = o.optString("title");
            p.artist = o.optString("artist_title", "");
            if ("null".equals(p.artist)) p.artist = "";
            p.date = o.optString("date_display", "");
            p.museum = "Art Institute of Chicago";
            p.image = "https://www.artic.edu/iiif/2/" + id + "/full/1686,/0/default.jpg";
            JSONObject th = o.optJSONObject("thumbnail");
            if (th != null && th.optDouble("height", 0) > 0) {
                // Skip small scans: they'd look soft on a big screen.
                if (Math.min(th.optDouble("width"), th.optDouble("height")) < 1000) continue;
                p.ratio = th.optDouble("width") / th.optDouble("height");
            }
            out.add(p);
        }
        return out;
    }

    /** The Met: highlighted European paintings with open-access images. */
    private static List<Piece> met() throws Exception {
        // European paintings (11) and the American Wing (1, rich in wide landscapes).
        List<Integer> ids = new ArrayList<>();
        for (int dept : new int[]{11, 1}) {
            JSONObject s = new JSONObject(text(new Request.Builder()
                    .url("https://collectionapi.metmuseum.org/public/collection/v1/search?departmentId=" + dept
                            + "&isHighlight=true&hasImages=true&q=painting")
                    .header("User-Agent", UA).build()));
            JSONArray a = s.optJSONArray("objectIDs");
            if (a != null) for (int i = 0; i < a.length() && i < 130; i++) ids.add(a.getInt(i));
        }
        final List<Piece> out = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        for (final int id : ids) {
            pool.execute(() -> {
                try {
                    JSONObject o = new JSONObject(text(new Request.Builder()
                            .url("https://collectionapi.metmuseum.org/public/collection/v1/objects/" + id)
                            .header("User-Agent", UA).build()));
                    String img = o.optString("primaryImage", "");
                    if (!o.optBoolean("isPublicDomain") || img.isEmpty()) return;
                    if (!o.optString("classification").toLowerCase(java.util.Locale.ROOT).contains("painting")) return;
                    Piece p = new Piece();
                    p.title = o.optString("title");
                    p.artist = o.optString("artistDisplayName");
                    p.date = o.optString("objectDate");
                    p.museum = "The Metropolitan Museum of Art";
                    p.image = img;
                    p.ratio = metRatio(o);
                    out.add(p);
                } catch (Exception ignored) {
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(120, TimeUnit.SECONDS);
        return new ArrayList<>(out);
    }

    /** Shape from the Met's physical measurements (the painting's own height and width). */
    private static double metRatio(JSONObject o) {
        JSONArray m = o.optJSONArray("measurements");
        if (m == null) return 0;
        for (int i = 0; i < m.length(); i++) {
            JSONObject e = m.optJSONObject(i);
            JSONObject em = e == null ? null : e.optJSONObject("elementMeasurements");
            if (em == null) continue;
            double hh = em.optDouble("Height", 0), ww = em.optDouble("Width", 0);
            if (hh > 0 && ww > 0) return ww / hh;
        }
        return 0;
    }

    // ------------------------------------------------------------------ images

    /** Picks a random painting (not the last one) and decodes it for a w×h screen. */
    static Object[] next(Context c, int w, int h, String avoid) throws Exception {
        List<Piece> everything = list(c);
        if (everything.isEmpty()) throw new IOException("no paintings");
        // Only paintings shaped like the screen; if there are too few, at least the same orientation.
        double screen = w / (double) h;
        List<Piece> all = new ArrayList<>();
        for (Piece p : everything) if (fits(p.ratio, screen)) all.add(p);
        if (all.size() < 8) {
            all.clear();
            for (Piece p : everything) if (p.ratio > 0 && (p.ratio > 1) == (screen > 1)) all.add(p);
        }
        if (all.isEmpty()) all = everything;
        StringBuilder errs = new StringBuilder();
        for (int attempt = 0; attempt < 8; attempt++) {
            Piece p = all.get(rnd.nextInt(all.size()));
            if (p.image.equals(avoid) && all.size() > 1) continue;
            // The Art Institute caches 843-px-wide images; larger ones are sometimes refused.
            String[] urls = p.image.contains("/full/1686,/")
                    ? new String[]{p.image, p.image.replace("/full/1686,/", "/full/843,/")}
                    : new String[]{p.image};
            for (String url : urls) {
                try {
                    Request.Builder rb = new Request.Builder().url(url)
                            .header("User-Agent", BROWSER_UA)
                            .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
                    if (url.contains("artic.edu")) {
                        rb.header("AIC-User-Agent", UA).header("Referer", "https://www.artic.edu/");
                    }
                    Bitmap bmp = decode(bytes(rb.build()), w, h);
                    if (bmp == null) continue;
                    // Too small to fill this screen sharply? Try another painting.
                    double scale = Math.max(w / (double) bmp.getWidth(), h / (double) bmp.getHeight());
                    if (scale > 2.3) { bmp.recycle(); errs.append("low resolution; "); continue; }
                    return new Object[]{p, bmp};
                } catch (Exception e) {
                    if (errs.length() < 120) errs.append(Uri.parse(url).getHost()).append(" ").append(e.getMessage()).append("; ");
                }
            }
        }
        throw new IOException(errs.length() == 0 ? "couldn't load a painting" : errs.toString());
    }

    private static Bitmap decode(byte[] b, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }

    /** A dark, heavily blurred copy for the gallery "matte" behind the painting. */
    static Bitmap matte(Bitmap art) {
        int w = Math.max(8, art.getWidth() / 24), h = Math.max(8, art.getHeight() / 24);
        Bitmap small = Bitmap.createScaledBitmap(art, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
        Wallpaper.blur(small, 3, 3);
        return small;
    }

    private static String text(Request r) throws IOException {
        return new String(bytes(r), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(Request r) throws IOException {
        try (Response resp = http().newCall(r).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            return resp.body() == null ? new byte[0] : resp.body().bytes();
        }
    }
}
