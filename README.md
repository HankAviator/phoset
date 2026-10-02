# PhoSet

PhoSet combines three opt-in Google Photos behavior fixes in one LSPosed module.
Each feature has its own switch in a Material 3 Expressive settings screen.

## Features

- **Coordinate links** normalizes exact coordinate intents from Google Photos
  into interoperable `geo:` links while preserving a visible map pin.
- **Reconcile device changes** automatically applies recognized Google Photos
  out-of-sync `EDIT`, `TRASH`, `RESTORE`, `DELETE`, and `VAULT` changes through
  Photos' own review controls in an invisible, transparent window. Your Photos
  screen stays visible and can receive taps while changes are applied. The
  automatic review closes when Photos shows the completed empty state. If an
  action is unrecognized, unavailable, or times out, PhoSet closes its invisible
  window and retains the review chip for manual attention. Recognized batches
  have no 100-photo limit; large batches
  are handled by Photos' own action and allowed up to five minutes to complete.
- **Skip trash confirmation** activates Google Photos' real positive action for
  identified move-to-trash dialogs, without bypassing Android or ROM permission
  prompts.

All three features are enabled by default. Open PhoSet from the module's
**Settings** button in LSPosed to change them.

## Scope

The module is scoped only to `com.google.android.apps.photos`. Its package name
is `io.github.hankaviator.phoset`. Settings are shared with the hooked process
through LSPosed's API 93 preferences bridge.

## Update resilience

The coordinate fix hooks an Android framework boundary and does not depend on
Photos class names. Trash confirmations and out-of-sync review controls are
recognized using resource names and localized strings rather than obfuscated
Java symbols. The reconciliation feature checks the review category, action,
and a valid positive photo count before pressing Photos' own action button.
Unknown or changed review cards remain available for manual review.

Development and trash-dialog analysis used Google Photos
`7.91.0.973540846`. The resource-based reconciliation flow was checked against
Google Photos `7.93.0.982110057` and `7.94.0.988717361`. PhoSet `0.1.3` was
verified on a real 106-photo pending-trash batch: Photos applied the action,
the review closed automatically, and reopening it showed no pending changes.
PhoSet `0.1.4` adds the invisible automatic-review window. Its launch was recorded
and checked frame by frame on the same device, and touch passthrough was checked
while the window was active. Manually opened reviews retain their normal display.

## Install

1. Install the APK and enable **PhoSet** in LSPosed.
2. Scope it only to **Google Photos**; this is also the declared recommended
   scope.
3. Force-stop and reopen Google Photos.

## Build and test

Requirements are JDK 17 or newer and Android SDK platform 35.

```powershell
.\gradlew.bat test assembleRelease
```

The release APK is written to
`app/build/outputs/apk/release/app-release.apk` and is signed with the standard
local debug key for update compatibility with development builds.
