package app.fridgedash;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands exactly one file — the downloaded update — to Android's package
 * installer, read-only. (A minimal stand-in for AndroidX FileProvider, which
 * we avoid to keep the app dependency-free.)
 */
public class ApkProvider extends ContentProvider {
    static final String AUTHORITY = "app.fridgedash.apk";
    static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/fridgedash.apk");
    static final String APK_MIME = "application/vnd.android.package-archive";
    /** The diagnostics report, shared as a .txt file (e.g. to a phone over Bluetooth). */
    static final Uri REPORT_URI = Uri.parse("content://" + AUTHORITY + "/fridgedash-report.txt");

    static File reportFile(android.content.Context c) {
        return new File(c.getCacheDir(), "fridgedash-report.txt");
    }

    private boolean isReport(Uri uri) {
        return uri != null && uri.getPath() != null && uri.getPath().endsWith(".txt");
    }

    @Override public boolean onCreate() { return true; }

    private File file() {
        return Updater.apkFile(getContext());
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        File f = isReport(uri) ? reportFile(getContext()) : file();
        if (!f.exists()) throw new FileNotFoundException("file not available");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return isReport(uri) ? "text/plain" : APK_MIME; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        boolean rep = isReport(uri);
        File f = rep ? reportFile(getContext()) : file();
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{rep ? "fridgedash-report.txt" : "fridgedash.apk", f.length()});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
