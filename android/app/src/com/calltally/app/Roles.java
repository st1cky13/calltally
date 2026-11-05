package com.calltally.app;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;

/**
 * Asking to be, and to stop being, the phone's default messaging app, for Catch up encrypted. Android shows the owner a
 * dialog for every change; this only builds the intents and reads who the messaging app is now. Call Tally never sends a
 * message and holds the role only long enough to read, with the owner approving each step.
 */
final class Roles {
    static final String GOOGLE_MESSAGES = "com.google.android.apps.messaging";
    static final String SECURE_DEFAULT = "sms_default_application";

    private Roles() {}

    /** The package of the phone's current messaging app, or null when it can't be read. */
    static String defaultMessaging(Context context) {
        try {
            return Telephony.Sms.getDefaultSmsPackage(context);
        } catch (RuntimeException error) {
            return null;
        }
    }

    /**
     * True while Call Tally is the phone's messaging app. Three signals are tried in turn, and any one saying yes is
     * enough, because on some phones (the owner's Samsung) Settings shows Call Tally as the SMS app but one signal still
     * disagrees. Each is guarded on its own so a failing one can't throw: the canonical role check first, then the
     * messaging-app package, then the secure setting Settings writes.
     */
    static boolean holdsSmsRole(Context context) {
        String self = context.getPackageName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                RoleManager roles = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
                if (roles != null && roles.isRoleHeld(RoleManager.ROLE_SMS)) return true;
            } catch (RuntimeException error) {
                // Fall through to the other signals.
            }
        }
        try {
            if (self.equals(Telephony.Sms.getDefaultSmsPackage(context))) return true;
        } catch (RuntimeException error) {
            // Fall through to the secure setting.
        }
        try {
            if (self.equals(Settings.Secure.getString(context.getContentResolver(), SECURE_DEFAULT))) return true;
        } catch (RuntimeException error) {
            // Nothing more to try.
        }
        return false;
    }

    /**
     * A short line naming what each signal reports, for diagnosis, e.g. "role=held · default=com.calltally.app ·
     * secure=com.calltally.app". Only package names and states, never personal data. Reads system state, so it is not
     * pure, but it takes nothing and changes nothing.
     */
    static String report(Context context) {
        String role = "n/a";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                RoleManager roles = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
                role = (roles != null && roles.isRoleHeld(RoleManager.ROLE_SMS)) ? "held" : "not held";
            } catch (RuntimeException error) {
                role = "n/a";
            }
        }
        return "role=" + role + " · default=" + packageOr(defaultMessaging(context)) + " · secure=" + packageOr(secureDefault(context));
    }

    /** The package the secure setting names as the messaging app, or null when it can't be read or is empty. */
    private static String secureDefault(Context context) {
        try {
            String value = Settings.Secure.getString(context.getContentResolver(), SECURE_DEFAULT);
            return (value == null || value.isEmpty()) ? null : value;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String packageOr(String value) {
        return (value == null || value.isEmpty()) ? "none" : value;
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

    /**
     * The best way, on this phone, to let the owner make Google Messages the default messaging app again. On Android 10+
     * an app cannot assign the messaging role to another app, so this never changes the default itself. It opens Google
     * Messages, which then offers to become the default; or, when that app is not installed, the system default-apps
     * screen, or plain Settings, so the owner picks it there. Returns the first intent that can be opened, or null when
     * none can. The owner stays in control: this only ever opens Google Messages or a Settings screen.
     */
    static Intent restoreMessagingIntent(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(GOOGLE_MESSAGES);
        if (launch != null) return launch;
        Intent defaults = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
        if (defaults.resolveActivity(context.getPackageManager()) != null) return defaults;
        Intent settings = new Intent(Settings.ACTION_SETTINGS);
        if (settings.resolveActivity(context.getPackageManager()) != null) return settings;
        return null;
    }
}
