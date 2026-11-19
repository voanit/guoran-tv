package cn.guoran.tv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** 仅向系统安装器临时授予指定 APK 的只读 URI，禁止暴露其他私有文件。 */
public final class ApkProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) throws FileNotFoundException {
        if (!"cn.guoran.tv.files".equals(uri.getAuthority()) || uri.getPathSegments().size() != 2
                || !"apk".equals(uri.getPathSegments().get(0))) throw new FileNotFoundException("无效安装包地址");
        String id = uri.getPathSegments().get(1);
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new FileNotFoundException("无效安装包编号");
        File directory = new File(getContext().getFilesDir(), "uploads");
        File target = new File(directory, id + ".apk");
        try {
            if (!target.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()) || !target.isFile()) throw new FileNotFoundException("安装包不存在");
        } catch (IOException e) { throw new FileNotFoundException("无法读取安装包"); }
        return target;
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("仅允许读取");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String order) {
        try {
            File target = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "果然TV待安装应用.apk";
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = target.length();
            }
            cursor.addRow(values); return cursor;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
