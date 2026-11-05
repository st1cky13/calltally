package com.calltally.app;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.util.Map;

/**
 * While Call Tally briefly holds the messaging role for Catch up encrypted, a text that arrives is delivered only to the
 * default app and is not stored by Android. So Call Tally writes it straight into the phone's inbox, unread, so Google
 * Messages shows it once the role goes back. This is the ONE place the app touches a text's words, and only to pass them
 * through: they are never read to count and never sent anywhere. The app never sends a message.
 */
public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;
        String address = null;
        long dateSent = 0;
        StringBuilder words = new StringBuilder();
        for (SmsMessage part : parts) {
            if (part == null) continue;
            if (address == null) address = part.getDisplayOriginatingAddress();
            String body = part.getDisplayMessageBody();
            if (body != null) words.append(body);
            dateSent = part.getTimestampMillis();
        }
        if (address == null) return;
        try {
            long thread = Telephony.Threads.getOrCreateThreadId(context, address);
            // Build the row away from Android, so the one place words are handled is tested: pass through, unread.
            Map<String, Object> fields = Passthrough.inboxRow(address, words.toString(), System.currentTimeMillis(),
                dateSent, thread, intent.getIntExtra("subscription", -1));
            ContentValues values = new ContentValues();
            for (Map.Entry<String, Object> field : fields.entrySet()) {
                Object value = field.getValue();
                if (value instanceof Integer) values.put(field.getKey(), (Integer) value);
                else if (value instanceof Long) values.put(field.getKey(), (Long) value);
                else values.put(field.getKey(), String.valueOf(value));
            }
            context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, values);
        } catch (RuntimeException error) {
            // A text that can't be written is left for Google Messages to handle after the switch back, never a crash.
        }
    }
}
