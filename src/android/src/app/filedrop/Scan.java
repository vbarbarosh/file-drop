package app.filedrop;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// When the wifi drops broadcasts, the laptop is still one of the 254
// addresses next to the phone's own: each gets GET /info, 64 at a time.
// It needs only tcp, which the uploads need anyway.
public class Scan
{
    private static final int threads = 64;

    // Every File Drop server on the phone's own /24 networks, by address.
    public static List<Server> run(int port, int connect_ms)
    {
        return run(port, connect_ms, local_prefixes());
    }

    // prefixes like "192.168.1.": each is scanned from .1 to .254.
    public static List<Server> run(int port, int connect_ms, List<String> prefixes)
    {
        List<Server> out = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Server>> answers = new ArrayList<>();
            for (String prefix : prefixes) {
                for (int i = 1; i <= 254; ++i) {
                    String url = "http://" + prefix + i + ":" + port;
                    answers.add(executor.submit(() -> Server.info(url, connect_ms)));
                }
            }
            for (Future<Server> answer : answers) {
                try {
                    Server server = answer.get();
                    if (server != null) {
                        out.add(server);
                    }
                }
                catch (Exception error) {
                    // one address failed oddly; the others still count
                }
            }
        }
        finally {
            executor.shutdownNow();
        }
        return out;
    }

    // "192.168.1." for the phone at 192.168.1.57: private IPv4 addresses
    // only, and not the mobile network's.
    public static List<String> local_prefixes()
    {
        List<String> out = new ArrayList<>();
        for (InetAddress address : local_addresses()) {
            String text = address.getHostAddress();
            String prefix = text.substring(0, text.lastIndexOf('.') + 1);
            if (!out.contains(prefix)) {
                out.add(prefix);
            }
        }
        return out;
    }

    public static List<InetAddress> local_addresses()
    {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = face.getName();
                if (!face.isUp() || face.isLoopback() || name.startsWith("rmnet") || name.startsWith("ccmni")
                    || name.startsWith("v4-") || name.startsWith("tun") || name.startsWith("dummy")) {
                    continue;
                }
                for (InterfaceAddress address : face.getInterfaceAddresses()) {
                    InetAddress inet = address.getAddress();
                    if ((inet instanceof Inet4Address) && inet.isSiteLocalAddress()) {
                        out.add(inet);
                    }
                }
            }
        }
        catch (Exception error) {
            // no interfaces to read: nothing to scan
        }
        return out;
    }
}
