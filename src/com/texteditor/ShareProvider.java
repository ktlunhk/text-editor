package com.texteditor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal read-only provider that hands files from cache/share to other apps,
 * so the editor can "Share as file" without androidx / support-library FileProvider.
 */
public class ShareProvider extends ContentProvider {

	public static final String AUTHORITY = "com.texteditor.share";

	private File fileFor(Uri uri) throws FileNotFoundException {
		String name = uri.getLastPathSegment();
		if (name == null || name.indexOf('/') >= 0 || name.indexOf("..") >= 0) {
			throw new FileNotFoundException("Bad name");
		}
		File dir = new File(getContext().getCacheDir(), "share");
		File f = new File(dir, name);
		if (!f.isFile()) {
			throw new FileNotFoundException(name);
		}
		return f;
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		try {
			File f = fileFor(uri);
			String[] cols = projection != null ? projection
					: new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
			Object[] row = new Object[cols.length];
			for (int i = 0; i < cols.length; i++) {
				if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
					row[i] = f.getName();
				} else if (OpenableColumns.SIZE.equals(cols[i])) {
					row[i] = Long.valueOf(f.length());
				}
			}
			MatrixCursor c = new MatrixCursor(cols, 1);
			c.addRow(row);
			return c;
		} catch (FileNotFoundException e) {
			return null;
		}
	}

	@Override
	public String getType(Uri uri) {
		String name = uri.getLastPathSegment();
		if (name != null) {
			int dot = name.lastIndexOf('.');
			if (dot >= 0 && dot < name.length() - 1) {
				String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
						name.substring(dot + 1).toLowerCase());
				if (m != null) {
					return m;
				}
			}
		}
		return "text/plain";
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		return null;
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		return 0;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		return 0;
	}
}
