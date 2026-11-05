package com.calltally.app;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;

/**
 * Asking to be, and to stop being, the phone's default messaging app, for Catch up encrypted. Android shows the owner a
 * dialog for every change; this only builds the intents and reads who the messaging app is now. Call Tally never sends a
 * message and holds the role only long enough to read, with the owner approving each step.
 */
final class Roles {
    static final String GOOGLE_MESSAGES = "com.google.android.apps.messaging";

    private Roles() {}

    /** The package of the phone's current messaging app, or null when it can't be read. */
    static String defaultMessaging(Context context) {
        try {
            return Telephony.Sms.getDefaultSmsPackage(context);
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** True while Call Tally is the phone's messaging app. */
    static boolean holdsSmsRole(Context context) {
        return context.getPackageName().equals(defaultMessaging(context));
    }

    /** The dialog that asks the owner to make Call Tally the messaging app for a moment. */
    static Intent requestIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_SMS)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_SMS);
            }
        }
        return new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.getPackageName());
    }

    /** The dialog that asks the owner to make Google Messages (the remembered app) the messaging app again. */
    static Intent handBackIntent(Context context, String previous) {
        if (previous == null || previous.isEmpty() || previous.equals(context.getPackageName())) previous = GOOGLE_MESSAGES;
        return new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, previous);
    }
}
