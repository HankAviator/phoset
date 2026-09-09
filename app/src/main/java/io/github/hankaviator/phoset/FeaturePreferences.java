package io.github.hankaviator.phoset;

import android.content.Context;
import android.content.SharedPreferences;
import android.annotation.SuppressLint;

/** Private preference storage owned by the PhoSet app process. */
final class FeaturePreferences {
    static final String FILE_NAME = "features";

    private FeaturePreferences() {}

    static boolean isEnabled(Context context, String feature) {
        return preferences(context).getBoolean(feature, true);
    }

    static void setEnabled(Context context, String feature, boolean enabled) {
        if (!FeatureFlags.ALL.contains(feature)) {
            throw new IllegalArgumentException("Unknown feature " + feature);
        }
        preferences(context).edit().putBoolean(feature, enabled).apply();
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("WorldReadableFiles")
    private static SharedPreferences preferences(Context context) {
        try {
            // LSPosed API 93 redirects this to its private, cross-process bridge.
            return context.getSharedPreferences(FILE_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException ignored) {
            // Keeps the settings screen usable before the module is enabled.
            return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
        }
    }
}
