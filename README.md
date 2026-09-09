# PhoSet

PhoSet combines three opt-in Google Photos behavior fixes in one LSPosed module.
Each feature has its own switch in a Material 3 Expressive settings screen.

## Features

- **Coordinate links** normalizes exact coordinate intents from Google Photos
  into interoperable `geo:` links while preserving a visible map pin.
- **Reconcile device changes** silently accepts recognized Google Photos
  out-of-sync `EDIT`, `TRASH`, `RESTORE`, `DELETE`, and `VAULT` changes through
  Photos' own reconciliation paths.
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
Photos class names. Trash confirmations are recognized using resource entry
names rather than obfuscated Java symbols. The reconciliation feature validates
its required runtime classes, signatures, category mappings, MediaStore URI
shapes, and item state before acting. Unknown or changed structures retain the
stock Google Photos behavior.

Development and trash-dialog analysis used Google Photos
`7.91.0.973540846`.

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
