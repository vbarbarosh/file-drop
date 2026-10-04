package app.filedrop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

// Everything the app sends waits here first, one folder per file, until the
// laptop confirms it. A folder is built under a ".tmp-" name and renamed into
// place, so a half-copied file is never sent; the names sort by time, so
// files go in the order they were added.
public class Outbox
{
    private static final long tmp_max_idle_ms = 10*60*1000;
    private static final SecureRandom random = new SecureRandom();
    private static final AtomicInteger id_counter = new AtomicInteger();

    private final File dir;

    public Outbox(File dir)
    {
        this.dir = dir;
        dir.mkdirs();
    }

    // Copies a stream into a new item. A failed copy leaves nothing behind.
    public OutboxItem add(InputStream in, String path, long mtime, String day) throws IOException
    {
        File tmp = tmp_dir_create();
        try {
            long size = 0;
            OutputStream out = new FileOutputStream(new File(tmp, "data"));
            try {
                byte[] chunk = new byte[65536];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    out.write(chunk, 0, read);
                    size += read;
                }
            }
            finally {
                out.close();
            }
            return item_commit(tmp, path, size, mtime, day);
        }
        catch (IOException | RuntimeException error) {
            delete_tree(tmp);
            throw error;
        }
    }

    // Moves a finished file, a photo or a recording made in staging, into a new item.
    public OutboxItem add_file(File file, String path, long mtime, String day) throws IOException
    {
        File tmp = tmp_dir_create();
        long size = file.length();
        if (!file.renameTo(new File(tmp, "data"))) {
            delete_tree(tmp);
            throw new IOException("cannot move " + file);
        }
        return item_commit(tmp, path, size, mtime, day);
    }

    // Where the camera and the recorder write before a file joins the outbox.
    public File staging_dir()
    {
        File out = new File(dir, ".staging");
        out.mkdirs();
        return out;
    }

    public List<OutboxItem> list()
    {
        List<OutboxItem> out = new ArrayList<>();
        String[] names = dir.list();
        if (names == null) {
            return out;
        }
        Arrays.sort(names);
        for (String name : names) {
            if (name.startsWith(".")) {
                continue;
            }
            try {
                out.add(OutboxItem.read(new File(dir, name)));
            }
            catch (Exception error) {
                // an item with no readable meta is not ours to send
            }
        }
        return out;
    }

    // Cheap enough for a screen that refreshes twice a second: no meta is read.
    public int count()
    {
        int out = 0;
        String[] names = dir.list();
        if (names == null) {
            return 0;
        }
        for (String name : names) {
            if (!name.startsWith(".")) {
                out += 1;
            }
        }
        return out;
    }

    public long bytes()
    {
        long out = 0;
        for (OutboxItem item : list()) {
            out += item.size;
        }
        return out;
    }

    public void remove(OutboxItem item)
    {
        delete_tree(item.dir);
    }

    // A copy that stopped changing ten minutes ago belongs to a process that died.
    public void remove_stale_tmp()
    {
        String[] names = dir.list();
        if (names == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String name : names) {
            File tmp = new File(dir, name);
            if (name.startsWith(".tmp-") && ((now - tree_last_modified(tmp)) > tmp_max_idle_ms)) {
                delete_tree(tmp);
            }
        }
    }

    public static String today()
    {
        return LocalDate.now().toString();
    }

    public static String time_stamp()
    {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
    }

    private File tmp_dir_create() throws IOException
    {
        File out = new File(dir, ".tmp-" + id_create());
        if (!out.mkdirs()) {
            throw new IOException("cannot create " + out);
        }
        return out;
    }

    private OutboxItem item_commit(File tmp, String path, long size, long mtime, String day) throws IOException
    {
        OutboxItem draft = new OutboxItem(tmp, path, size, mtime, day);
        draft.write_meta();
        File done = new File(dir, tmp.getName().substring(".tmp-".length()));
        if (!tmp.renameTo(done)) {
            delete_tree(tmp);
            throw new IOException("cannot rename " + tmp);
        }
        return OutboxItem.read(done);
    }

    // 20261005_012345_678_0042_1a2b3c4d: sorts in the order of adding, the
    // counter keeps a burst inside one millisecond in order, and the random
    // tail keeps the id unique on the server.
    private static String id_create()
    {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        StringBuilder id = new StringBuilder(new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()));
        id.append(String.format(Locale.US, "_%04d_", id_counter.incrementAndGet() % 10000));
        for (byte value : bytes) {
            id.append(String.format("%02x", value));
        }
        return id.toString();
    }

    private static long tree_last_modified(File file)
    {
        long out = file.lastModified();
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                out = Math.max(out, tree_last_modified(child));
            }
        }
        return out;
    }

    static void delete_tree(File file)
    {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete_tree(child);
            }
        }
        file.delete();
    }
}
