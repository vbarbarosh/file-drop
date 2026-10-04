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
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

// The app's one screen. It only sends: take photos, record voice, pick
// files or a folder, type a text. Everything goes to the outbox first; the
// screen shows the laptop, what waits and what is being sent.
public class MainActivity extends Activity
{
    private static final int request_photo = 1;
    private static final int request_files = 2;
    private static final int request_folder = 3;
    private static final int request_audio_permission = 4;
    private static final int request_notification_permission = 5;

    private static final int[] light = {0xFFF3F5F4, 0xFFFFFFFF, 0xFF141816, 0xFF5B6662, 0xFFD5DCD9, 0xFF1F8A70, 0xFFFFFFFF};
    private static final int[] dark = {0xFF121614, 0xFF1B211E, 0xFFE6EBE8, 0xFF96A39D, 0xFF2C3531, 0xFF34B892, 0xFF0B1210};
    private static final int color_bg = 0;
    private static final int color_surface = 1;
    private static final int color_fg = 2;
    private static final int color_muted = 3;
    private static final int color_border = 4;
    private static final int color_accent = 5;
    private static final int color_accent_fg = 6;

    private int[] palette = light;
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
    private int update_offered = 0;
    private MediaRecorder recorder = null;
    private File voice_file = null;
    private String voice_name = null;
    private long voice_started_ms = 0;

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
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(this);
        title.setText("File Drop");
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        theme_button = header_button("");
        header.addView(theme_button);
        server_button = header_button("laptop");
        header.addView(server_button);
        root.addView(header);

        status_card = card();
        laptop_text = new TextView(this);
        laptop_text.setTextSize(15f);
        queue_text = new TextView(this);
        queue_text.setTextSize(14f);
        queue_text.setPadding(0, dp(4), 0, 0);
        status_card.addView(laptop_text);
        status_card.addView(queue_text);
        root.addView(status_card, spaced(12));

        update_card = card();
        update_text = new TextView(this);
        update_button = new Button(this);
        update_button.setText("Download");
        update_card.addView(update_text);
        update_card.addView(update_button, spaced(8));
        update_card.setVisibility(View.GONE);
        root.addView(update_card, spaced(12));

        photo_button = new Button(this);
        photo_button.setText("Take photos");
        root.addView(photo_button, spaced(20));
        voice_button = new Button(this);
        voice_button.setText("Record voice");
        root.addView(voice_button, spaced(10));
        files_button = new Button(this);
        files_button.setText("Choose files");
        root.addView(files_button, spaced(10));
        folder_button = new Button(this);
        folder_button.setText("Choose folder");
        root.addView(folder_button, spaced(10));

        text_input = new EditText(this);
        text_input.setHint("Text to send");
        text_input.setMinLines(3);
        text_input.setGravity(Gravity.TOP | Gravity.START);
        text_input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text_input.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(text_input, spaced(20));
        text_button = new Button(this);
        text_button.setText("Send text");
        LinearLayout.LayoutParams text_button_params = spaced(8);
        text_button_params.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        text_button_params.gravity = Gravity.END;
        root.addView(text_button, text_button_params);

        setContentView(scroll);

        theme_button.setOnClickListener(view -> click_theme());
        server_button.setOnClickListener(view -> click_server());
        update_button.setOnClickListener(view -> click_update());
        photo_button.setOnClickListener(view -> photo_take());
        voice_button.setOnClickListener(view -> click_voice());
        files_button.setOnClickListener(view -> click_files());
        folder_button.setOnClickListener(view -> click_folder());
        text_button.setOnClickListener(view -> click_text());

        theme_apply();
        staging_rescue();
        outbox.remove_stale_tmp();
        notification_permission_ask();
        report("app-started", Build.MODEL + " android " + Build.VERSION.RELEASE);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        ui_handler.post(ticker);
        server_check();
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
        if ((code == request_audio_permission) && granted) {
            voice_start();
        }
        else if (code == request_audio_permission) {
            toast("recording needs the microphone permission");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data)
    {
        if (code == request_photo) {
            photo_taken(result == RESULT_OK);
            return;
        }
        if ((result != RESULT_OK) || (data == null)) {
            return;
        }
        if (code == request_files) {
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
            Importer.add_documents(this, uris, this::import_done);
            return;
        }
        if ((code == request_folder) && (data.getData() != null)) {
            Importer.add_tree(this, data.getData(), this::import_done);
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
        laptop_text.setText((server != null) ? ("Laptop: " + server.host) : laptop_state);
        int waiting = outbox.count();
        String importing = Importer.status;
        String sending = SendJob.status;
        String queue;
        if (importing != null) {
            queue = importing;
        }
        else if (SendJob.running && !sending.isEmpty()) {
            queue = sending + " · " + SendJob.plural(waiting, "file") + " left";
        }
        else if (waiting > 0) {
            queue = SendJob.plural(waiting, "file") + " waiting" + (sending.isEmpty() ? "" : " · " + sending);
        }
        else {
            queue = "everything is sent";
        }
        queue_text.setText(queue);
        if (recorder != null) {
            long seconds = (SystemClock.elapsedRealtime() - voice_started_ms)/1000;
            voice_button.setText(String.format("Stop recording · %d:%02d", seconds/60, seconds%60));
        }
    }

    // the laptop: the saved address, else whoever answers on the wifi

    private void server_check()
    {
        String saved = Settings.server(this);
        new Thread(() -> {
            Server found = Server.find(saved);
            runOnUiThread(() -> server_found(found));
        }).start();
    }

    private void server_found(Server found)
    {
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
            if (update_offered < found.apk_version) {
                update_offered = found.apk_version;
                new AlertDialog.Builder(this, dialog_theme())
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
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://192.168.1.23:8080");
        input.setText(Settings.server(this));
        new AlertDialog.Builder(this, dialog_theme())
            .setTitle("Laptop address")
            .setMessage("Found by itself on the same wifi. Type it only when it is not: the address bin/run prints.")
            .setView(input)
            .setPositiveButton("Save", (dialog, which) -> server_address_save(input.getText().toString()))
            .setNeutralButton("Find again", (dialog, which) -> server_address_save(""))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void server_address_save(String typed)
    {
        String url = typed.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.isEmpty() && !url.startsWith("http")) {
            url = "http://" + url;
        }
        Settings.server_save(this, url.isEmpty() ? null : url);
        server = null;
        laptop_state = "looking for the laptop…";
        refresh();
        server_check();
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
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(30000);
                InputStream in = connection.getInputStream();
                FileOutputStream out = new FileOutputStream(new File(getCacheDir(), "update.apk"));
                byte[] chunk = new byte[65536];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    out.write(chunk, 0, read);
                }
                out.close();
                in.close();
                connection.disconnect();
                runOnUiThread(this::update_install);
            }
            catch (Exception error) {
                report("update-failed", error.toString());
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
            startActivityForResult(intent, request_photo);
        }
        catch (ActivityNotFoundException error) {
            Settings.prefs(this).edit().remove("camera_pending").apply();
            toast("no camera app on this phone");
        }
    }

    private void photo_taken(boolean ok)
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
        Importer.add_staged(this, file, name, this::import_done);
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
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, request_audio_permission);
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
            report("voice-failed", error.toString());
            toast("cannot record: " + error.getMessage());
            return;
        }
        voice_started_ms = SystemClock.elapsedRealtime();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        voice_button.setBackground(button_background(true));
        voice_button.setTextColor(palette[color_accent_fg]);
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
        voice_button.setBackground(button_background(false));
        voice_button.setTextColor(palette[color_fg]);
        if (recorded && (voice_file.length() > 0)) {
            Importer.add_staged(this, voice_file, voice_name, this::import_done);
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
        startActivityForResult(intent, request_files);
    }

    private void click_folder()
    {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), request_folder);
    }

    private void click_text()
    {
        String text = text_input.getText().toString();
        if (text.trim().isEmpty()) {
            return;
        }
        text_input.setText("");
        Importer.add_text(this, text, this::import_done);
    }

    private void import_done(int added, String error)
    {
        if (error != null) {
            report("import-failed", error);
            toast(error);
        }
        refresh();
    }

    // A photo or a recording left in staging by a process that died is
    // still a file the user made: it joins the outbox.
    private void staging_rescue()
    {
        String pending = Settings.prefs(this).getString("camera_pending", null);
        File[] files = outbox.staging_dir().listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.getName().equals(pending)) {
                continue;
            }
            if (file.length() > 0) {
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
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, request_notification_permission);
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
        palette = is_dark ? dark : light;
        theme_button.setText(is_dark ? "light" : "dark");
        getWindow().setStatusBarColor(palette[color_bg]);
        getWindow().setNavigationBarColor(palette[color_bg]);
        getWindow().getDecorView().setSystemUiVisibility(is_dark ? 0 : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        ((View) root.getParent()).setBackgroundColor(palette[color_bg]);
        title.setTextColor(palette[color_fg]);
        theme_button.setTextColor(palette[color_muted]);
        server_button.setTextColor(palette[color_muted]);
        status_card.setBackground(rounded(palette[color_surface], palette[color_border], 14));
        update_card.setBackground(rounded(palette[color_surface], palette[color_accent], 14));
        laptop_text.setTextColor(palette[color_fg]);
        queue_text.setTextColor(palette[color_muted]);
        update_text.setTextColor(palette[color_fg]);
        button_style(update_button, true);
        button_style(photo_button, true);
        button_style(voice_button, recorder != null);
        button_style(files_button, false);
        button_style(folder_button, false);
        button_style(text_button, false);
        text_input.setTextColor(palette[color_fg]);
        text_input.setHintTextColor(palette[color_muted]);
        text_input.setBackground(rounded(palette[color_surface], palette[color_border], 12));
    }

    private int dialog_theme()
    {
        return "dark".equals(Settings.theme(this))
            ? android.R.style.Theme_Material_Dialog_Alert
            : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    // views

    private TextView header_button(String text)
    {
        TextView out = new TextView(this);
        out.setText(text);
        out.setTextSize(14f);
        out.setPadding(dp(12), dp(12), dp(12), dp(12));
        return out;
    }

    private LinearLayout card()
    {
        LinearLayout out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        out.setPadding(dp(16), dp(12), dp(16), dp(12));
        return out;
    }

    private LinearLayout.LayoutParams spaced(int top_dp)
    {
        LinearLayout.LayoutParams out = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        out.topMargin = dp(top_dp);
        return out;
    }

    private void button_style(Button button, boolean primary)
    {
        button.setAllCaps(false);
        button.setTextSize(17f);
        button.setStateListAnimator(null);
        button.setMinHeight(dp(56));
        button.setBackground(button_background(primary));
        button.setTextColor(primary ? palette[color_accent_fg] : palette[color_fg]);
    }

    private GradientDrawable button_background(boolean primary)
    {
        return primary
            ? rounded(palette[color_accent], palette[color_accent], 14)
            : rounded(palette[color_surface], palette[color_border], 14);
    }

    private GradientDrawable rounded(int fill, int stroke, int radius_dp)
    {
        GradientDrawable out = new GradientDrawable();
        out.setColor(fill);
        out.setStroke(dp(1), stroke);
        out.setCornerRadius(dp(radius_dp));
        return out;
    }

    // helpers

    private void report(String event, String detail)
    {
        String url = Settings.server(this);
        if (url == null) {
            return;
        }
        String version = Settings.version(this);
        new Thread(() -> Server.report(url, version, event, detail)).start();
    }

    private void toast(String message)
    {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value)
    {
        return Math.round(value*getResources().getDisplayMetrics().density);
    }
}
