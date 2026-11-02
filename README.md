# Suboverlay

Native Android floating subtitle overlay. Initial scaffold targets Android 8.0+ and includes Android 14-compatible special-use foreground-service declarations.

## Current MVP foundation
- Select an external SRT subtitle file through Android's document picker.
- Parse SRT timestamps and subtitle text.
- Display subtitle text in a system overlay near the bottom of the screen.
- Adjust the subtitle timeline manually by -5s / +5s.
- Keep the service active with a foreground notification.

## Permissions
The user must grant Display over other apps (SYSTEM_ALERT_WINDOW). Android 13+ may also request notification permission.

## Build
Open in Android Studio, sync Gradle, and run the app on a device.
Requirements: JDK 17, Android SDK 35, Android Gradle Plugin 8.7.3.

## Known limitations
- Timeline control is manual; these buttons do not seek the video in the browser.
- Automatic sync depends on whether the browser exposes playback position through an Android media session. This is not yet implemented or verified for UC Browser.
- Drag positioning, style controls, pause/resume clock, and MediaSession integration are follow-up milestones.
- This scaffold has not yet been built or device-tested.

## Next milestones
1. Build and resolve compile/runtime issues in Android Studio.
2. Add drag positioning and subtitle appearance settings.
3. Investigate MediaSession access and optional notification-listener permission.
4. Test on Android 14 with the target browser and refine synchronization.
