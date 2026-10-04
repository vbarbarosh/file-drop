package app.filedrop;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.io.File;

// What the app remembers between runs. All of it is set inside the app.
public class Settings
{
    public static SharedPreferences prefs(Context context)
    {
        return context.getSharedPreferences("filedrop", Context.MODE_PRIVATE);
    }

    public static Outbox outbox(Context context)
    {
        return new Outbox(new File(context.getFilesDir(), "outbox"));
    }

    // The last address that answered; null before the first one did.
    public static String server(Context context)
    {
        return prefs(context).getString("server", null);
    }

    public static void server_save(Context context, String url)
    {
        prefs(context).edit().putString("server", url).apply();
    }

    // "light" or "dark": the stored choice, else the phone's, read once.
    public static String theme(Context context)
    {
        String stored = prefs(context).getString("theme", null);
        if ("light".equals(stored) || "dark".equals(stored)) {
            return stored;
        }
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return (night == Configuration.UI_MODE_NIGHT_YES) ? "dark" : "light";
    }

    public static void theme_save(Context context, String theme)
    {
        prefs(context).edit().putString("theme", theme).apply();
    }

    @SuppressWarnings("deprecation")
    public static int version_code(Context context)
    {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        }
        catch (Exception error) {
            return 0;
        }
    }

    public static String version(Context context)
    {
        return "apk-" + version_code(context);
    }
}
