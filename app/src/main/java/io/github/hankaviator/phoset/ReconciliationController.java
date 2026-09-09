package io.github.hankaviator.phoset;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Applies Photos' recognized out-of-sync collections through its own operations. The implementation
 * deliberately fails closed when any validated internal shape changes.
 */
final class ReconciliationController {
    private static final String TAG = "PhoSetSync";
    private static final String PENDING_TRASH = "PENDING_TRASH";
    private static final String EDIT = "EDIT";
    private static final String[] OOS_CATEGORIES =
            new String[]{"EDIT", "TRASH", "RESTORE", "DELETE", "VAULT"};
    private static final int READY_TO_SHOW = 1;
    private static final int MAX_BATCH = 100;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, RunState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static ClassLoader loader;
    private static Method chipMethod;
    private static volatile Boolean structureValid;

    private ReconciliationController() {}

    static void install(ClassLoader classLoader) {
        loader = classLoader;
        Class<?> chipClass = XposedHelpers.findClassIfExists("aokz", classLoader);
        if (chipClass == null) {
            always("disabled: aokz missing", null);
            return;
        }
        Method candidate = null;
        for (Method method : chipClass.getDeclaredMethods()) {
            if ("f".equals(method.getName()) && method.getParameterTypes().length == 0
                    && method.getReturnType() == void.class) {
                candidate = method;
                break;
            }
        }
        if (candidate == null) {
            always("disabled: validated chip boundary missing", null);
            return;
        }
        chipMethod = candidate;
        XposedBridge.hookMethod(candidate, new XC_MethodHook(10000) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                interceptChip(param);
            }
        });
        debug("installed at validated chip boundary");
    }

    private static void interceptChip(XC_MethodHook.MethodHookParam param) {
        if (!FeatureFlags.isEnabled(FeatureFlags.RECONCILE_CHANGES)) {
            return;
        }
        Object chip = param.thisObject;
        try {
            Object model = XposedHelpers.getObjectField(chip, "b");
            int accountId = XposedHelpers.getIntField(model, "d");
            int modelState = XposedHelpers.getIntField(model, "e");
            if (modelState != READY_TO_SHOW || accountId < 0) return;

            RunState current = STATES.get(chip);
            if (current == RunState.RUNNING || current == RunState.RESOLVING) {
                param.setResult(null);
                return;
            }

            Activity activity = activityFromChip(chip);
            if (!validateStructure(accountId)) return;
            STATES.put(chip, RunState.RUNNING);
            // Defer the chip while Photos' typed OOS collections are classified. On any
            // empty/ambiguous/error result, the untouched stock method is invoked below.
            param.setResult(null);
            Thread worker = new Thread(
                    () -> loadAndApply(chip, model, activity, accountId),
                    "PhoSetSync-classify");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable t) {
            STATES.remove(chip);
            always("classification setup failed; leaving stock UI", t);
        }
    }

    private static void loadAndApply(Object chip, Object model, Activity activity, int accountId) {
        try {
            PendingBatch pending = loadPendingBatch(activity, accountId);
            int total = pending.totalItems;
            if (total == 0) {
                showStock(chip, "no recognized pending OOS candidates");
                return;
            }
            if (total > MAX_BATCH) {
                showStock(chip, "OOS batch exceeds safety cap: " + total);
                return;
            }
            MAIN.post(() -> approveWithPhotosInternals(
                    chip, model, activity, accountId, pending));
        } catch (Throwable t) {
            always("OOS collection load failed; restoring stock UI", t);
            showStock(chip, "load failure");
        }
    }

    private static PendingBatch loadPendingBatch(Context context, int accountId) {
        validateRuntimeCategories();
        PendingBatch batch = new PendingBatch();
        Class<?> categoryClass = requireClass("aokr");
        Object queryOptions = XposedHelpers.getStaticObjectField(requireClass("wej"), "a");
        for (String name : OOS_CATEGORIES) {
            Object category = XposedHelpers.callStaticMethod(categoryClass, "b", name);
            Object collection = XposedHelpers.callMethod(category, "c", accountId);
            Object key = XposedHelpers.callStaticMethod(requireClass("wga"), "b", collection);
            Object featureSet = XposedHelpers.getStaticObjectField(
                    requireClass(EDIT.equals(name) ? "aoky" : "bhyz"), "e");
            Object loaded = XposedHelpers.callStaticMethod(requireClass("wfs"), "H",
                    context, key, queryOptions, featureSet);
            if (!(loaded instanceof List<?>)) {
                throw new IllegalStateException(name + " collection load was not a list");
            }
            List<?> items = (List<?>) loaded;
            batch.totalItems += items.size();
            if (EDIT.equals(name)) {
                batch.editKeys.addAll(editKeysFromMedia(items));
            } else {
                batch.mediaUris.put(name, urisFromMedia(items));
            }
        }
        debug("recognized pending OOS batch=" + batch);
        return batch;
    }

    private static LinkedHashSet<String> editKeysFromMedia(List<?> media) {
        Class<?> featureClass = requireClass("yyz");
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Object item : media) {
            Object feature = XposedHelpers.callMethod(item, "b", featureClass);
            Object value = XposedHelpers.callMethod(feature, "a");
            if (!(value instanceof String) || TextUtils.isEmpty((String) value)
                    || !keys.add((String) value)) {
                throw new IllegalStateException("EDIT item has an invalid or duplicate dedup key");
            }
        }
        return keys;
    }

    private static LinkedHashSet<Uri> urisFromMedia(List<?> media) {
        Class<?> featureClass = requireClass("azhj");
        LinkedHashSet<Uri> uris = new LinkedHashSet<>();
        for (Object item : media) {
            Object feature = XposedHelpers.callMethod(item, "b", featureClass);
            Object values = XposedHelpers.getObjectField(feature, "a");
            if (!(values instanceof List<?>) || ((List<?>) values).isEmpty()) {
                throw new IllegalStateException("OOS item lacks a resolved URI feature");
            }
            boolean found = false;
            for (Object resolved : (List<?>) values) {
                String raw = (String) XposedHelpers.getObjectField(resolved, "a");
                // Photos' own bhyz resolver applies azhm.c() first, which drops
                // unresolved aliases whose URI string is empty, then maps the
                // remaining aliases to Uri and collects them into a set.
                if (TextUtils.isEmpty(raw)) {
                    continue;
                }
                Uri uri = Uri.parse(raw);
                if (!isSafeMediaUri(uri)) {
                    throw new IllegalStateException("unsafe OOS URI " + uri);
                }
                uris.add(uri);
                found = true;
            }
            if (!found) {
                throw new IllegalStateException("OOS item has no safe resolved URI");
            }
        }
        return uris;
    }

    private static void validateRuntimeCategories() {
        Object[] runtimeValues = (Object[]) XposedHelpers.callStaticMethod(requireClass("aokr"), "values");
        LinkedHashSet<String> runtimeNames = new LinkedHashSet<>();
        for (Object value : runtimeValues) runtimeNames.add(((Enum<?>) value).name());
        LinkedHashSet<String> expectedNames = new LinkedHashSet<>();
        Collections.addAll(expectedNames, OOS_CATEGORIES);
        if (!runtimeNames.equals(expectedNames)) {
            throw new IllegalStateException("OOS categories changed: " + runtimeNames);
        }
    }

    private static LinkedHashMap<String, Integer> loadPendingCategoryCounts(
            Context context, int accountId) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        validateRuntimeCategories();
        Class<?> categoryClass = requireClass("aokr");

        Object queryOptions = XposedHelpers.getStaticObjectField(requireClass("wej"), "a");
        for (String name : OOS_CATEGORIES) {
            Object category = XposedHelpers.callStaticMethod(categoryClass, "b", name);
            Object collection = XposedHelpers.callMethod(category, "c", accountId);
            Object key = XposedHelpers.callStaticMethod(requireClass("wga"), "b", collection);
            Object featureSet = XposedHelpers.getStaticObjectField(
                    requireClass(EDIT.equals(name) ? "aoky" : "bhyz"), "e");
            Object loaded = XposedHelpers.callStaticMethod(requireClass("wfs"), "H",
                    context, key, queryOptions, featureSet);
            if (!(loaded instanceof List<?>)) {
                throw new IllegalStateException(name + " collection load was not a list");
            }
            counts.put(name, ((List<?>) loaded).size());
        }
        debug("recognized pending OOS counts=" + counts);
        return counts;
    }

    private static void approveWithPhotosInternals(Object chip, Object model, Context context,
                                                   int accountId, PendingBatch pending) {
        try {
            STATES.put(chip, RunState.RESOLVING);
            validatePendingBatch(context, pending);
            Object helper = null;
            if (pending.hasMediaOperations()) {
                helper = findTrashHelper(context);
                if (helper == null) {
                    throw new IllegalStateException("Photos media operation helper unavailable");
                }
            }
            if (!pending.editKeys.isEmpty()) submitEdits(context, accountId, pending.editKeys);
            for (Map.Entry<String, LinkedHashSet<Uri>> entry : pending.mediaUris.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    submitMediaOperation(helper, entry.getKey(), entry.getValue());
                }
            }
            pollForAllResolved(chip, model, context, accountId, 0, 0);
        } catch (Throwable t) {
            always("stock OOS resolver rejected operation; restoring review UI", t);
            showStock(chip, "resolver submission failure");
        }
    }

    private static void validatePendingBatch(Context context, PendingBatch pending) {
        for (Map.Entry<String, LinkedHashSet<Uri>> entry : pending.mediaUris.entrySet()) {
            String category = entry.getKey();
            for (Uri uri : entry.getValue()) {
                int trashState = queryTrashState(context.getContentResolver(), uri);
                if (trashState < 0
                        || ("TRASH".equals(category) && trashState != 0)
                        || ("RESTORE".equals(category) && trashState != 1)) {
                    throw new IllegalStateException(
                            category + " URI has ambiguous state: " + uri);
                }
            }
        }
    }

    private static void submitEdits(Context context, int accountId, Set<String> keys) throws Exception {
        Class<?> immutableSetClass = requireClass("com.google.common.collect.ImmutableSet");
        Object immutableKeys = XposedHelpers.callStaticMethod(immutableSetClass, "G", keys);
        Class<?> taskClass = requireClass(
                "com.google.android.apps.photos.editor.sync.observers.ResolvePendingEditsTask");
        Method factory = taskClass.getDeclaredMethod("f", int.class, immutableSetClass, int.class);
        if (!Modifier.isStatic(factory.getModifiers()) || factory.getReturnType() != taskClass) {
            throw new IllegalStateException("EDIT task factory changed");
        }
        factory.setAccessible(true);
        Object task = factory.invoke(null, accountId, immutableKeys, 0x7f0b1190);
        Method enqueue = requireClass("bvnk").getDeclaredMethod(
                "n", Context.class, requireClass("bvne"));
        if (!Modifier.isStatic(enqueue.getModifiers()) || enqueue.getReturnType() != void.class) {
            throw new IllegalStateException("EDIT enqueue signature changed");
        }
        enqueue.setAccessible(true);
        enqueue.invoke(null, context.getApplicationContext(), task);
        debug("submitted stock EDIT task for keys=" + keys);
    }

    private static void submitMediaOperation(Object helper, String category, Set<Uri> uris)
            throws Exception {
        String tag = "PhoSetSync_" + category + "_" + SEQUENCE.incrementAndGet();
        String callbackName = "RESTORE".equals(category) ? "bhxq"
                : ("TRASH".equals(category) ? "bhxr" : "bhxp");
        String registerName = "RESTORE".equals(category) ? "b"
                : ("TRASH".equals(category) ? "c" : "a");
        String operationName = "RESTORE".equals(category) ? "h"
                : ("TRASH".equals(category) ? "i" : "f");
        Class<?> callbackType = requireClass(callbackName);
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return "PhoSetSync-" + category;
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                    }
                    if ("a".equals(method.getName())) debug(category + " helper completed");
                    return null;
                });
        Method register = helper.getClass().getDeclaredMethod(registerName, String.class, callbackType);
        register.setAccessible(true);
        register.invoke(helper, tag, callback);
        Method operation = helper.getClass().getDeclaredMethod(
                operationName, Parcelable.class, String.class, Set.class);
        operation.setAccessible(true);
        operation.invoke(helper, null, tag, uris);
        debug("submitted stock " + category + " helper operation for URIs=" + uris);
    }

    private static final class PendingBatch {
        final LinkedHashSet<String> editKeys = new LinkedHashSet<>();
        final LinkedHashMap<String, LinkedHashSet<Uri>> mediaUris = new LinkedHashMap<>();
        int totalItems;

        boolean hasMediaOperations() {
            for (Set<Uri> uris : mediaUris.values()) {
                if (!uris.isEmpty()) return true;
            }
            return false;
        }

        @Override
        public String toString() {
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
            counts.put(EDIT, editKeys.size());
            for (Map.Entry<String, LinkedHashSet<Uri>> entry : mediaUris.entrySet()) {
                counts.put(entry.getKey(), entry.getValue().size());
            }
            return counts.toString();
        }
    }

    private static void pollForAllResolved(Object chip, Object model, Context context,
                                           int accountId, int attempt, int emptyStreak) {
        MAIN.postDelayed(() -> {
            Thread worker = new Thread(() -> {
                try {
                    LinkedHashMap<String, Integer> remaining =
                            loadPendingCategoryCounts(context, accountId);
                    int total = 0;
                    for (int count : remaining.values()) total += count;
                    if (total == 0 && emptyStreak >= 2) {
                        debug("all recognized OOS changes resolved");
                        MAIN.post(() -> refreshAfterSuccess(chip, model, accountId));
                    } else if (attempt < 59) {
                        pollForAllResolved(chip, model, context, accountId, attempt + 1,
                                total == 0 ? emptyStreak + 1 : 0);
                    } else {
                        showStock(chip, "OOS changes remain unresolved after timeout: " + remaining);
                    }
                } catch (Throwable t) {
                    always("could not validate OOS completion; restoring review UI", t);
                    showStock(chip, "completion validation failure");
                }
            }, "PhoSetSync-verify");
            worker.setDaemon(true);
            worker.start();
        }, 2000L);
    }

    private static void refreshAfterSuccess(Object chip, Object model, int accountId) {
        // Re-run Photos' own aggregate OOS loader. Any unresolved or newly arrived
        // change will cause another validated pass or restore the stock chip.
        XposedHelpers.callMethod(model, "b", accountId);
        STATES.put(chip, RunState.RUNNING);
        MAIN.postDelayed(() -> {
            STATES.remove(chip);
            try {
                XposedHelpers.callMethod(chip, "f");
            } catch (Throwable t) {
                always("post-success OOS refresh failed", t);
            }
        }, 2000L);
    }

    private static void showStock(Object chip, String reason) {
        MAIN.post(() -> {
            STATES.remove(chip);
            debug("stock review retained: " + reason);
            try {
                XposedBridge.invokeOriginalMethod(chipMethod, chip, new Object[0]);
            } catch (Throwable t) {
                always("failed to restore stock chip", t);
            }
        });
    }

    private static Activity activityFromChip(Object chip) {
        Object fragment = XposedHelpers.getObjectField(chip, "a");
        Object activity = XposedHelpers.callMethod(fragment, "J");
        if (!(activity instanceof Activity)) throw new IllegalStateException("no host Activity");
        return (Activity) activity;
    }

    private static Object findTrashHelper(Context context) {
        Object helper = XposedHelpers.callStaticMethod(requireClass("bxjj"), "f",
                context, requireClass("bhxs"), null);
        if (helper == null || !"bhxi".equals(helper.getClass().getName())) {
            always("disabled: validated Photos trash helper unavailable", null);
            return null;
        }
        return helper;
    }

    private static boolean validateStructure(int accountId) {
        Boolean known = structureValid;
        if (known != null) return known;
        synchronized (ReconciliationController.class) {
            if (structureValid != null) return structureValid;
            try {
                Class<?> bhxs = requireClass("bhxs");
                Class<?> bhxp = requireClass("bhxp");
                Class<?> bhxq = requireClass("bhxq");
                Class<?> bhxr = requireClass("bhxr");
                Class<?> bhxi = requireClass("bhxi");
                bhxi.getDeclaredMethod("i", Parcelable.class, String.class, Set.class);
                bhxi.getDeclaredMethod("h", Parcelable.class, String.class, Set.class);
                bhxi.getDeclaredMethod("f", Parcelable.class, String.class, Set.class);
                bhxi.getDeclaredMethod("a", String.class, bhxp);
                bhxi.getDeclaredMethod("b", String.class, bhxq);
                bhxi.getDeclaredMethod("c", String.class, bhxr);
                if (!bhxs.isAssignableFrom(bhxi) || !bhxp.isInterface()
                        || !bhxq.isInterface() || !bhxr.isInterface()) {
                    throw new IllegalStateException("trash helper interfaces changed");
                }
                Class<?> immutableSet = requireClass("com.google.common.collect.ImmutableSet");
                Class<?> baseTask = requireClass("bvne");
                Class<?> editTask = requireClass(
                        "com.google.android.apps.photos.editor.sync.observers.ResolvePendingEditsTask");
                Method editFactory = editTask.getDeclaredMethod(
                        "f", int.class, immutableSet, int.class);
                Method enqueue = requireClass("bvnk").getDeclaredMethod(
                        "n", Context.class, baseTask);
                if (!Modifier.isStatic(editFactory.getModifiers())
                        || editFactory.getReturnType() != editTask
                        || !Modifier.isStatic(enqueue.getModifiers())
                        || enqueue.getReturnType() != void.class
                        || !baseTask.isAssignableFrom(editTask)) {
                    throw new IllegalStateException("EDIT task structure changed");
                }
                requireClass("yyz").getDeclaredMethod("a");
                XposedHelpers.getStaticObjectField(requireClass("aoky"), "e");
                XposedHelpers.getStaticObjectField(requireClass("bhyz"), "e");
                validateRuntimeCategories();
                Object collection = XposedHelpers.callStaticMethod(requireClass("qq"), "B", accountId);
                Object category = XposedHelpers.getObjectField(collection, "b");
                if (!(category instanceof Enum<?>)
                        || !PENDING_TRASH.equals(((Enum<?>) category).name())) {
                    throw new IllegalStateException("TRASH collection mapping changed");
                }
                structureValid = true;
                return true;
            } catch (Throwable t) {
                structureValid = false;
                always("disabled: Photos runtime validation failed", t);
                return false;
            }
        }
    }

    private static boolean isSafeMediaUri(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority())) {
            return false;
        }
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 4) return false;
        if (!("external".equals(parts.get(0)) || "external_primary".equals(parts.get(0)))) return false;
        if (!("images".equals(parts.get(1)) || "video".equals(parts.get(1)))) return false;
        if (!"media".equals(parts.get(2))) return false;
        try {
            return Long.parseLong(parts.get(3)) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int queryTrashState(ContentResolver resolver, Uri uri) {
        Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.IS_TRASHED},
                queryArgs, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.getCount() != 1) return -1;
            return cursor.getInt(0);
        } catch (Throwable t) {
            always("MediaStore state query failed for " + uri, t);
            return -1;
        }
    }

    private static Class<?> requireClass(String name) {
        Class<?> type = XposedHelpers.findClassIfExists(name, loader);
        if (type == null) throw new IllegalStateException("missing class " + name);
        return type;
    }

    private static void debug(String message) {
        // Debug builds are the opt-in switch. Release builds retain only safety failures.
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

    private enum RunState { RUNNING, RESOLVING }
}
