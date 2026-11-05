package com.calltally.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Picture messages (MMS) arrive as a WAP push. Storing one the way the standard messaging apps do means parsing the push
 * into content://mms, which isn't feasible safely in plain Java here. So Call Tally does not drop it silently: it keeps
 * the raw bytes in its own files and shows a line in Options and the month screen saying a picture message may be missing
 * from Messages, so the owner knows. It never reads or sends anything.
 */
public class MmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            byte[] pdu = intent.getByteArrayExtra("data");
            File folder = new File(context.getFilesDir(), "catchup-mms");
            if (folder.exists() || folder.mkdirs()) {
                File kept = new File(folder, System.currentTimeMillis() + ".pdu");
                try (FileOutputStream out = new FileOutputStream(kept)) {
                    if (pdu != null) out.write(pdu);
                }
            }
            // The month screen and Options read this to show Passthrough.PICTURE_MISSING.
            Sync.preferences(context).edit().putBoolean("mms_pending", true).commit();
        } catch (Exception error) {
            // Even failing to keep it, the flag below would already warn; never crash the receiver.
            Sync.preferences(context).edit().putBoolean("mms_pending", true).commit();
        }
    }
}
