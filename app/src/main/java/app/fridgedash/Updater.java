package app.fridgedash;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Self-update from GitHub Releases.
 *
 *  - Reads version.json from the latest public release.
 *  - Downloads the APK into the app's private cache and checks its SHA-256,
 *    package name and version before handing it to Android's installer.
 *  - Android itself also refuses the update unless it is signed with the
 *    same key as the installed app.
 *
 * No background service: checks only happen while FridgeDash is open.
 */
final class Updater {
    private Updater() {}

    static final String VERSION_URL =
            "https://github.com/armadocs/fridgedash/releases/latest/download/version.json";

    private static final String PREFS = "fridgedash";
    private static final String KEY_LAST_CHECK = "update_last_check";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000; // 6 hours

    static final class Info {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final long size;
        final String notes;

        Info(JSONObject o) {
            versionCode = o.optInt("versionCode");
            versionName = o.optString("versionName");
            apkUrl = o.optString("apkUrl");
            sha256 = o.optString("sha256").toLowerCase();
            size = o.optLong("size");
            notes = o.optString("notes");
        }
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    static int installedVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String installedVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    static boolean autoCheckDue(Context ctx) {
        long last = prefs(ctx).getLong(KEY_LAST_CHECK, 0);
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS;
    }

    /** Network call: run off the UI thread. */
    static Info fetchLatest(Context ctx) throws IOException {
        HttpURLConnection c = open(VERSION_URL);
        try {
            check(c);
            byte[] body = readAll(c.getInputStream(), 64 * 1024);
            prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
            return new Info(new JSONObject(new String(body, "UTF-8")));
        } catch (org.json.JSONException e) {
            throw new IOException("Bad version.json: " + e.getMessage());
        } finally {
            c.disconnect();
        }
    }

    static boolean isNewer(Context ctx, Info info) {
        return info != null && info.versionCode > installedVersionCode(ctx);
    }

    static File apkFile(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "fridgedash.apk");
    }

    /** Network call: run off the UI thread. Returns the verified APK file. */
    static File download(Context ctx, Info info, Progress progress) throws IOException {
        File out = apkFile(ctx);
        File tmp = new File(out.getPath() + ".part");
        HttpURLConnection c = open(info.apkUrl);
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable");
        }
        try {
            check(c);
            long total = c.getContentLength() > 0 ? c.getContentLength() : info.size;
            long done = 0;
            byte[] buf = new byte[16 * 1024];
            try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress(done, total);
                }
            }
        } finally {
            c.disconnect();
        }

        String got = hex(sha.digest());
        if (!info.sha256.isEmpty() && !info.sha256.equals(got)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Download corrupted (checksum mismatch)");
        }

        PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(tmp.getPath(), 0);
        if (pi == null || !ctx.getPackageName().equals(pi.packageName)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Downloaded file is not a FridgeDash APK");
        }
        if (pi.versionCode <= installedVersionCode(ctx)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Downloaded version is not newer");
        }

        //noinspection ResultOfMethodCallIgnored
        out.delete();
        if (!tmp.renameTo(out)) throw new IOException("Couldn't save the update");
        return out;
    }

    /** Android 8+ needs a one-time "Install unknown apps" permission for FridgeDash. */
    static boolean canInstall(Context ctx) {
        return Build.VERSION.SDK_INT < 26 || ctx.getPackageManager().canRequestPackageInstalls();
    }

    static Intent unknownSourcesSettings(Context ctx) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + ctx.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static Intent installIntent() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ApkProvider.CONTENT_URI, ApkProvider.APK_MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    // ------------------------------------------------------------------ helpers

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true); // github.com → release-assets host (both https)
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "FridgeDash-Updater");
        c.setRequestProperty("Cache-Control", "no-cache");
        return c;
    }

    private static void check(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code == 404) throw new IOException("No release published yet (404)");
        if (code != 200) throw new IOException("Server answered HTTP " + code);
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > limit) throw new IOException("Response too large");
            }
            return bos.toByteArray();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
