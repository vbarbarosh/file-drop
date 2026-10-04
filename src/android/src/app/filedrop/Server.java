package app.filedrop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The laptop: who answers at an address, and how to find it when the saved
// address no longer answers.
public class Server
{
    public static final int default_port = 8080;

    public final String url;
    public final String host;
    public final int apk_version;

    public Server(String url, String host, int apk_version)
    {
        this.url = url;
        this.host = host;
        this.apk_version = apk_version;
    }

    // The saved address first; then the whole wifi is asked. Null: no laptop.
    public static Server find(String saved_url, String... extra_targets)
    {
        if (saved_url != null) {
            Server saved = info(saved_url);
            if (saved != null) {
                return saved;
            }
        }
        String found_url = Discovery.ask(port_of(saved_url), 1500, extra_targets);
        if (found_url == null) {
            return null;
        }
        return info(found_url);
    }

    // GET /info; null when nothing, or something that is not file-drop, answers.
    public static Server info(String url)
    {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url + "/info").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            int code = connection.getResponseCode();
            String body = read_body(connection.getInputStream());
            connection.disconnect();
            if ((code != 200) || !body.contains("\"name\":\"file-drop\"")) {
                return null;
            }
            String host = json_string(body, "host");
            String apk_version = json_number(body, "apk_version");
            return new Server(url, (host == null) ? url : host, (apk_version == null) ? 0 : Integer.parseInt(apk_version));
        }
        catch (Exception error) {
            return null;
        }
    }

    // POST /client-log: the event shows in the laptop's console. Best effort.
    public static void report(String url, String version, String event, String detail)
    {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url + "/client-log").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            String json = "{\"version\":\"" + json_escape(version) + "\",\"event\":\"" + json_escape(event)
                + "\",\"detail\":\"" + json_escape(detail) + "\"}";
            OutputStream out = connection.getOutputStream();
            out.write(json.getBytes("UTF-8"));
            out.close();
            connection.getResponseCode();
            connection.disconnect();
        }
        catch (Exception ignored) {
            // the console misses one line; nothing else depends on it
        }
    }

    public static int port_of(String url)
    {
        if (url == null) {
            return default_port;
        }
        try {
            int port = new URL(url).getPort();
            return (port > 0) ? port : default_port;
        }
        catch (Exception error) {
            return default_port;
        }
    }

    static String read_body(InputStream in) throws IOException
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

    static String json_string(String json, String key)
    {
        Matcher match = Pattern.compile("\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return match.find() ? match.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    static String json_number(String json, String key)
    {
        Matcher match = Pattern.compile("\"" + key + "\":(\\d+)").matcher(json);
        return match.find() ? match.group(1) : null;
    }

    static String json_escape(String value)
    {
        return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
