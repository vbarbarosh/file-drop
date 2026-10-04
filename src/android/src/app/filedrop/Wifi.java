package app.filedrop;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

// The laptop is only ever on the wifi. A wifi with no internet (a travel
// router, the laptop's own hotspot) is not Android's default network, and
// mobile data would carry the app's traffic instead: the whole process is
// bound to the wifi, the one place the laptop can be.
public class Wifi
{
    public static void bind(Context context)
    {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if ((capabilities != null) && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                connectivity.bindProcessToNetwork(network);
                return;
            }
        }
        connectivity.bindProcessToNetwork(null);
    }

    // Any wifi, with or without internet.
    public static NetworkRequest request()
    {
        return new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
    }
}
