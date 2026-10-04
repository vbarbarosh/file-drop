package app.filedrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

// Sends the outbox in the background. Android runs it when the phone is on
// wifi, also after a reboot; with no laptop there it plans the next try,
// sooner at first, then every five minutes, until the outbox is empty.
public class SendJob extends JobService
{
    private static final int job_id = 1;
    private static final long[] retry_delays_ms = {15000, 30000, 60000, 120000, 300000};
    private static final String channel_id = "sending";
    private static final int notice_sending = 1;
    private static final int notice_update = 2;

    // What the screen shows; written by the job's thread.
    static volatile boolean running = false;
    static volatile String status = "";
    static volatile String server_host = null;

    private final AtomicBoolean stop = new AtomicBoolean();

    // A file was added, or the screen opened: try now, the waits start over.
    public static void kick(Context context)
    {
        Settings.prefs(context).edit().putInt("retry_step", 0).apply();
        if (!running) {
            schedule(context, 0);
        }
    }

    static void schedule(Context context, long delay_ms)
    {
        JobInfo job = new JobInfo.Builder(job_id, new ComponentName(context, SendJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setMinimumLatency(delay_ms)
            .setPersisted(true)
            .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    static void channel_create(Context context)
    {
        NotificationChannel channel = new NotificationChannel(channel_id, "Sending", NotificationManager.IMPORTANCE_LOW);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public boolean onStartJob(JobParameters params)
    {
        running = true;
        stop.set(false);
        new Thread(() -> run(params)).start();
        return true;
    }

    // Android takes the time back (ten minutes at most): the upload resumes next run.
    @Override
    public boolean onStopJob(JobParameters params)
    {
        stop.set(true);
        return true;
    }

    private void run(JobParameters params)
    {
        channel_create(this);
        Outbox outbox = Settings.outbox(this);
        Listener listener = new Listener();
        Sender.Result result = Sender.run(outbox, Settings.server(this), listener, stop);
        running = false;

        int left = outbox.count();
        if (result == Sender.Result.stopped) {
            status = "";
            getSystemService(NotificationManager.class).cancel(notice_sending);
            return;
        }
        if (left == 0) {
            status = "";
            if (listener.sent > 0) {
                notice(notice_sending, "Sent " + plural(listener.sent, "file") + " to " + server_host, false, -1);
            }
            Settings.prefs(this).edit().putInt("retry_step", 0).apply();
            jobFinished(params, false);
            return;
        }
        // Added while the last item went, or the laptop is gone: try again.
        int step = Settings.prefs(this).getInt("retry_step", 0);
        long delay_ms = (result == Sender.Result.sent_all) ? 0 : retry_delays_ms[Math.min(step, retry_delays_ms.length - 1)];
        Settings.prefs(this).edit().putInt("retry_step", step + 1).apply();
        status = (result == Sender.Result.no_server) ? "no laptop on this wifi" : "connection lost";
        notice(notice_sending, plural(left, "file") + " waiting for the laptop", false, -1);
        schedule(this, delay_ms);
        jobFinished(params, false);
    }

    private class Listener implements Sender.Listener
    {
        int sent = 0;
        String server_url = null;
        long last_notice_ms = 0;
        int left = 0;

        public void on_server(Server server)
        {
            server_url = server.url;
            server_host = server.host;
            Settings.server_save(SendJob.this, server.url);
            update_notice(server);
        }

        public void on_item_begin(OutboxItem item, int left)
        {
            this.left = left;
            status = "sending " + item.name();
        }

        public void on_progress(OutboxItem item, long sent_bytes, long size)
        {
            int percent = (size == 0) ? 100 : (int) (sent_bytes*100/size);
            status = "sending " + item.name() + " · " + percent + "%";
            long now = SystemClock.elapsedRealtime();
            if ((now - last_notice_ms) < 1000) {
                return;
            }
            last_notice_ms = now;
            notice(notice_sending, "Sending " + item.name() + " · " + plural(left, "file") + " left", true, percent);
        }

        public void on_item_sent(OutboxItem item, String saved)
        {
            sent += 1;
        }

        public void on_item_failed(OutboxItem item, Exception error)
        {
            if (server_url != null) {
                Server.report(server_url, Settings.version(SendJob.this), "send-failed", item.path + ": " + error);
            }
        }
    }

    // A newer apk on the laptop is offered once per version; the tap opens
    // the screen, which offers the download.
    private void update_notice(Server server)
    {
        int notified = Settings.prefs(this).getInt("update_notified", 0);
        if ((server.apk_version <= Settings.version_code(this)) || (server.apk_version <= notified)) {
            return;
        }
        Settings.prefs(this).edit().putInt("update_notified", server.apk_version).apply();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, channel_id)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("File Drop update")
            .setContentText("Version " + server.apk_version + " is on the laptop. Tap to download.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build();
        getSystemService(NotificationManager.class).notify(notice_update, notification);
    }

    private void notice(int id, String text, boolean ongoing, int percent)
    {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, channel_id)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("File Drop")
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing);
        if (percent >= 0) {
            builder.setProgress(100, percent, false);
        }
        try {
            getSystemService(NotificationManager.class).notify(id, builder.build());
        }
        catch (SecurityException error) {
            // notifications not allowed; the screen still shows the state
        }
    }

    static String plural(int count, String noun)
    {
        return count + " " + noun + ((count == 1) ? "" : "s");
    }
}
