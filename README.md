# WhatsApp Feature Guard

This repository builds two APK variants from the same codebase.

- **Channels Only**: blocks WhatsApp Channels and overlays the Channels area; Status remains accessible.
- **Status + Channels**: blocks both WhatsApp Status and Channels and overlays both areas.

Both variants intentionally use the same application ID, so only one can be installed at a time. Installing the other variant replaces/updates the first rather than allowing both to coexist.

## Build and install

GitHub Actions builds both debug APKs on each push to `main` or when manually started. Download them from the workflow run's artifacts or the GitHub Release.

After installing, open **WhatsApp Feature Guard** and tap **Set up / enable Accessibility**. In Android Accessibility settings, open the installed service and enable it. Return to the app and tap **Refresh status**.

## Background reliability and recovery

The setup screen reports:

- whether the accessibility service is enabled in Android settings;
- whether the service is currently connected to the system;
- whether Android's battery-optimization exemption is active;
- the latest service status and diagnostic log size.

Use **Request battery-optimization exemption** and approve the Android prompt. On Samsung devices, also open **Open app battery settings (choose Unrestricted)**, then select **Battery → Unrestricted** if that option is available. These settings reduce the chance of Android restricting background operation but cannot guarantee the service will never be stopped.

An ordinary standalone APK cannot silently turn its own accessibility service back on if Android or the user disables it. The user must return to Accessibility settings and re-enable it. The app therefore keeps a launcher icon so the setup screen remains easy to reopen for initial setup and recovery. Hiding the icon would make that recovery much harder without an MDM or a separate trusted launch mechanism.

## Version 0.5.0

This version adds the accessibility setup button, battery-exemption request, shortcut to app battery settings, and live diagnostic indicators. Existing Status/Channels blocking, overlays, redirects, cooldown, and notification behavior are intended to remain unchanged.


Version 0.5.0 adds a custom launcher icon, a more reliable Open WhatsApp button with package-visibility support, flexible Status viewer counter detection, alternative known IDs/labels for the Updates list and Channels heading, and a latest detection report in the setup screen and diagnostic log.
