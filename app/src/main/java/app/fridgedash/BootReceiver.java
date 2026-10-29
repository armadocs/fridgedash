package app.fridgedash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** On the phone: start "Fridge Mic" again after a restart, if it was on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && Prefs.linkEnabled(c)) {
            try { MicLinkService.start(c); } catch (Exception ignored) {}
        }
    }
}
