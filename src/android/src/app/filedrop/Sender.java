package app.filedrop;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

// Empties the outbox into the laptop: finds it, sends the items in order,
// and deletes each one only after the server has confirmed it. Files added
// while it runs are sent in the same run.
public class Sender
{
    public enum Result { sent_all, no_server, connection_lost, stopped }

    public interface Listener extends Uploader.Progress
    {
        void on_server(Server server);

        void on_item_begin(OutboxItem item, int left);

        void on_item_sent(OutboxItem item, String saved);

        void on_item_failed(OutboxItem item, Exception error);
    }

    public static Result run(Outbox outbox, String saved_url, Listener listener, AtomicBoolean stop, String... extra_targets)
    {
        Server server = Server.find(saved_url, extra_targets);
        if (server == null) {
            return Result.no_server;
        }
        listener.on_server(server);

        // Android takes the time back mid-file: the upload stops at once.
        Uploader.Progress progress = (item, sent, size) -> {
            if (stop.get()) {
                throw new Uploader.Stopped();
            }
            listener.on_progress(item, sent, size);
        };

        while (true) {
            List<OutboxItem> items = outbox.list();
            if (items.isEmpty()) {
                return Result.sent_all;
            }
            for (int i = 0; i < items.size(); ++i) {
                OutboxItem item = items.get(i);
                if (stop.get()) {
                    return Result.stopped;
                }
                listener.on_item_begin(item, items.size() - i);
                try {
                    String saved = Uploader.send(server.url, item, progress);
                    outbox.remove(item);
                    listener.on_item_sent(item, saved);
                }
                catch (IllegalStateException error) {
                    // A damaged copy can never arrive whole; it would block the rest.
                    outbox.remove(item);
                    listener.on_item_failed(item, error);
                }
                catch (Uploader.Stopped | InterruptedException error) {
                    return Result.stopped;
                }
                catch (Exception error) {
                    listener.on_item_failed(item, error);
                    return Result.connection_lost;
                }
            }
        }
    }
}
