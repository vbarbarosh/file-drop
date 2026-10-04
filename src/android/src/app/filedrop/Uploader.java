package app.filedrop;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;

// Sends one outbox item. The server keeps every byte it receives under the
// item's id, so after a lost connection the upload asks where it stopped and
// continues from there; a finished id answers with where the file went.
public class Uploader
{
    public interface Progress
    {
        void on_progress(OutboxItem item, long sent, long size);
    }

    private static final int failures_max = 3;
    private static final int busy_max = 45;

    // The path the file got on the laptop. Throws when the laptop is gone.
    public static String send(String server, OutboxItem item, Progress progress) throws IOException, InterruptedException
    {
        if (item.data_file().length() != item.size) {
            throw new IllegalStateException("outbox item " + item.id + " is damaged");
        }
        int failures = 0;
        int busy = 0;
        long last_offset = -1;
        while (true) {
            try {
                long[] offset = new long[1];
                String saved = state_get(server, item, offset);
                if (saved != null) {
                    return saved;
                }
                // A link that drops now and then but moves forward is never given up on.
                if (offset[0] > last_offset) {
                    failures = 0;
                    last_offset = offset[0];
                }
                Answer answer = post_from(server, item, offset[0], progress);
                if (answer.saved != null) {
                    return answer.saved;
                }
                // 409: another connection of ours still holds the id, until
                // the server notices it is dead.
                if (answer.code == 409) {
                    busy += 1;
                    if (busy > busy_max) {
                        throw new IOException("upload stays busy");
                    }
                    Thread.sleep(2000);
                }
            }
            catch (IOException error) {
                failures += 1;
                if (failures >= failures_max) {
                    throw error;
                }
                Thread.sleep(2000*failures);
            }
        }
    }

    private static class Answer
    {
        final int code;
        final String saved;

        Answer(int code, String saved)
        {
            this.code = code;
            this.saved = saved;
        }
    }

    // GET /upload/:id: the saved path, or null with the offset to go on from.
    private static String state_get(String server, OutboxItem item, long[] offset) throws IOException
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + "/upload/" + item.id).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        int code = connection.getResponseCode();
        if (code != 200) {
            connection.disconnect();
            throw new IOException("http " + code);
        }
        Server.read_body(connection.getInputStream());
        offset[0] = Long.parseLong(connection.getHeaderField("x-upload-offset"));
        String saved = header_decode(connection.getHeaderField("x-file-saved"));
        connection.disconnect();
        return saved;
    }

    // POST /upload/:id with the file from offset on.
    private static Answer post_from(String server, OutboxItem item, long offset, Progress progress) throws IOException
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + "/upload/" + item.id).openConnection();
        connection.setConnectTimeout(5000);
        // The last byte makes the server hash the file before it answers.
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setFixedLengthStreamingMode(item.size - offset);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setRequestProperty("x-upload-offset", String.valueOf(offset));
        connection.setRequestProperty("x-file-size", String.valueOf(item.size));
        connection.setRequestProperty("x-file-path", header_encode(item.path));
        connection.setRequestProperty("x-file-mtime", String.valueOf(item.mtime));
        connection.setRequestProperty("x-file-day", item.day);

        InputStream in = new FileInputStream(item.data_file());
        try {
            in.skip(offset);
            OutputStream out = connection.getOutputStream();
            byte[] chunk = new byte[65536];
            long sent = offset;
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
                sent += read;
                progress.on_progress(item, sent, item.size);
            }
            out.close();
        }
        finally {
            in.close();
        }

        int code = connection.getResponseCode();
        String saved = header_decode(connection.getHeaderField("x-file-saved"));
        connection.disconnect();
        if ((code != 200) && (code != 409)) {
            throw new IOException("http " + code);
        }
        return new Answer(code, saved);
    }

    // encodeURIComponent, which the server undoes: URLEncoder writes a space as "+".
    static String header_encode(String value) throws IOException
    {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    static String header_decode(String value) throws IOException
    {
        return (value == null) ? null : URLDecoder.decode(value, "UTF-8");
    }
}
