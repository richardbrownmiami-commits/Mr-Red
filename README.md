# Redmi 9 Offline AI

Experimental 32-bit Android offline AI build targeting `armeabi-v7a`.

## Current target
- Android APK
- 32-bit ARM (`armeabi-v7a`) only
- CPU inference first
- llama.cpp integration
- Model kept outside the APK
- RAM target: under 2 GB for the app process
- GitHub Actions build + APK artifact

## Important
The first milestone is a reproducible 32-bit Android build. Device-level RAM and performance must be measured on the actual Redmi 9 before claiming the final target is met.
