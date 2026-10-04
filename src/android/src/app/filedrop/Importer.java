package app.filedrop;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Copies what the user picked or shared into the outbox, one import at a
// time, then wakes the sender. A copy is the app's own: the picked file may
// move or vanish before the laptop is back.
public class Importer
{
    public interface Done
    {
        void on_done(int added, String error);
    }

    private static class Entry
    {
        final Uri uri;
        final String path;
        final long mtime;

        Entry(Uri uri, String path, long mtime)
        {
            this.uri = uri;
            this.path = path;
            this.mtime = mtime;
        }
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    // "adding 3 of 10", or null when idle; the screens show it.
    static volatile String status = null;

    public static void add_documents(Context context, List<Uri> uris, Done done)
    {
        Context app = context.getApplicationContext();
        executor.execute(() -> {
            List<Entry> entries = new ArrayList<>();
            for (Uri uri : uris) {
                entries.add(entry_of(app.getContentResolver(), uri));
            }
            copy(app, entries, done);
        });
    }

    // A whole folder, with its subfolders: on the laptop it keeps its tree.
    public static void add_tree(Context context, Uri tree, Done done)
    {
        Context app = context.getApplicationContext();
        executor.execute(() -> {
            status = "reading the folder";
            List<Entry> entries = new ArrayList<>();
            try {
                String root_id = DocumentsContract.getTreeDocumentId(tree);
                String root_name = document_name(app.getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, root_id));
                tree_walk(app.getContentResolver(), tree, root_id, root_name, entries);
            }
            catch (Exception error) {
                status = null;
                post(done, 0, "cannot read the folder: " + error.getMessage());
                return;
            }
            copy(app, entries, done);
        });
    }

    public static void add_text(Context context, String text, Done done)
    {
        Context app = context.getApplicationContext();
        executor.execute(() -> {
            try {
                InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
                Settings.outbox(app).add(in, "note_" + Outbox.time_stamp() + ".txt", System.currentTimeMillis(), Outbox.today());
                SendJob.kick(app);
                post(done, 1, null);
            }
            catch (Exception error) {
                post(done, 0, "cannot add the text: " + error.getMessage());
            }
        });
    }

    // A photo or a recording finished in staging.
    public static void add_staged(Context context, File file, String name, Done done)
    {
        Context app = context.getApplicationContext();
        executor.execute(() -> {
            try {
                Settings.outbox(app).add_file(file, name, file.lastModified(), Outbox.today());
                SendJob.kick(app);
                post(done, 1, null);
            }
            catch (Exception error) {
                post(done, 0, "cannot add " + name + ": " + error.getMessage());
            }
        });
    }

    private static void copy(Context app, List<Entry> entries, Done done)
    {
        Outbox outbox = Settings.outbox(app);
        String day = Outbox.today();
        int added = 0;
        String failed = null;
        for (int i = 0; i < entries.size(); ++i) {
            Entry entry = entries.get(i);
            status = "adding " + (i + 1) + " of " + entries.size();
            try {
                InputStream in = app.getContentResolver().openInputStream(entry.uri);
                try {
                    outbox.add(in, entry.path, entry.mtime, day);
                }
                finally {
                    in.close();
                }
                added += 1;
            }
            catch (Exception error) {
                failed = "cannot add " + entry.path + ": " + error.getMessage();
            }
            // The first files can go while the rest are still copied.
            if (added == 1) {
                SendJob.kick(app);
            }
        }
        status = null;
        if (added > 0) {
            SendJob.kick(app);
        }
        post(done, added, failed);
    }

    private static void tree_walk(ContentResolver resolver, Uri tree, String parent_id, String prefix, List<Entry> out)
    {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent_id);
        String[] columns = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        Cursor cursor = resolver.query(children, columns, null, null, null);
        if (cursor == null) {
            return;
        }
        try {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String path = prefix + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    tree_walk(resolver, tree, id, path, out);
                    continue;
                }
                long mtime = cursor.isNull(3) ? System.currentTimeMillis() : cursor.getLong(3);
                out.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), path, mtime));
            }
        }
        finally {
            cursor.close();
        }
    }

    // The name the picker shows, and the file's own time when the provider knows it.
    private static Entry entry_of(ContentResolver resolver, Uri uri)
    {
        String name = null;
        long mtime = System.currentTimeMillis();
        try {
            Cursor cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int name_column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int mtime_column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                        if ((name_column >= 0) && !cursor.isNull(name_column)) {
                            name = cursor.getString(name_column);
                        }
                        if ((mtime_column >= 0) && !cursor.isNull(mtime_column)) {
                            mtime = cursor.getLong(mtime_column);
                        }
                    }
                }
                finally {
                    cursor.close();
                }
            }
        }
        catch (Exception error) {
            // a provider with no columns: the file still goes, named by its time
        }
        if ((name == null) || name.isEmpty()) {
            name = "file_" + Outbox.time_stamp();
        }
        return new Entry(uri, name, mtime);
    }

    private static String document_name(ContentResolver resolver, Uri uri)
    {
        Cursor cursor = resolver.query(uri, new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (cursor == null) {
            return "folder";
        }
        try {
            return cursor.moveToFirst() ? cursor.getString(0) : "folder";
        }
        finally {
            cursor.close();
        }
    }

    private static void post(Done done, int added, String error)
    {
        if (done == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> done.on_done(added, error));
    }
}
