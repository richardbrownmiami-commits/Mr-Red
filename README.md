# Mr-Red — Minimal GGUF Runtime & Android Device Utilities

Mr-Red is an offline Android application for loading local GGUF models and running inference on 32-bit ARMv7 (`armeabi-v7a`) devices running Android 11 (API 30+).

## GGUF inference path

The chat inference path is deliberately single-turn:

1. Import or restore one GGUF model.
2. Load the model and allocate its llama.cpp context.
3. Reset the native KV cache for a fresh inference context.
4. Send only the current user question through the model's native GGUF chat template.
5. Stream the model's current answer back to the chat UI.

The GGUF request does not include prior chat turns, persisted conversation history, rolling summaries, saved personal memory, a character/persona card, app-authored system prompts, model-generated tool calls, reconstructed context, or KV state from previous requests. Chat display is limited to the active turn and is not persisted. The model's own GGUF chat template remains enabled because it is part of the model's inference format.

Sampling configuration, context size, CPU thread settings, tokenization, native model loading, inference, cancellation, and runtime diagnostics remain configurable.

## Independent Android utilities

Phone actions are handled locally by Kotlin and are separate from GGUF inference. Existing device utilities include alarms, reminders, notifications, app/settings launch, calls, timers, device vitals, weather, and web search. These local utilities do not append instructions or tool results to a model prompt.

## Native runtime

- Direct JNI bridge (`native_llm`) to pinned `llama.cpp`.
- ARMv7 NEON hardware acceleration (`-mfpu=neon-vfpv4 -mfloat-abi=softfp`).
- Memory-mapped GGUF loading (`use_mmap = true`, `use_mlock = false`).
- Conservative context and batch settings for 32-bit address-space constraints.
- Release APK packaging restricted to `armeabi-v7a`.

## Build

The repository's GitHub Actions workflow installs JDK 17, Android SDK/NDK, and CMake, runs unit tests, builds the release APK, and verifies that only ARMv7 native libraries are packaged.

Locally, run:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```
