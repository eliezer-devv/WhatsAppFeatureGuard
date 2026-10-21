# WhatsApp Feature Guard

This repository builds two APK variants from one codebase using GitHub Actions.

## APK variants

- **Channels Only** (`app-channelsOnly-debug.apk`): blocks Channels and displays the Channels overlay. Status remains accessible.
- **Status + Channels** (`app-statusAndChannels-debug.apk`): blocks both Status and Channels and displays both overlays.

Both variants hide the normal launcher icon. This does not start or enable the Accessibility Service automatically. After installing, enable **WhatsApp Feature Guard** under Android Settings → Accessibility (the exact menu name may vary).

## Important installation note

Both variants intentionally use the same Android application ID so either variant can update the same installed app. You cannot install both variants side-by-side on one phone; installing one over the other replaces the installed variant (assuming the APK signing key matches).

## Builds

Every push to `main`, and manual workflow runs, build both APKs. Download them from the workflow run's **Artifacts**, or from the latest GitHub Release.

The app's Status/Channels mode is fixed by the APK variant. The MainActivity remains in the package for troubleshooting, but it has no launcher icon. Existing blocking logic, WhatsApp overlays, 10-second cooldown, redirect behavior, and diagnostic logging are retained.
