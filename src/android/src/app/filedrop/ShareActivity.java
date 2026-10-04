package app.filedrop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

// Gallery → Share → File Drop. The shared files are readable only while
// this window is open, so it stays until they are copied into the outbox,
// then closes; the sending goes on in the background.
public class ShareActivity extends Activity
{
    private TextView status;
    private Handler ui_handler;

    @Override
    protected void onCreate(Bundle saved_state)
    {
        super.onCreate(saved_state);
        setFinishOnTouchOutside(false);
        boolean is_dark = "dark".equals(Settings.theme(this));
        status = new TextView(this);
        status.setTextSize(16f);
        status.setPadding(dp(24), dp(20), dp(24), dp(20));
        status.setTextColor(is_dark ? 0xFFE6EBE8 : 0xFF141816);
        getWindow().setBackgroundDrawable(new ColorDrawable(is_dark ? 0xFF1B211E : 0xFFFFFFFF));
        status.setText("adding…");
        setContentView(status);
        ui_handler = new Handler(getMainLooper());

        Intent intent = getIntent();
        List<Uri> uris = shared_uris(intent);
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (!uris.isEmpty()) {
            Importer.add_documents(this, uris, this::import_done);
            ui_handler.post(ticker);
            return;
        }
        if ((text != null) && !text.trim().isEmpty()) {
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            String note = ((subject == null) || subject.isEmpty() || text.contains(subject)) ? text : (subject + "\n\n" + text);
            Importer.add_text(this, note, this::import_done);
            return;
        }
        toast("nothing to send");
        finish();
    }

    @Override
    protected void onDestroy()
    {
        ui_handler.removeCallbacks(ticker);
        super.onDestroy();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run()
        {
            String importing = Importer.status;
            if (importing != null) {
                status.setText(importing);
            }
            ui_handler.postDelayed(this, 300);
        }
    };

    private void import_done(int added, String error)
    {
        if (error != null) {
            toast(error);
        }
        else {
            toast(SendJob.plural(added, "file") + " will go to the laptop");
        }
        finish();
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> shared_uris(Intent intent)
    {
        List<Uri> out = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                out.addAll(streams);
            }
            return out;
        }
        Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream != null) {
            out.add(stream);
        }
        return out;
    }

    private void toast(String message)
    {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value)
    {
        return Math.round(value*getResources().getDisplayMetrics().density);
    }
}
