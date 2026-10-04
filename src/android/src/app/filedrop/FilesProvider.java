package app.filedrop;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

// Hands two kinds of files to other apps:
//   content://app.filedrop.files/staging/<name>  the camera writes a photo there
//   content://app.filedrop.files/update.apk      the system installer reads the update
public class FilesProvider extends ContentProvider
{
    public static final String authority = "app.filedrop.files";

    public static Uri staging_uri(String name)
    {
        return Uri.parse("content://" + authority + "/staging/" + Uri.encode(name));
    }

    public static Uri update_uri()
    {
        return Uri.parse("content://" + authority + "/update.apk");
    }

    @Override
    public boolean onCreate()
    {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException
    {
        File file = file_of(uri);
        if (file.getName().equals("update.apk")) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri)
    {
        String name = uri.getLastPathSegment();
        if (name == null) {
            return "application/octet-stream";
        }
        if (name.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        if (name.endsWith(".jpg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    // Some camera apps ask for the name and size of the file they write.
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order)
    {
        File file;
        try {
            file = file_of(uri);
        }
        catch (FileNotFoundException error) {
            return null;
        }
        MatrixCursor out = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        out.addRow(new Object[] {file.getName(), file.length()});
        return out;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values)
    {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args)
    {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args)
    {
        return 0;
    }

    private File file_of(Uri uri) throws FileNotFoundException
    {
        String path = uri.getPath();
        if ("/update.apk".equals(path)) {
            return new File(getContext().getCacheDir(), "update.apk");
        }
        String name = uri.getLastPathSegment();
        if ((path != null) && path.startsWith("/staging/") && (name != null) && !name.contains("/") && !name.startsWith(".")) {
            return new File(Settings.outbox(getContext()).staging_dir(), name);
        }
        throw new FileNotFoundException(String.valueOf(uri));
    }
}
