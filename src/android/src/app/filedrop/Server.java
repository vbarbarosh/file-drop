package app.filedrop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The laptop: who answers at an address, and how to find it when the saved
// address no longer answers.
public class Server
{
    public static final int DEFAULT_PORT = 8080;

    public final String url;
    public final String host;
    public final int apk_version;

    public Server(String url, String host, int apk_version)
    {
        this.url = url;
        this.host = host;
        this.apk_version = apk_version;
    }

    // The saved address first; then the whole wifi is asked; then, when
    // the wifi drops broadcasts, the addresses next to the phone's are
    // scanned. Null: no laptop.
    public static Server find(String saved_url, String... extra_targets)
    {
        if (saved_url != null) {
            Server saved = info_get(saved_url);
            if (saved != null) {
                return saved;
            }
        }
        List<Server> found = find_all(port_of(saved_url), extra_targets);
        return found.isEmpty() ? null : found.get(0);
    }

    // Every laptop that answers: the broadcast's first, else the scan's.
    public static List<Server> find_all(int port, String... extra_targets)
    {
        List<Server> out = new ArrayList<>();
        try {
            Scan.progress = "asking the wifi…";
            String asked_url = Discovery.ask(port, 1500, extra_targets);
            Server asked = (asked_url == null) ? null : info_get(asked_url);
            if (asked != null) {
                out.add(asked);
                return out;
            }
            out.addAll(Scan.run(port, 700));
            return out;
        }
        finally {
            Scan.progress = null;
        }
    }

    // GET /info; null when nothing, or something that is not file-drop, answers.
    public static Server info_get(String url)
    {
        return info_get(url, 3000);
    }

    public static Server info_get(String url, int connect_ms)
    {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url + "/info").openConnection();
            connection.setConnectTimeout(connect_ms);
            connection.setReadTimeout(5000);
            int code = connection.getResponseCode();
            String body = body_read(connection.getInputStream());
            connection.disconnect();
            if ((code != 200) || !body.contains("\"name\":\"file-drop\"")) {
                return null;
            }
            String host = json_string_read(body, "host");
            String apk_version = json_number_read(body, "apk_version");
            return new Server(url, (host == null) ? url : host, (apk_version == null) ? 0 : Integer.parseInt(apk_version));
        }
        catch (Exception error) {
            return null;
        }
    }

    // POST /client-log: the event shows in the laptop's console. Best effort.
    public static void client_log_post(String url, String version, String event_name, String detail)
    {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url + "/client-log").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            String json = "{\"version\":\"" + json_escape(version) + "\",\"event\":\"" + json_escape(event_name)
                + "\",\"detail\":\"" + json_escape(detail) + "\"}";
            OutputStream body_stream = connection.getOutputStream();
            body_stream.write(json.getBytes("UTF-8"));
            body_stream.close();
            connection.getResponseCode();
            connection.disconnect();
        }
        catch (Exception error) {
            // the console misses one line; nothing else depends on it
        }
    }

    public static int port_of(String url)
    {
        if (url == null) {
            return DEFAULT_PORT;
        }
        try {
            int port = new URL(url).getPort();
            return (port > 0) ? port : DEFAULT_PORT;
        }
        catch (Exception error) {
            return DEFAULT_PORT;
        }
    }

    static String body_read(InputStream in) throws IOException
    {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            body.write(chunk, 0, read);
        }
        in.close();
        return body.toString("UTF-8");
    }

    static String json_string_read(String json, String key)
    {
        Matcher match = Pattern.compile("\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return match.find() ? match.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    static String json_number_read(String json, String key)
    {
        Matcher match = Pattern.compile("\"" + key + "\":(\\d+)").matcher(json);
        return match.find() ? match.group(1) : null;
    }

    static String json_escape(String value)
    {
        return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
