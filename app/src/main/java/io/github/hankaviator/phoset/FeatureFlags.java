package io.github.hankaviator.phoset;

import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/** Feature flag client used from inside the Google Photos process. */
final class FeatureFlags {
    static final String GEO_INTENT_FIX = "geo_intent_fix";
    static final String RECONCILE_CHANGES = "reconcile_changes";
    static final String SKIP_TRASH_CONFIRMATION = "skip_trash_confirmation";

    static final Set<String> ALL = Set.of(
            GEO_INTENT_FIX, RECONCILE_CHANGES, SKIP_TRASH_CONFIRMATION);

    private FeatureFlags() {}

    static boolean isEnabled(String feature) {
        if (!ALL.contains(feature)) {
            return false;
        }
        try {
            XSharedPreferences preferences = new XSharedPreferences(
                    BuildConfig.APPLICATION_ID, FeaturePreferences.FILE_NAME);
            preferences.reload();
            return preferences.getBoolean(feature, true);
        } catch (Throwable ignored) {
            // Defaults are enabled, including during installation or framework startup.
            return true;
        }
    }
}
