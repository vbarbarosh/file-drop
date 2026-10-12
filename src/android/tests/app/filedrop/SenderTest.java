package app.filedrop;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// The sending core against the real server (src/http/index.js), on the JVM:
// no Android device, the same classes the app runs.
public final class SenderTest
{
    private static int checks;
    private static File repo;
    private static File work;

    private static void check(boolean condition, String message)
    {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception
    {
        repo = new File(args[0]).getCanonicalFile();
        work = Files.createTempDirectory("file-drop-sender-test").toFile();
        try {
            test_outbox_waits_for_the_server();
            test_discovery_finds_the_server();
            test_scan_finds_the_server();
            test_cut_connections_resume();
            test_lost_server_keeps_the_item();
            test_stop_ends_the_upload_mid_file();
            test_outbox_add_failure_leaves_nothing();
        }
        finally {
            Outbox.tree_delete(work);
        }
        System.out.println("Passed " + checks + " sender checks");
    }

    // No laptop: the files wait. The laptop comes: they go, and leave the phone.
    private static void test_outbox_waits_for_the_server() throws Exception
    {
        Outbox outbox = new Outbox(new File(work, "outbox-1"));
        byte[] photo = random_bytes_create(2_000_000);
        outbox.add(new ByteArrayInputStream("buy milk".getBytes(StandardCharsets.UTF_8)), "note_2026-10-05_00-30-00.txt", 1_700_000_000_000L, "2026-10-05");
        outbox.add(new ByteArrayInputStream(photo), "photo 1.jpg", 1_700_000_000_000L, "2026-10-05");
        outbox.add(new ByteArrayInputStream(photo), "Trip/день 1/photo 1.jpg", 1_700_000_000_000L, "2026-10-05");

        int port = free_port_find();
        Recorder recorder = new Recorder();
        Sender.Result offline = Sender.run(outbox, "http://127.0.0.1:" + port, recorder, new AtomicBoolean(), "127.0.0.1");
        check(offline == Sender.Result.no_server, "no server: " + offline);
        check(outbox.list().size() == 3, "the files wait in the outbox");

        File data = new File(work, "data-1");
        Process server = server_start(port, data);
        try {
            Sender.Result online = Sender.run(outbox, "http://127.0.0.1:" + port, recorder, new AtomicBoolean(), "127.0.0.1");
            check(online == Sender.Result.sent_all, "server: " + online);
            check(outbox.list().isEmpty(), "sent files leave the phone");
            check(recorder.sent.equals(Arrays.asList(
                "note_2026-10-05_00-30-00.txt",
                "photo 1.jpg",
                "Trip/день 1/photo 1.jpg")), "sent in order: " + recorder.sent);
            check(Arrays.equals(Files.readAllBytes(new File(data, "Trip/день 1/photo 1.jpg").toPath()), photo), "a folder file arrives whole");
            check(new File(data, "photo 1.jpg").lastModified() == 1_700_000_000_000L, "the phone time is kept");
        }
        finally {
            server.destroy();
        }
    }

    // The saved address is dead; the laptop is found by asking the network.
    private static void test_discovery_finds_the_server() throws Exception
    {
        int port = free_port_find();
        Process server = server_start(port, new File(work, "data-2"));
        try {
            Server found = Server.find("http://127.0.0.1:" + port + "0", "127.0.0.1");
            check(found == null, "a wrong port is not found by asking another one");
            String asked = Discovery.ask(port, 1500, "127.0.0.1");
            check(("http://127.0.0.1:" + port).equals(asked), "discovery answer: " + asked);
            Server info = Server.info_get(asked);
            check((info != null) && (info.apk_version == 0) && !info.host.isEmpty(), "info answers");
        }
        finally {
            server.destroy();
        }
    }

    // A wifi that drops broadcasts: the addresses next to ours are asked one by one.
    private static void test_scan_finds_the_server() throws Exception
    {
        int port = free_port_find();
        Process server = server_start(port, new File(work, "data-7"));
        try {
            // The screen shows the scan as it goes.
            List<String> seen = new ArrayList<>();
            Thread watcher = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    String progress = Scan.progress;
                    if ((progress != null) && !seen.contains(progress)) {
                        seen.add(progress);
                    }
                    try {
                        Thread.sleep(5);
                    }
                    catch (InterruptedException error) {
                        return;
                    }
                }
            });
            watcher.start();
            long time0 = System.currentTimeMillis();
            List<Server> loopback = Scan.run(port, 700, Arrays.asList("127.0.0."));
            long elapsed = System.currentTimeMillis() - time0;
            watcher.interrupt();
            watcher.join();
            check(seen.size() > 1, "the scan reported its progress: " + seen.size() + " states");
            check(seen.get(0).startsWith("scanning 127.0.0.x · "), "progress reads: " + seen.get(0));
            check(Scan.progress == null, "no progress once the scan is over");
            // On linux every 127.0.0.x is this machine, so all of them may answer.
            check(!loopback.isEmpty() && loopback.get(0).url.equals("http://127.0.0.1:" + port), "scan of 127.0.0.x: " + loopback.size());
            check(elapsed < 10000, "254 addresses in " + elapsed + " ms");
            List<String> prefixes = Scan.local_prefixes();
            check(!prefixes.isEmpty(), "this machine has a private network to scan");
            List<Server> lan = Scan.run(port, 700, prefixes);
            String own = Scan.local_addresses().get(0).getHostAddress();
            boolean found_own = false;
            for (Server lan_server : lan) {
                found_own = found_own || lan_server.url.equals("http://" + own + ":" + port);
            }
            check(found_own, "scan of " + prefixes + " finds " + own + ": " + lan.size() + " found");
        }
        finally {
            server.destroy();
        }
    }

    // Every connection is cut after 700 kB; the upload still arrives whole.
    private static void test_cut_connections_resume() throws Exception
    {
        int port = free_port_find();
        File data = new File(work, "data-3");
        Process server = server_start(port, data);
        CuttingProxy proxy = new CuttingProxy(port, 700_000, 100);
        try {
            Outbox outbox = new Outbox(new File(work, "outbox-3"));
            byte[] video = random_bytes_create(5_000_000);
            outbox.add(new ByteArrayInputStream(video), "video.mp4", 1_700_000_000_000L, "2026-10-05");
            Sender.Result result = Sender.run(outbox, "http://127.0.0.1:" + proxy.port, new Recorder(), new AtomicBoolean());
            check(result == Sender.Result.sent_all, "cut connections: " + result);
            check(proxy.cuts.get() >= 5, "the proxy did cut: " + proxy.cuts.get());
            check(Arrays.equals(Files.readAllBytes(new File(data, "video.mp4").toPath()), video), "the video arrives whole");
        }
        finally {
            proxy.close();
            server.destroy();
        }
    }

    // The laptop vanishes mid-file: the item stays, and later goes from where it stopped.
    private static void test_lost_server_keeps_the_item() throws Exception
    {
        int port = free_port_find();
        File data = new File(work, "data-4");
        Process server = server_start(port, data);
        CuttingProxy proxy = new CuttingProxy(port, 1_000_000, 1);
        proxy.dead_after_cuts = true;
        Outbox outbox = new Outbox(new File(work, "outbox-4"));
        byte[] video = random_bytes_create(3_000_000);
        outbox.add(new ByteArrayInputStream(video), "lost.mp4", 1_700_000_000_000L, "2026-10-05");
        try {
            Recorder recorder = new Recorder();
            Sender.Result lost = Sender.run(outbox, "http://127.0.0.1:" + proxy.port, recorder, new AtomicBoolean());
            check(lost == Sender.Result.connection_lost, "lost server: " + lost);
            check(outbox.list().size() == 1, "the item stays on the phone");
            check(recorder.failed.size() == 1, "the failure is reported");
            check(new File(data.getPath() + "-uploads", outbox.list().get(0).id + ".part").length() > 0, "the server kept the first part");
            Sender.Result back = Sender.run(outbox, "http://127.0.0.1:" + port, recorder, new AtomicBoolean());
            check(back == Sender.Result.sent_all, "server back: " + back);
            check(Arrays.equals(Files.readAllBytes(new File(data, "lost.mp4").toPath()), video), "the file arrives whole");
            check(recorder.progress_first_after_resume >= 1_000_000, "the second run resumed at " + recorder.progress_first_after_resume);
        }
        finally {
            proxy.close();
            server.destroy();
        }
    }

    // Android stops the job: the upload ends mid-file and the next run goes on.
    private static void test_stop_ends_the_upload_mid_file() throws Exception
    {
        int port = free_port_find();
        File data = new File(work, "data-6");
        Process server = server_start(port, data);
        try {
            Outbox outbox = new Outbox(new File(work, "outbox-6"));
            byte[] video = random_bytes_create(8_000_000);
            outbox.add(new ByteArrayInputStream(video), "stopped.mp4", 1_700_000_000_000L, "2026-10-05");
            AtomicBoolean stop_requested = new AtomicBoolean();
            Recorder stopper = new Recorder() {
                @Override
                public void on_progress(OutboxItem item, long sent_bytes, long size)
                {
                    if (sent_bytes > 2_000_000) {
                        stop_requested.set(true);
                    }
                }
            };
            Sender.Result stopped = Sender.run(outbox, "http://127.0.0.1:" + port, stopper, stop_requested);
            check(stopped == Sender.Result.stopped, "stopped: " + stopped);
            check(outbox.list().size() == 1, "a stopped item stays");
            Sender.Result next = Sender.run(outbox, "http://127.0.0.1:" + port, new Recorder(), new AtomicBoolean());
            check(next == Sender.Result.sent_all, "next run: " + next);
            check(Arrays.equals(Files.readAllBytes(new File(data, "stopped.mp4").toPath()), video), "the stopped file arrives whole");
        }
        finally {
            server.destroy();
        }
    }

    private static void test_outbox_add_failure_leaves_nothing() throws Exception
    {
        Outbox outbox = new Outbox(new File(work, "outbox-5"));
        InputStream broken = new InputStream() {
            int left = 100_000;

            @Override
            public int read() throws java.io.IOException
            {
                if (left-- <= 0) {
                    throw new java.io.IOException("the picker's file went away");
                }
                return 7;
            }
        };
        try {
            outbox.add(broken, "half.bin", 0, "2026-10-05");
            check(false, "a broken copy must throw");
        }
        catch (java.io.IOException error) {
            check(outbox.list().isEmpty(), "a broken copy leaves no item");
            check(new File(work, "outbox-5").list().length == 0, "and no tmp folder");
        }
    }

    private static class Recorder implements Sender.Listener
    {
        final List<String> sent = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        long progress_first_after_resume = -1;
        boolean began = false;

        public void on_server(Server server)
        {
        }

        public void on_item_begin(OutboxItem item, int left)
        {
            began = true;
        }

        public void on_progress(OutboxItem item, long sent_bytes, long size)
        {
            if (began && !failed.isEmpty() && (progress_first_after_resume < 0)) {
                progress_first_after_resume = sent_bytes;
            }
        }

        public void on_item_sent(OutboxItem item, String saved)
        {
            sent.add(saved);
        }

        public void on_item_failed(OutboxItem item, Exception error)
        {
            failed.add(item.path + ": " + error);
        }
    }

    // Forwards to the server and closes a connection once it carried
    // cut_after bytes upstream, cuts_max times; after that it forwards
    // freely, or, with dead_after_cuts, refuses every connection.
    private static class CuttingProxy
    {
        final int port;
        final AtomicInteger cuts = new AtomicInteger();
        volatile boolean dead_after_cuts = false;
        private final ServerSocket listener;

        CuttingProxy(int target_port, long cut_after, int cuts_max) throws Exception
        {
            listener = new ServerSocket(0);
            port = listener.getLocalPort();
            Thread accept_thread = new Thread(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket client = listener.accept();
                        if (dead_after_cuts && (cuts.get() >= cuts_max)) {
                            client.close();
                            continue;
                        }
                        Socket upstream = new Socket("127.0.0.1", target_port);
                        boolean cutting = cuts.get() < cuts_max;
                        pump_start(client, upstream, cutting ? cut_after : Long.MAX_VALUE);
                        pump_start(upstream, client, Long.MAX_VALUE);
                    }
                    catch (Exception error) {
                        // closed
                    }
                }
            });
            accept_thread.setDaemon(true);
            accept_thread.start();
        }

        private void pump_start(Socket from, Socket to, long limit)
        {
            Thread thread = new Thread(() -> {
                long carried = 0;
                try {
                    InputStream in = from.getInputStream();
                    OutputStream to_stream = to.getOutputStream();
                    byte[] chunk = new byte[16384];
                    int read;
                    while ((read = in.read(chunk)) > 0) {
                        to_stream.write(chunk, 0, read);
                        carried += read;
                        if (carried > limit) {
                            cuts.incrementAndGet();
                            break;
                        }
                    }
                }
                catch (Exception error) {
                    // either side closed
                }
                socket_close_quietly(from);
                socket_close_quietly(to);
            });
            thread.setDaemon(true);
            thread.start();
        }

        void close() throws Exception
        {
            listener.close();
        }
    }

    private static void socket_close_quietly(Socket socket)
    {
        try {
            socket.close();
        }
        catch (Exception error) {
            // already closed
        }
    }

    private static Process server_start(int port, File data) throws Exception
    {
        // Files land in data, the server's own state goes beside it.
        ProcessBuilder builder = new ProcessBuilder("node", new File(repo, "src/http/index.js").getPath(), data.getPath(), data.getPath() + "-uploads");
        builder.environment().put("PORT", String.valueOf(port));
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(work, "server-" + port + ".log"));
        Process server = builder.start();
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (Server.info_get("http://127.0.0.1:" + port) != null) {
                return server;
            }
            Thread.sleep(100);
        }
        server.destroy();
        throw new AssertionError("server did not start on " + port);
    }

    private static int free_port_find() throws Exception
    {
        ServerSocket socket = new ServerSocket(0);
        int port = socket.getLocalPort();
        socket.close();
        return port;
    }

    private static byte[] random_bytes_create(int size)
    {
        byte[] out = new byte[size];
        new Random(size).nextBytes(out);
        return out;
    }
}
