package io.github.hankaviator.phoset;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/** Applies Material You colors on supported Android versions. */
public final class PhoSetApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
