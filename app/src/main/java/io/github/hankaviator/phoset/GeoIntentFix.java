package io.github.hankaviator.phoset;

import android.app.Instrumentation;
import android.content.Intent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class GeoIntentFix {
    private GeoIntentFix() {}

    static void install() {
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.isEnabled(FeatureFlags.GEO_INTENT_FIX)) {
                    return;
                }
                for (int index = 0; index < param.args.length; index++) {
                    if (param.args[index] instanceof Intent) {
                        Intent original = (Intent) param.args[index];
                        Intent rewritten = IntentRewriter.rewrite(original);
                        if (rewritten != original) {
                            param.args[index] = rewritten;
                        }
                        return;
                    }
                }
            }
        });
    }
}
