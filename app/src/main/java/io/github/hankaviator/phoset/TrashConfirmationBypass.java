package io.github.hankaviator.phoset;

import android.app.Dialog;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Automatically accepts only Google Photos' identifiable move-to-trash dialogs. */
final class TrashConfirmationBypass {
    private static final String TAG = "PhoSet";
    private static final String PHOTOS_PACKAGE = "com.google.android.apps.photos";

    /*
     * These are Android resource entry names, not numeric IDs or obfuscated Java
     * symbols. aapt keeps entry names available to Resources at runtime, and Photos
     * has retained these feature-specific names while its R8 class names changed.
     */
    private static final String[] TRASH_DIALOG_MARKERS = {
            "photos_allphotos_ui_actionconfirmation_all_move_to_trash_dialog",
            "photos_allphotos_ui_actionconfirmation_dialog_r_title",
            "photos_allphotos_ui_actionconfirmation_intersitital_dialog_r",
            "photos_allphotos_ui_actionconfirmation_signed_out_dialog"
    };

    private static final String[] FIXED_POSITIVE_LABELS = {
            "delete_interstitial_positive_text",
            "photos_allphotos_ui_actionconfirmation_positive_button_r",
            "photos_allphotos_ui_actionconfirmation_trash_one",
            "photos_allphotos_ui_actionconfirmation_iterstitial_positive_button_r",
            "photos_allphotos_ui_actionconfirmation_signed_in_interstitial_dialog_reduced_trash_time_positive_button"
    };

    private TrashConfirmationBypass() {}

    static void install(ClassLoader ignored) {
        XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook(10_000) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                acceptIfTrashConfirmation((Dialog) param.thisObject);
            }
        });
        debug("resource-identified move-to-trash confirmation bypass installed");
    }

    private static void acceptIfTrashConfirmation(Dialog dialog) {
        try {
            Context context = dialog.getContext();
            if (context == null || !PHOTOS_PACKAGE.equals(context.getPackageName())) {
                return;
            }
            if (!FeatureFlags.isEnabled(FeatureFlags.SKIP_TRASH_CONFIRMATION)) {
                return;
            }

            Resources resources = context.getResources();
            boolean hasMarker = false;
            for (String marker : TRASH_DIALOG_MARKERS) {
                if (findPhotosView(dialog, resources, marker) != null) {
                    hasMarker = true;
                    break;
                }
            }

            // One Photos variant uses a custom row instead of an AlertDialog button.
            View customPositive = findPhotosView(dialog, resources, "move_to_trash");
            if (hasMarker && customPositive != null && customPositive.isClickable()) {
                customPositive.performClick();
                debug("accepted custom move-to-trash confirmation");
                return;
            }

            View positive = dialog.findViewById(android.R.id.button1);
            if (!(positive instanceof TextView) || !positive.isClickable()) {
                return;
            }

            CharSequence label = ((TextView) positive).getText();
            if (!hasMarker && !isKnownTrashLabel(resources, label)) {
                return;
            }

            positive.performClick();
            debug("accepted move-to-trash confirmation");
        } catch (Throwable error) {
            // Never interfere with an unrecognized or changed dialog.
            always("could not identify trash confirmation; retaining stock dialog", error);
        }
    }

    @SuppressLint("DiscouragedApi")
    private static View findPhotosView(Dialog dialog, Resources resources, String entryName) {
        int id = resources.getIdentifier(entryName, "id", PHOTOS_PACKAGE);
        return id == 0 ? null : dialog.findViewById(id);
    }

    @SuppressLint("DiscouragedApi")
    private static boolean isKnownTrashLabel(Resources resources, CharSequence actual) {
        if (actual == null) {
            return false;
        }
        String text = actual.toString();
        for (String name : FIXED_POSITIVE_LABELS) {
            int id = resources.getIdentifier(name, "string", PHOTOS_PACKAGE);
            if (id != 0 && text.contentEquals(resources.getText(id))) {
                return true;
            }
        }

        int pluralId = resources.getIdentifier(
                "photos_allphotos_ui_actionconfirmation_trash", "plurals", PHOTOS_PACKAGE);
        if (pluralId == 0) {
            return false;
        }
        return matchesFormattedQuantity(text, resources.getQuantityString(pluralId, 1))
                || matchesFormattedQuantity(text, resources.getQuantityString(pluralId, 2));
    }

    private static boolean matchesFormattedQuantity(String actual, String format) {
        int token = format.indexOf('%');
        if (token < 0) {
            return actual.equals(format);
        }
        int tokenEnd = token + 1;
        while (tokenEnd < format.length()
                && !Character.isLetter(format.charAt(tokenEnd))) {
            tokenEnd++;
        }
        if (tokenEnd >= format.length()) {
            return false;
        }
        String prefix = format.substring(0, token);
        String suffix = format.substring(tokenEnd + 1);
        return actual.length() > prefix.length() + suffix.length()
                && actual.startsWith(prefix)
                && actual.endsWith(suffix);
    }

    private static void debug(String message) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, message);
            XposedBridge.log(TAG + ": " + message);
        }
    }

    private static void always(String message, Throwable error) {
        Log.e(TAG, message, error);
        XposedBridge.log(TAG + ": " + message
                + (error == null ? "" : "\n" + Log.getStackTraceString(error)));
    }
}
