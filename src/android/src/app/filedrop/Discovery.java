package app.filedrop;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Asks the whole wifi "file-drop?" over UDP. The laptop answers with its
// port; the answer's source address is the laptop itself.
public class Discovery
{
    private static final String question = "file-drop?";

    // "http://192.168.1.23:8080", or null when nobody answered in time.
    public static String ask(int port, int timeout_ms, String... extra_targets)
    {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(timeout_ms);
            byte[] bytes = question.getBytes("UTF-8");
            for (InetAddress target : targets(extra_targets)) {
                try {
                    socket.send(new DatagramPacket(bytes, bytes.length, target, port));
                }
                catch (Exception error) {
                    // one interface refused a broadcast; the others may not
                }
            }
            byte[] buffer = new byte[1024];
            long deadline = System.currentTimeMillis() + timeout_ms;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                }
                catch (SocketTimeoutException error) {
                    return null;
                }
                String answer = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                Matcher match = Pattern.compile("\"port\":(\\d+)").matcher(answer);
                if (answer.contains("\"name\":\"file-drop\"") && match.find()) {
                    return "http://" + packet.getAddress().getHostAddress() + ":" + match.group(1);
                }
            }
            return null;
        }
        catch (Exception error) {
            return null;
        }
        finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    // 255.255.255.255, plus each interface's own broadcast address: some
    // routers pass only the latter.
    private static List<InetAddress> targets(String[] extra_targets) throws Exception
    {
        List<InetAddress> out = new ArrayList<>();
        for (String target : extra_targets) {
            out.add(InetAddress.getByName(target));
        }
        out.add(InetAddress.getByName("255.255.255.255"));
        for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!face.isUp() || face.isLoopback()) {
                continue;
            }
            for (InterfaceAddress address : face.getInterfaceAddresses()) {
                if (address.getBroadcast() != null) {
                    out.add(address.getBroadcast());
                }
            }
        }
        return out;
    }
}
