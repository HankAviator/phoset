package io.github.hankaviator.phoset;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Keeps only PhoSet-launched reviews transparent, with the original Photos window underneath. */
final class AutomaticReviewWindow {
    static final String EXTRA_AUTOMATIC = "io.github.hankaviator.phoset.AUTOMATIC_REVIEW";
    private static volatile long pendingLaunchDeadline;
    private static final Map<Activity, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class State {
        boolean resumed;
    }

    private AutomaticReviewWindow() {}

    static void install() {
        XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult",
                Intent.class, int.class, Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Intent intent = (Intent) param.args[0];
                        if (SystemClock.elapsedRealtime() > pendingLaunchDeadline || intent == null
                                || intent.getComponent() == null
                                || !isReviewClass(intent.getComponent().getClassName())) {
                            return;
                        }
                        intent.putExtra(EXTRA_AUTOMATIC, true);
                        pendingLaunchDeadline = 0L;
                        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        param.args[2] = ActivityOptions.makeCustomAnimation(
                                (Activity) param.thisObject, 0, 0).toBundle();
                    }
                });
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        if (!isReviewClass(activity.getClass().getName())
                                || !activity.getIntent().getBooleanExtra(EXTRA_AUTOMATIC, false)
                                || !FeatureFlags.isEnabled(FeatureFlags.RECONCILE_CHANGES)) {
                            return;
                        }
                        Window window = activity.getWindow();
                        State state = new State();
                        if (!activity.setTranslucent(true)) {
                            OutOfSyncUiReconciler.log("transparent review unavailable; automatic review cancelled");
                            OutOfSyncUiReconciler.onAutomaticReviewAbandoned();
                            activity.finish();
                            activity.overridePendingTransition(0, 0);
                            return;
                        }
                        STATES.put(activity, state);
                        WindowManager.LayoutParams attributes = window.getAttributes();
                        attributes.alpha = 0f;
                        attributes.dimAmount = 0f;
                        attributes.windowAnimations = 0;
                        attributes.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                        attributes.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                        window.setAttributes(attributes);
                        activity.overridePendingTransition(0, 0);
                        OutOfSyncUiReconciler.onAutomaticReviewCreated();
                        OutOfSyncUiReconciler.log("automatic review window hidden");
                        window.getDecorView().postDelayed(() -> leaveForManualReview(activity,
                                "automatic review timed out"), 5 * 60_000L);
                    }
                });
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                State state = STATES.get(activity);
                if (state != null) {
                    state.resumed = true;
                    OutOfSyncUiReconciler.watchForCompletedReview(activity);
                }
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                State state = STATES.get((Activity) param.thisObject);
                if (state != null) {
                    state.resumed = false;
                }
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                STATES.remove((Activity) param.thisObject);
            }
        });
    }

    static boolean clickChip(View chip) {
        // Also cover listeners that post their launch instead of starting the Activity inline.
        pendingLaunchDeadline = SystemClock.elapsedRealtime() + 5000L;
        boolean accepted = chip.performClick();
        if (!accepted) {
            pendingLaunchDeadline = 0L;
        }
        return accepted;
    }

    static boolean isHidden(Activity activity) {
        return STATES.containsKey(activity);
    }

    static boolean canFinish(Activity activity) {
        State state = STATES.get(activity);
        return state == null ? activity.hasWindowFocus() : state.resumed;
    }

    static void leaveForManualReview(Activity activity, String reason) {
        State state = STATES.remove(activity);
        if (state == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        OutOfSyncUiReconciler.log(reason + "; invisible review cancelled, pending changes retained");
        OutOfSyncUiReconciler.onAutomaticReviewAbandoned();
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }

    private static boolean isReviewClass(String name) {
        return name.endsWith(".outofsync.ui.OutOfSyncReviewActivity");
    }
}
