WhatsApp Feature Guard

WhatsApp Feature Guard is an Android app designed to restrict access to WhatsApp Status and Channels using Android Accessibility services.

It is intended for managed or supervised phones where access to these WhatsApp features should be limited.

Available Versions

The project builds three APK variants:

- Channels Only — blocks WhatsApp Channels.
- Status + Channels — blocks both WhatsApp Status and Channels.
- Custom Testing — a testing version for diagnosing and refining blocking behavior.

All variants use the same application ID. Install only one variant at a time.

How It Works

Depending on the installed version and its configuration, the app can:

- Block access to Status and/or Channels.
- Display an overlay on the WhatsApp Updates tab indicating which features are hidden.
- Prevent interaction with WhatsApp behind the overlay.
- Navigate back to WhatsApp Chats when a blocked screen is detected.
- Apply a 10-second lockout after detecting access to a blocked screen.
- Display a notification while the lockout is active.
- Record diagnostic events to help investigate blocking issues.

The app uses Accessibility services to identify relevant WhatsApp screens. Changes to WhatsApp's interface may affect detection, so diagnostics are useful when behavior changes.

Installation and Setup

1. Install the APK for the desired variant.
2. Open WhatsApp Feature Guard.
3. Follow the instructions to enable its Accessibility service.
4. If Android blocks the Accessibility permission, open the app's Android settings, tap the three-dot menu, and select Allow restricted settings, if that option is available.
5. Return to WhatsApp Feature Guard and enable the required settings.
6. Test the blocking behavior in WhatsApp.

Background Operation

For reliable operation, allow the app to run in the background.

On Samsung devices, check:

Settings → Apps → WhatsApp Feature Guard → Battery → Unrestricted

The exact menu names may vary by Android version and device manufacturer.

Android may still restrict background activity under certain conditions. Keeping the app unrestricted can improve reliability.

Diagnostics and Logging

The app includes diagnostic logging to help troubleshoot accessibility detection and blocking behavior.

Basic Log

Records concise diagnostic events, such as relevant screen detections, blocking actions, and errors.

Full Log

Includes the basic diagnostic information plus additional Accessibility UI-tree snapshots to help investigate why a screen was or was not detected.

Full Log can contain more technical information and may produce larger log files.

Privacy

The logger is designed to avoid recording personal WhatsApp conversations or other private content.

- Message text and captions are not intentionally recorded.
- Contact names and channel names are not intentionally recorded.
- Notification content, descriptions, and hints are omitted from UI-tree text logging.
- Generic interface labels may be recorded when they exactly match an approved list, such as "Updates", "Channels", "Explore", "Chats", "Status", and "Search".
- Technical diagnostic information, such as view IDs, screen structure, event types, and timestamps, may be recorded.
- Log files are stored locally by the app and are not automatically uploaded to a server.

Important: These privacy protections depend on the implementation in the installed build. Review the current logger code before relying on allowlisted UI-label logging; the repository's logger must be updated to implement that behavior.

Sharing Logs

When reporting a problem, use the app's log controls to collect the relevant diagnostic file. Review the file before sharing it, particularly if it contains technical details about the device or app configuration.

Building the APKs

The project uses Gradle and GitHub Actions to build the Android APKs.

The workflow builds these variants:

- "channelsOnlyDebug"
- "statusAndChannelsDebug"
- "customTestingDebug"

The APK artifacts are produced under:

- "app/build/outputs/apk/channelsOnly/debug/"
- "app/build/outputs/apk/statusAndChannels/debug/"
- "app/build/outputs/apk/customTesting/debug/"

To build locally with Gradle, the corresponding tasks are:

./gradlew assembleChannelsOnlyDebug assembleStatusAndChannelsDebug assembleCustomTestingDebug

The GitHub Actions workflow can also be run through GitHub's Actions tab when manual workflow execution is enabled.

Project Structure

Important source files include:

- "MainActivity.kt" — app screen, setup guidance, and logging controls.
- "WhatsAppAccessibilityService.kt" — accessibility monitoring, screen detection, overlays, and blocking behavior.
- "DiagnosticsLogger.kt" — diagnostic logging and privacy filtering.
- "AndroidManifest.xml" — app components, permissions, and Accessibility service declaration.
- "app/build.gradle.kts" — Android configuration, version information, and build variants.

Version Information

- Version name: "0.7.0"
- Version code: "7"
- Minimum Android SDK: 26
- Target Android SDK: 35

The version information above reflects the current project configuration and should be updated whenever the app version changes.

Limitations

- The app depends on Android Accessibility services being enabled.
- WhatsApp updates can change screen layouts, labels, and view IDs, which may require detection updates.
- Android battery and background restrictions can affect reliability.
- This app restricts access within WhatsApp; it is not a device-wide web filter or a replacement for a complete Mobile Device Management solution.
- Blocking behavior should be tested on the WhatsApp version and Android devices where the app will be deployed.

License

Check the repository's license file for the terms under which this project may be used or modified.
