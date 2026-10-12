package app.filedrop;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The app's one screen. It only sends: take photos, record voice, pick
// files or a folder, type a text. Everything goes to the outbox first; the
// screen shows the laptop, what waits and what is being sent.
public class MainActivity extends Activity
{
    private static final int REQUEST_PHOTO = 1;
    private static final int REQUEST_FILES = 2;
    private static final int REQUEST_FOLDER = 3;
    private static final int REQUEST_AUDIO_PERMISSION = 4;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 5;

    private static final int[] LIGHT = {0xFFF3F5F4, 0xFFFFFFFF, 0xFF141816, 0xFF5B6662, 0xFFD5DCD9, 0xFF1F8A70, 0xFFFFFFFF};
    private static final int[] DARK = {0xFF121614, 0xFF1B211E, 0xFFE6EBE8, 0xFF96A39D, 0xFF2C3531, 0xFF34B892, 0xFF0B1210};
    private static final int COLOR_BG = 0;
    private static final int COLOR_SURFACE = 1;
    private static final int COLOR_FG = 2;
    private static final int COLOR_MUTED = 3;
    private static final int COLOR_BORDER = 4;
    private static final int COLOR_ACCENT = 5;
    private static final int COLOR_ACCENT_FG = 6;

    private int[] palette = LIGHT;
    private Handler ui_handler;
    private Outbox outbox;

    private LinearLayout root;
    private TextView title;
    private TextView theme_button;
    private TextView server_button;
    private LinearLayout status_card;
    private TextView laptop_text;
    private TextView queue_text;
    private LinearLayout update_card;
    private TextView update_text;
    private Button update_button;
    private Button photo_button;
    private Button voice_button;
    private Button files_button;
    private Button folder_button;
    private EditText text_input;
    private Button text_button;

    private Server server = null;
    private String laptop_state = "looking for the laptop…";
    private int update_offered_version = 0;
    private MediaRecorder recorder = null;
    private File voice_file = null;
    private String voice_name = null;
    private long voice_time0 = 0;

    @Override
    protected void onCreate(Bundle saved_state)
    {
        super.onCreate(saved_state);
        ui_handler = new Handler(getMainLooper());
        outbox = Settings.outbox(this);
        SendJob.channel_create(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px_from_dp(16), px_from_dp(12), px_from_dp(16), px_from_dp(24));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(this);
        title.setText("File Drop");
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        theme_button = header_button_create("");
        header.addView(theme_button);
        server_button = header_button_create("laptop");
        header.addView(server_button);
        root.addView(header);

        status_card = card_create();
        laptop_text = new TextView(this);
        laptop_text.setTextSize(15f);
        queue_text = new TextView(this);
        queue_text.setTextSize(14f);
        queue_text.setPadding(0, px_from_dp(4), 0, 0);
        status_card.addView(laptop_text);
        status_card.addView(queue_text);
        root.addView(status_card, spaced_params_create(12));

        update_card = card_create();
        update_text = new TextView(this);
        update_button = new Button(this);
        update_button.setText("Download");
        update_card.addView(update_text);
        update_card.addView(update_button, spaced_params_create(8));
        update_card.setVisibility(View.GONE);
        root.addView(update_card, spaced_params_create(12));

        photo_button = new Button(this);
        photo_button.setText("Take photos");
        root.addView(photo_button, spaced_params_create(20));
        voice_button = new Button(this);
        voice_button.setText("Record voice");
        root.addView(voice_button, spaced_params_create(10));
        files_button = new Button(this);
        files_button.setText("Choose files");
        root.addView(files_button, spaced_params_create(10));
        folder_button = new Button(this);
        folder_button.setText("Choose folder");
        root.addView(folder_button, spaced_params_create(10));

        text_input = new EditText(this);
        text_input.setHint("Text to send");
        text_input.setMinLines(3);
        text_input.setGravity(Gravity.TOP | Gravity.START);
        text_input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text_input.setPadding(px_from_dp(12), px_from_dp(10), px_from_dp(12), px_from_dp(10));
        root.addView(text_input, spaced_params_create(20));
        text_button = new Button(this);
        text_button.setText("Send text");
        LinearLayout.LayoutParams text_button_params = spaced_params_create(8);
        text_button_params.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        text_button_params.gravity = Gravity.END;
        root.addView(text_button, text_button_params);

        setContentView(scroll);

        theme_button.setOnClickListener(v -> click_theme());
        server_button.setOnClickListener(v -> click_server());
        update_button.setOnClickListener(v -> click_update());
        photo_button.setOnClickListener(v -> photo_take());
        voice_button.setOnClickListener(v -> click_voice());
        files_button.setOnClickListener(v -> click_files());
        folder_button.setOnClickListener(v -> click_folder());
        text_button.setOnClickListener(v -> click_text());

        theme_apply();
        staging_rescue(saved_state != null);
        outbox.remove_stale_tmp();
        notification_permission_ask();
        client_log_post("app-started", Build.MODEL + " android " + Build.VERSION.RELEASE);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        ui_handler.post(ticker);
        refresh_server();
        if (outbox.count() > 0) {
            SendJob.kick(this);
        }
    }

    @Override
    protected void onPause()
    {
        ui_handler.removeCallbacks(ticker);
        super.onPause();
    }

    // Android hands an app in the background silence from the microphone:
    // leaving the screen ends the recording and keeps what was said.
    @Override
    protected void onStop()
    {
        if (recorder != null) {
            voice_stop(true);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy()
    {
        if (recorder != null) {
            voice_stop(true);
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results)
    {
        boolean granted = (results.length > 0) && (results[0] == PackageManager.PERMISSION_GRANTED);
        if ((code == REQUEST_AUDIO_PERMISSION) && granted) {
            voice_start();
        }
        else if (code == REQUEST_AUDIO_PERMISSION) {
            toast_show("recording needs the microphone permission");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data)
    {
        if (code == REQUEST_PHOTO) {
            photo_receive(result == RESULT_OK);
            return;
        }
        if ((result != RESULT_OK) || (data == null)) {
            return;
        }
        if (code == REQUEST_FILES) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); ++i) {
                    uris.add(clip.getItemAt(i).getUri());
                }
            }
            else if (data.getData() != null) {
                uris.add(data.getData());
            }
            Importer.add_documents(this, uris, this::import_notify);
            return;
        }
        if ((code == REQUEST_FOLDER) && (data.getData() != null)) {
            Importer.add_tree(this, data.getData(), this::import_notify);
        }
    }

    // the screen refreshes twice a second from the job's and the importer's state

    private final Runnable ticker = new Runnable() {
        @Override
        public void run()
        {
            refresh();
            ui_handler.postDelayed(this, 500);
        }
    };

    private void refresh()
    {
        int waiting = outbox.count();
        String importing = Importer.status;
        String sending = SendJob.status;
        // The job tried after this screen asked: its answer is the newer one.
        boolean job_lost = sending.equals(SendJob.NO_LAPTOP);
        String searching = Scan.progress;
        if (searching != null) {
            laptop_text.setText("Looking for the laptop: " + searching);
        }
        else if ((server != null) && !job_lost) {
            laptop_text.setText("Laptop: " + server.host);
        }
        else {
            laptop_text.setText(job_lost ? (SendJob.NO_LAPTOP + " — files wait on the phone") : laptop_state);
        }
        if (job_lost) {
            sending = "";
        }
        String queue;
        if (importing != null) {
            queue = importing;
        }
        else if (SendJob.running && !sending.isEmpty()) {
            queue = sending + " · " + SendJob.format_count(waiting, "file") + " left";
        }
        else if (waiting > 0) {
            queue = SendJob.format_count(waiting, "file") + " waiting" + (sending.isEmpty() ? "" : " · " + sending);
        }
        else {
            queue = "everything is sent";
        }
        queue_text.setText(queue);
        if (recorder != null) {
            voice_button.setText("Stop recording · " + format_duration(SystemClock.elapsedRealtime() - voice_time0));
        }
    }

    // the laptop: the saved address, else whoever answers on the wifi

    private void refresh_server()
    {
        String saved = Settings.server(this);
        new Thread(() -> {
            Wifi.bind(this);
            Server found = Server.find(saved);
            runOnUiThread(() -> server_apply(found));
        }).start();
    }

    private void server_apply(Server found)
    {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        server = found;
        if (found == null) {
            laptop_state = "no laptop on this wifi — files wait on the phone";
            refresh();
            return;
        }
        Settings.server_save(this, found.url);
        refresh();
        if (found.apk_version > Settings.version_code(this)) {
            update_text.setText("Version " + found.apk_version + " is on the laptop (installed: " + Settings.version_code(this) + ").");
            update_card.setVisibility(View.VISIBLE);
            if (update_offered_version < found.apk_version) {
                update_offered_version = found.apk_version;
                new AlertDialog.Builder(this, dialog_theme_pick())
                    .setTitle("Update available")
                    .setMessage("Version " + found.apk_version + " is on the laptop. Download it now?")
                    .setPositiveButton("Download", (dialog, which) -> click_update())
                    .setNegativeButton("Later", null)
                    .show();
            }
        }
        else {
            update_card.setVisibility(View.GONE);
        }
    }

    private void click_server()
    {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("192.168.1.23:8080");
        input.setText(Settings.server(this));
        List<String> phone_addresses = new ArrayList<>();
        for (InetAddress address : Scan.local_addresses()) {
            phone_addresses.add(address.getHostAddress());
        }
        String phone_line = phone_addresses.isEmpty() ? "" : ("\n\nThis phone: " + String.join(", ", phone_addresses));
        new AlertDialog.Builder(this, dialog_theme_pick())
            .setTitle("Laptop address")
            .setMessage("Find looks for File Drop on this wifi. Or type the address bin/run prints." + phone_line)
            .setView(input)
            .setPositiveButton("Save", (dialog, which) -> server_address_save(input.getText().toString()))
            .setNeutralButton("Find", (dialog, which) -> click_find())
            .setNegativeButton("Cancel", null)
            .show();
    }

    // Every laptop on the wifi: one is taken, several are offered.
    private void click_find()
    {
        int port = Server.port_of(Settings.server(this));
        server = null;
        laptop_state = "looking for the laptop on this wifi…";
        refresh();
        new Thread(() -> {
            Wifi.bind(this);
            List<Server> found = Server.find_all(port);
            runOnUiThread(() -> servers_offer(found));
        }).start();
    }

    private void servers_offer(List<Server> found)
    {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (found.isEmpty()) {
            laptop_state = "no laptop found — is bin/run running, on this wifi?";
            refresh();
            return;
        }
        if (found.size() == 1) {
            server_address_save(found.get(0).url);
            return;
        }
        String[] labels = new String[found.size()];
        for (int i = 0; i < labels.length; ++i) {
            labels[i] = found.get(i).host + " · " + found.get(i).url.replace("http://", "");
        }
        new AlertDialog.Builder(this, dialog_theme_pick())
            .setTitle("Which laptop?")
            .setItems(labels, (dialog, which) -> server_address_save(found.get(which).url))
            .setNegativeButton("Cancel", null)
            .show();
    }

    // "192.168.1.23" is taken as "http://192.168.1.23:8080".
    private void server_address_save(String typed)
    {
        String url = typed.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.isEmpty() && !url.startsWith("http")) {
            url = "http://" + url;
        }
        if (!url.isEmpty() && !url.substring(url.indexOf("//") + 2).contains(":")) {
            url = url + ":" + Server.DEFAULT_PORT;
        }
        Settings.server_save(this, url.isEmpty() ? null : url);
        server = null;
        laptop_state = "looking for the laptop…";
        refresh();
        refresh_server();
        SendJob.kick(this);
    }

    // update: download the apk the laptop serves and hand it to the system installer

    private void click_update()
    {
        if (server == null) {
            return;
        }
        String url = server.url;
        update_button.setEnabled(false);
        update_text.setText("Downloading…");
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url + "/file-drop.apk").openConnection();
                long written = 0;
                long expected;
                try {
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(30000);
                    if (connection.getResponseCode() != 200) {
                        throw new Exception("http " + connection.getResponseCode());
                    }
                    expected = connection.getContentLengthLong();
                    InputStream in = connection.getInputStream();
                    FileOutputStream apk_stream = new FileOutputStream(new File(getCacheDir(), "update.apk"));
                    try {
                        byte[] chunk = new byte[65536];
                        int read;
                        while ((read = in.read(chunk)) > 0) {
                            apk_stream.write(chunk, 0, read);
                            written += read;
                        }
                    }
                    finally {
                        apk_stream.close();
                        in.close();
                    }
                }
                finally {
                    connection.disconnect();
                }
                // A cut download would reach the installer as "problem parsing the package".
                if ((expected >= 0) && (written != expected)) {
                    throw new Exception("download cut at " + written + " of " + expected + " bytes");
                }
                runOnUiThread(this::update_install);
            }
            catch (Exception error) {
                client_log_post("update-failed", error.toString());
                runOnUiThread(() -> {
                    update_button.setEnabled(true);
                    update_text.setText("Download failed: " + error.getMessage());
                });
            }
        }).start();
    }

    private void update_install()
    {
        update_button.setEnabled(true);
        update_text.setText("Android asks to confirm the install.");
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(FilesProvider.update_uri(), "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(install);
    }

    // photos: the phone's camera opens again after each shot; Back ends the series

    private void photo_take()
    {
        String name = "photo_" + Outbox.time_stamp() + ".jpg";
        Settings.prefs(this).edit().putString("camera_pending", name).apply();
        Uri uri = FilesProvider.staging_uri(name);
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        intent.setClipData(ClipData.newRawUri(name, uri));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PHOTO);
        }
        catch (ActivityNotFoundException error) {
            Settings.prefs(this).edit().remove("camera_pending").apply();
            toast_show("no camera app on this phone");
        }
    }

    private void photo_receive(boolean ok)
    {
        String name = Settings.prefs(this).getString("camera_pending", null);
        Settings.prefs(this).edit().remove("camera_pending").apply();
        if (name == null) {
            return;
        }
        File file = new File(outbox.staging_dir(), name);
        if (!ok || (file.length() == 0)) {
            file.delete();
            return;
        }
        Importer.add_staged(this, file, name, this::import_notify);
        photo_take();
    }

    // voice: tap to start, tap to stop; the recording goes as an .m4a file

    private void click_voice()
    {
        if (recorder != null) {
            voice_stop(true);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }
        voice_start();
    }

    private void voice_start()
    {
        voice_name = "voice_" + Outbox.time_stamp() + ".m4a";
        voice_file = new File(outbox.staging_dir(), voice_name);
        recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(voice_file.getPath());
            recorder.prepare();
            recorder.start();
        }
        catch (Exception error) {
            recorder.release();
            recorder = null;
            voice_file.delete();
            client_log_post("voice-failed", error.toString());
            toast_show("cannot record: " + error.getMessage());
            return;
        }
        voice_time0 = SystemClock.elapsedRealtime();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        voice_button.setBackground(button_background_create(true));
        voice_button.setTextColor(palette[COLOR_ACCENT_FG]);
        refresh();
    }

    private void voice_stop(boolean keep)
    {
        boolean recorded = keep;
        try {
            recorder.stop();
        }
        catch (RuntimeException error) {
            // stopped before any sound was written: nothing to keep
            recorded = false;
        }
        recorder.release();
        recorder = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        voice_button.setText("Record voice");
        voice_button.setBackground(button_background_create(false));
        voice_button.setTextColor(palette[COLOR_FG]);
        if (recorded && (voice_file.length() > 0)) {
            Importer.add_staged(this, voice_file, voice_name, this::import_notify);
        }
        else {
            voice_file.delete();
        }
    }

    // files and folders: the system picker; the files are copied, never moved

    private void click_files()
    {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_FILES);
    }

    private void click_folder()
    {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER);
    }

    private void click_text()
    {
        String text = text_input.getText().toString();
        if (text.trim().isEmpty()) {
            return;
        }
        text_input.setText("");
        Importer.add_text(this, text, this::import_notify);
    }

    private void import_notify(int added, String failure)
    {
        if (failure != null) {
            client_log_post("import-failed", failure);
            toast_show(failure);
        }
        refresh();
    }

    // A photo left in staging by a process that died is still a photo the
    // user took: it joins the outbox. The camera's result still comes to a
    // screen restored after the process died, so its photo waits for it.
    // A recording cut off by a dying process has no index and does not play.
    private void staging_rescue(boolean restored)
    {
        String pending = restored ? Settings.prefs(this).getString("camera_pending", null) : null;
        if (!restored) {
            Settings.prefs(this).edit().remove("camera_pending").apply();
        }
        File[] files = outbox.staging_dir().listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.getName().equals(pending)) {
                continue;
            }
            if ((file.length() > 0) && !file.getName().startsWith("voice_")) {
                Importer.add_staged(this, file, file.getName(), null);
            }
            else {
                file.delete();
            }
        }
    }

    private void notification_permission_ask()
    {
        if ((Build.VERSION.SDK_INT < 33) || Settings.prefs(this).getBoolean("notifications_asked", false)) {
            return;
        }
        Settings.prefs(this).edit().putBoolean("notifications_asked", true).apply();
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, REQUEST_NOTIFICATION_PERMISSION);
        }
    }

    // theme: two states, light and dark; the button names the one it switches to

    private void click_theme()
    {
        Settings.theme_save(this, "dark".equals(Settings.theme(this)) ? "light" : "dark");
        theme_apply();
    }

    private void theme_apply()
    {
        boolean is_dark = "dark".equals(Settings.theme(this));
        palette = is_dark ? DARK : LIGHT;
        theme_button.setText(is_dark ? "light" : "dark");
        getWindow().setStatusBarColor(palette[COLOR_BG]);
        getWindow().setNavigationBarColor(palette[COLOR_BG]);
        getWindow().getDecorView().setSystemUiVisibility(is_dark ? 0 : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        ((View) root.getParent()).setBackgroundColor(palette[COLOR_BG]);
        title.setTextColor(palette[COLOR_FG]);
        theme_button.setTextColor(palette[COLOR_MUTED]);
        server_button.setTextColor(palette[COLOR_MUTED]);
        status_card.setBackground(rounded_create(palette[COLOR_SURFACE], palette[COLOR_BORDER], 14));
        update_card.setBackground(rounded_create(palette[COLOR_SURFACE], palette[COLOR_ACCENT], 14));
        laptop_text.setTextColor(palette[COLOR_FG]);
        queue_text.setTextColor(palette[COLOR_MUTED]);
        update_text.setTextColor(palette[COLOR_FG]);
        button_style(update_button, true);
        button_style(photo_button, true);
        button_style(voice_button, recorder != null);
        button_style(files_button, false);
        button_style(folder_button, false);
        button_style(text_button, false);
        text_input.setTextColor(palette[COLOR_FG]);
        text_input.setHintTextColor(palette[COLOR_MUTED]);
        text_input.setBackground(rounded_create(palette[COLOR_SURFACE], palette[COLOR_BORDER], 12));
    }

    private int dialog_theme_pick()
    {
        return "dark".equals(Settings.theme(this))
            ? android.R.style.Theme_Material_Dialog_Alert
            : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    // views

    private TextView header_button_create(String text)
    {
        TextView out = new TextView(this);
        out.setText(text);
        out.setTextSize(14f);
        out.setPadding(px_from_dp(12), px_from_dp(12), px_from_dp(12), px_from_dp(12));
        return out;
    }

    private LinearLayout card_create()
    {
        LinearLayout out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        out.setPadding(px_from_dp(16), px_from_dp(12), px_from_dp(16), px_from_dp(12));
        return out;
    }

    private LinearLayout.LayoutParams spaced_params_create(int top_dp)
    {
        LinearLayout.LayoutParams out = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        out.topMargin = px_from_dp(top_dp);
        return out;
    }

    private void button_style(Button button, boolean primary)
    {
        button.setAllCaps(false);
        button.setTextSize(17f);
        button.setStateListAnimator(null);
        button.setMinHeight(px_from_dp(56));
        button.setBackground(button_background_create(primary));
        button.setTextColor(primary ? palette[COLOR_ACCENT_FG] : palette[COLOR_FG]);
    }

    private GradientDrawable button_background_create(boolean primary)
    {
        return primary
            ? rounded_create(palette[COLOR_ACCENT], palette[COLOR_ACCENT], 14)
            : rounded_create(palette[COLOR_SURFACE], palette[COLOR_BORDER], 14);
    }

    private GradientDrawable rounded_create(int fill, int stroke, int radius_dp)
    {
        GradientDrawable out = new GradientDrawable();
        out.setColor(fill);
        out.setStroke(px_from_dp(1), stroke);
        out.setCornerRadius(px_from_dp(radius_dp));
        return out;
    }

    // helpers

    private void client_log_post(String event_name, String detail)
    {
        String url = Settings.server(this);
        if (url == null) {
            return;
        }
        String version = Settings.version(this);
        new Thread(() -> Server.client_log_post(url, version, event_name, detail)).start();
    }

    private void toast_show(String message)
    {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // 75000 → "1:15"
    private static String format_duration(long ms)
    {
        long seconds = ms/1000;
        return String.format(Locale.US, "%d:%02d", seconds/60, seconds%60);
    }

    private int px_from_dp(int value)
    {
        return Math.round(value*getResources().getDisplayMetrics().density);
    }
}
