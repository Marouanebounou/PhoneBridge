# Build PhoneBridge for Android

The Android project needs Android Studio with JDK 17, Android SDK Platform 35, and Android Build Tools 35.0.0.

1. Open this `android` folder in Android Studio.
2. Install the requested SDK packages when Android Studio prompts.
3. Wait for Gradle sync, then select **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. Android Studio writes the debug APK to `app/build/outputs/apk/debug/app-debug.apk`.
5. Copy that APK to the Android phone and install it. Android may ask to allow installation from the file manager or browser used to open it.

The APK is a local personal-use build; it is not signed for Play Store publication.

