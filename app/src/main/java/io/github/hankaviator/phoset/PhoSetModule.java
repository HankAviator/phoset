package io.github.hankaviator.phoset;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed entry point, scoped exclusively to Google Photos. */
public final class PhoSetModule implements IXposedHookLoadPackage {
    private static final String PHOTOS_PACKAGE = "com.google.android.apps.photos";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!PHOTOS_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }

        GeoIntentFix.install();
        ReconciliationController.install(loadPackageParam.classLoader);
        TrashConfirmationBypass.install(loadPackageParam.classLoader);
    }
}
