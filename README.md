# Mr-Red — Android Device Controls

Mr-Red is an Android application for direct device actions, notification capture, scheduled alarms and reminders, online information tools, and a private on-device activity database.

## Features

- Open Android settings pages and launch installed applications.
- Open the phone dialer and draft SMS messages.
- Set timers, alarms, one-time reminders, and recurring reminders.
- Capture notification posts and removals with Android Notification Listener access.
- Read device battery, storage, and memory status.
- Retrieve weather, news headlines, and web search results.
- Browse the local event database and review diagnostics in the activity screen.

## Architecture

The app uses Kotlin and Jetpack Compose. Device actions are executed directly by the Android tool executor. There is no embedded language model, llama.cpp runtime, JNI bridge, GGUF model loader, or native C++ build.

## Build

Requirements: JDK 17, Android SDK Platform 35, Android Build Tools 35.0.0, and Gradle 8.10.2.

Run `gradle :app:assembleRelease` to build the release APK.
