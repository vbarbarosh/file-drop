package app.filedrop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

// One file waiting in the outbox: its folder holds the bytes ("data") and
// what the laptop needs to know about them ("meta.properties").
public class OutboxItem
{
    public final File dir;
    public final String id;
    public final String path;
    public final long size;
    public final long mtime;
    public final String day;

    public OutboxItem(File dir, String path, long size, long mtime, String day)
    {
        this.dir = dir;
        this.id = dir.getName();
        this.path = path;
        this.size = size;
        this.mtime = mtime;
        this.day = day;
    }

    public File data_file()
    {
        return new File(dir, "data");
    }

    public String name()
    {
        int slash = path.lastIndexOf('/');
        return (slash < 0) ? path : path.substring(slash + 1);
    }

    public static OutboxItem read(File dir) throws IOException
    {
        Properties meta = new Properties();
        InputStream in = new FileInputStream(new File(dir, "meta.properties"));
        try {
            meta.load(in);
        }
        finally {
            in.close();
        }
        long size = Long.parseLong(meta.getProperty("size"));
        long mtime = Long.parseLong(meta.getProperty("mtime"));
        return new OutboxItem(dir, meta.getProperty("path"), size, mtime, meta.getProperty("day"));
    }

    public void write_meta() throws IOException
    {
        Properties meta = new Properties();
        meta.setProperty("path", path);
        meta.setProperty("size", String.valueOf(size));
        meta.setProperty("mtime", String.valueOf(mtime));
        meta.setProperty("day", day);
        OutputStream out = new FileOutputStream(new File(dir, "meta.properties"));
        try {
            meta.store(out, null);
        }
        finally {
            out.close();
        }
    }
}
