package org.scrcpy.android;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.OpenableColumns;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;

public final class LogProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/debug.log".equals(uri.getPath()) || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(DebugLog.file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String sort) {
        if (!"/debug.log".equals(u.getPath())) throw new IllegalArgumentException("Unknown log URI");
        String[] columns = p == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : p;
        MatrixCursor cursor = new MatrixCursor(columns); Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "scrcpy-android-debug.log";
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = DebugLog.file(getContext()).length();
        }
        cursor.addRow(values); return cursor;
    }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
