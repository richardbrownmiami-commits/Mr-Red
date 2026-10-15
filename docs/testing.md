# Testing

Testing separates compilation success from real-device success.

## Levels
### Level 1 — Static/build checks
- Gradle configuration parses.
- CMake configuration succeeds.
- Native code compiles.
- APK packaging succeeds.
- APK ABI is `armeabi-v7a` only.

### Level 2 — Emulator/CI smoke checks
- APK installs where the test environment supports 32-bit ARM.
- Native library loads.
- JNI smoke test can call the native layer.

### Level 3 — Physical Redmi 9
- APK installs.
- App launches without crashing.
- Small GGUF model loads.
- Offline prompt produces a response.
- Peak RAM is measured.
- Generation speed is measured.
- Extended stability is tested.

## RAM measurement
The under-2-GB target is not considered achieved until peak runtime memory is measured during model loading and generation on the target device.

## Test record rule
Record date, build/commit, device variant, model, quantization, context size, peak RAM, speed and result. Failed tests stay recorded; they are not deleted just because the code changes later.
