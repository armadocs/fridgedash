package app.fridgedash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

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
    }

    private static final long LIST_MAX_AGE = 7L * 24 * 3600 * 1000;
    private static final String UA = "FridgeDash art mode (personal home display)";
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
        if (!cached.isEmpty() && System.currentTimeMillis() - f.lastModified() < LIST_MAX_AGE) return cached;
        List<Piece> fresh = new ArrayList<>();
        StringBuilder errors = new StringBuilder();
        try { fresh.addAll(chicago()); } catch (Exception e) { errors.append("Art Institute: ").append(e.getMessage()).append(". "); }
        if (fresh.size() < 20) {
            try { fresh.addAll(met()); } catch (Exception e) { errors.append("The Met: ").append(e.getMessage()).append(". "); }
        }
        if (fresh.isEmpty()) {
            if (!cached.isEmpty()) return cached;
            throw new IOException(errors.length() == 0 ? "no paintings found" : errors.toString().trim());
        }
        JSONArray a = new JSONArray();
        for (Piece p : fresh) {
            a.put(new JSONObject().put("t", p.title).put("a", p.artist).put("d", p.date)
                    .put("m", p.museum).put("i", p.image));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        }
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
                p.museum = o.optString("m"); p.image = o.optString("i");
                if (!p.image.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Art Institute of Chicago: public-domain paintings the museum highlights ("boosted"). */
    private static List<Piece> chicago() throws Exception {
        JSONObject query = new JSONObject().put("bool", new JSONObject().put("must", new JSONArray()
                .put(new JSONObject().put("term", new JSONObject().put("is_public_domain", true)))
                .put(new JSONObject().put("term", new JSONObject().put("is_boosted", true)))
                .put(new JSONObject().put("term", new JSONObject().put("artwork_type_id", 1))))); // 1 = Painting
        JSONObject body = new JSONObject()
                .put("query", query)
                .put("fields", new JSONArray().put("id").put("title").put("artist_title").put("date_display").put("image_id"))
                .put("limit", 100);
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
            out.add(p);
        }
        return out;
    }

    /** The Met: highlighted European paintings with open-access images. */
    private static List<Piece> met() throws Exception {
        JSONObject s = new JSONObject(text(new Request.Builder()
                .url("https://collectionapi.metmuseum.org/public/collection/v1/search?departmentId=11&isHighlight=true&hasImages=true&q=painting")
                .header("User-Agent", UA).build()));
        JSONArray ids = s.optJSONArray("objectIDs");
        List<Piece> out = new ArrayList<>();
        if (ids == null) return out;
        for (int i = 0; i < ids.length() && out.size() < 40; i++) {
            try {
                JSONObject o = new JSONObject(text(new Request.Builder()
                        .url("https://collectionapi.metmuseum.org/public/collection/v1/objects/" + ids.getInt(i))
                        .header("User-Agent", UA).build()));
                String img = o.optString("primaryImage", "");
                if (!o.optBoolean("isPublicDomain") || img.isEmpty()) continue;
                Piece p = new Piece();
                p.title = o.optString("title");
                p.artist = o.optString("artistDisplayName");
                p.date = o.optString("objectDate");
                p.museum = "The Metropolitan Museum of Art";
                p.image = img;
                out.add(p);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ images

    /** Picks a random painting (not the last one) and decodes it for a w×h screen. */
    static Object[] next(Context c, int w, int h, String avoid) throws Exception {
        List<Piece> all = list(c);
        if (all.isEmpty()) throw new IOException("no paintings");
        Exception last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            Piece p = all.get(rnd.nextInt(all.size()));
            if (p.image.equals(avoid) && all.size() > 1) continue;
            try {
                byte[] b = bytes(new Request.Builder().url(p.image).header("User-Agent", UA).build());
                Bitmap bmp = decode(b, w, h);
                if (bmp != null) return new Object[]{p, bmp};
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("couldn't load a painting");
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
