# OpenDroid + RWKV.cpp ARMv7 integration

## Integration branch

This branch is the integration work area. The destination remains
`richardbrownmiami-commits/android`; OpenDroid is the source for agent capabilities.
OpenDroid is licensed under Apache-2.0; preserve its LICENSE and required notices
when source files are ported.

## Device/build contract

- Runtime target: Android 11 / API 30 (`targetSdk = 30`).
- Minimum runtime: API 23, retained from the destination app.
- Native ABI: `armeabi-v7a` only.
- Native engine: existing RWKV.cpp model engine; do not add LiteRT-LM because the
  ARMv7 runtime is not a supported target for the OpenDroid LiteRT-LM provider.
- Compile SDK may be newer than API 30; it controls compile-time symbols, not the
  OS version the app targets.
- Keep `GGML_NATIVE=OFF`, OpenMP disabled, and generic ARMv7/NEON flags. Never
  enable host-native or ARM64-only optimizations in this build.

## Porting order

1. **Shared local inference:** use `RwkvNativeEngine` as the stable Kotlin facade.
   The existing screen and agent must share one native model/context; do not create
   separate native contexts on this low-memory 32-bit target.
2. **Provider contract:** adapt OpenDroid's `LLMProvider` contract to the facade.
   Keep requests bounded to the native engine's current 96-token generation cap,
   and return a clear "model not loaded" error rather than crashing.
3. **Agent orchestration:** port planner/tool routing and tool schemas, preserving
   per-tool permission checks and explicit confirmation for destructive actions.
4. **Accessibility:** port the accessibility service and UI-tree actions behind
   an Android system accessibility-service opt-in. Never silently enable it.
5. **Remote providers and voice:** port provider implementations individually, with
   secrets in Android Keystore-backed storage and runtime permission checks.
6. **Validation:** run unit tests, assembleDebug/assembleRelease, inspect APK ABI,
   install on API 30 ARMv7 hardware/emulator, load a small RWKV .bin, and exercise chat,
   cancellation, planner/tool routing, and accessibility opt-in.

## Status and acceptance criteria

- [x] Create isolated integration branch.
- [x] Set target SDK to API 30 while retaining compile SDK 35 and ARMv7 ABI.
- [x] Add a shared JNI facade over the existing RWKV.cpp singleton.
- [ ] Port OpenDroid planner, tools, provider settings, accessibility service, and voice.
- [ ] Build and run APK on an API 30 ARMv7 device.
- [ ] Confirm release APK contains `lib/armeabi-v7a/*.so` only.

No APK is considered release-ready until the final two validation steps pass.
