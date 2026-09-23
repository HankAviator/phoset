package io.github.hankaviator.phoset;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Uses Photos' own review controls, identified by stable resource names. */
final class OutOfSyncUiReconciler {
    private static final String TAG = "PhoSetSync";
    private static final String PHOTOS = "com.google.android.apps.photos";
    private static final int MAX_BATCH = 100;
    private static final long REVIEW_WINDOW_MS = 30_000L;
    private static final Map<View, Boolean> SCHEDULED_CHIPS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Boolean> VISIBILITY_FALLBACK =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Boolean> CLICKED_BUTTONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile long automaticReviewStarted;
    private static volatile int actionsThisReview;

    private static final String[][] CARDS = {
            {"photos_outofsync_ui_edited_title",
                    "photos_outofsync_ui_edited_change_all_button_text"},
            {"photos_outofsync_ui_trashed_title",
                    "photos_outofsync_ui_trash_all_button_text"},
            {"photos_outofsync_ui_restored_title",
                    "photos_outofsync_ui_restore_all_button_text"},
            {"photos_outofsync_ui_deleted_title",
                    "photos_outofsync_ui_delete_all_button_text"},
            {"photos_outofsync_ui_vaulted_title",
                    "photos_outofsync_ui_vault_all_button_text"}
    };

    private OutOfSyncUiReconciler() {}

    static void install() {
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;
                        if ((int) param.args[0] != View.VISIBLE
                                || !isReviewChip(view)
                                || !FeatureFlags.isEnabled(FeatureFlags.RECONCILE_CHANGES)
                                || VISIBILITY_FALLBACK.containsKey(view)) {
                            return;
                        }
                        if (SCHEDULED_CHIPS.put(view, true) == null) {
                            // The click listener is already installed when Photos shows the chip.
                            view.postDelayed(() -> openReview(view), 250L);
                        }
                        param.args[0] = View.GONE;
                    }
                });
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;
                        if (!hasResourceName(view, "card_handle_all_button")
                                && !hasResourceName(view, "empty_page_title_bottom")) {
                            return;
                        }
                        if (!FeatureFlags.isEnabled(FeatureFlags.RECONCILE_CHANGES)) {
                            return;
                        }
                        if (hasResourceName(view, "card_handle_all_button")) {
                            view.postDelayed(() -> processCard(view), 500L);
                        } else if (hasResourceName(view, "empty_page_title_bottom")) {
                            view.postDelayed(() -> finishEmptyAutomaticReview(view), 1500L);
                        }
                    }
                });
        XposedBridge.log(TAG + ": resource-based review controls installed");
    }

    private static boolean isReviewChip(View view) {
        if (!(view instanceof TextView)
                || !"Chip".equals(view.getClass().getSimpleName())) {
            return false;
        }
        String expected = resourceString(view.getResources(),
                "photos_outofsync_strings_review_out_of_sync_text");
        return expected != null && TextUtils.equals(((TextView) view).getText(), expected);
    }

    private static void openReview(View chip) {
        if (!FeatureFlags.isEnabled(FeatureFlags.RECONCILE_CHANGES)) {
            restoreChip(chip);
            return;
        }
        if (!chip.isAttachedToWindow() || !chip.hasOnClickListeners()) {
            restoreChip(chip);
            return;
        }
        automaticReviewStarted = SystemClock.elapsedRealtime();
        actionsThisReview = 0;
        if (!chip.performClick()) {
            automaticReviewStarted = 0L;
            restoreChip(chip);
        }
    }

    private static void restoreChip(View chip) {
        SCHEDULED_CHIPS.remove(chip);
        VISIBILITY_FALLBACK.put(chip, true);
        chip.setVisibility(View.VISIBLE);
        log("review chip retained because its action was unavailable");
    }

    private static void processCard(View button) {
        if (!FeatureFlags.isEnabled(FeatureFlags.RECONCILE_CHANGES)
                || !button.isAttachedToWindow() || !button.isEnabled()
                || CLICKED_BUTTONS.containsKey(button)) {
            return;
        }
        Activity activity = activityFrom(button.getContext());
        if (activity == null || !activity.getClass().getName().contains(".outofsync.ui.")) {
            return;
        }
        View card = findCard(button);
        if (card == null) {
            log("review card structure changed; action left for manual review");
            return;
        }
        TextView title = card.findViewById(resourceId(button, "card_title"));
        TextView message = card.findViewById(resourceId(button, "card_message"));
        if (title == null || message == null || !(button instanceof TextView)
                || !isKnownCard(title, (TextView) button)) {
            log("unknown review card; action left for manual review");
            return;
        }
        int count = firstCount(message.getText());
        if (count < 1 || count > MAX_BATCH || actionsThisReview >= CARDS.length) {
            log("review card count is missing or exceeds safety cap: " + count);
            return;
        }
        CLICKED_BUTTONS.put(button, true);
        actionsThisReview++;
        log("applying Photos review action: " + title.getText() + " (" + count + ")");
        if (!button.performClick()) {
            CLICKED_BUTTONS.remove(button);
            log("Photos review button did not accept the click");
        }
    }

    private static View findCard(View button) {
        View current = button;
        for (int depth = 0; depth < 6; depth++) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                return null;
            }
            current = (View) parent;
            int titleId = resourceId(button, "card_title");
            int messageId = resourceId(button, "card_message");
            if (titleId != 0 && messageId != 0
                    && current.findViewById(titleId) != null
                    && current.findViewById(messageId) != null) {
                return current;
            }
        }
        return null;
    }

    private static boolean isKnownCard(TextView title, TextView button) {
        Resources resources = title.getResources();
        for (String[] card : CARDS) {
            String expectedTitle = resourceString(resources, card[0]);
            String expectedAction = resourceString(resources, card[1]);
            if (expectedTitle != null && expectedAction != null
                    && TextUtils.equals(title.getText(), expectedTitle)
                    && TextUtils.equals(button.getText(), expectedAction)) {
                return true;
            }
        }
        return false;
    }

    private static int firstCount(CharSequence message) {
        if (message == null) {
            return -1;
        }
        int count = 0;
        boolean found = false;
        for (int index = 0; index < message.length();) {
            int codePoint = Character.codePointAt(message, index);
            index += Character.charCount(codePoint);
            int digit = Character.digit(codePoint, 10);
            if (digit >= 0) {
                found = true;
                count = count * 10 + digit;
                if (count > MAX_BATCH) {
                    return count;
                }
            } else if (found && codePoint != ',' && codePoint != '.'
                    && codePoint != ' ' && codePoint != 0x202f) {
                break;
            }
        }
        return found ? count : -1;
    }

    private static void finishEmptyAutomaticReview(View emptyTitle) {
        long started = automaticReviewStarted;
        if (started == 0L || SystemClock.elapsedRealtime() - started > REVIEW_WINDOW_MS) {
            return;
        }
        Activity activity = activityFrom(emptyTitle.getContext());
        if (activity != null && activity.getClass().getName().contains(".outofsync.ui.")) {
            automaticReviewStarted = 0L;
            activity.finish();
        }
    }

    private static Activity activityFrom(Context context) {
        for (int depth = 0; context instanceof ContextWrapper && depth < 10; depth++) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static int resourceId(View view, String name) {
        return view.getResources().getIdentifier(name, "id", PHOTOS);
    }

    private static String resourceString(Resources resources, String name) {
        int id = resources.getIdentifier(name, "string", PHOTOS);
        return id == 0 ? null : resources.getString(id);
    }

    private static boolean hasResourceName(View view, String name) {
        int id = view.getId();
        if (id == 0 || id == View.NO_ID) {
            return false;
        }
        try {
            return name.equals(view.getResources().getResourceEntryName(id));
        } catch (Resources.NotFoundException ignored) {
            return false;
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
