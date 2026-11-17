# Mr-Red — Minimal Offline GGUF Inference for Android

Mr-Red is an Android app for loading a local GGUF model and running direct, on-device inference on 32-bit ARMv7 (`armeabi-v7a`) devices.

## Inference flow

- Import and load a GGUF model through the Android document picker.
- Create a llama.cpp inference context and configure CPU threads.
- Send only the current user question to the model.
- Stream the model's current answer into the chat UI.
- Use the chat template embedded in the GGUF model when available; fall back to ChatML only when the model has no template.

Each request is stateless at the application layer: no conversation history, saved memory, rolling summary, persona card, custom system prompt, context reconstruction, or model-driven tool loop is added to the model input. The native runtime, tokenizer, model loading, context allocation, sampling configuration, stop handling, and streaming JNI bridge remain in place.

## Android runtime

- JNI bridge (`native_llm`) to the pinned `llama.cpp` runtime.
- ARMv7 NEON acceleration (`-mfpu=neon-vfpv4 -mfloat-abi=softfp`).
- Conservative default context size for 32-bit address-space constraints.
- Memory-mapped GGUF loading (`use_mmap = true`, `use_mlock = false`).
- Runtime controls for context size, CPU threads, and sampling parameters.
- Separate Android alarm/reminder scheduling screens remain part of the app.

## Build

- JDK 17
- Android SDK
- Android NDK
- CMake

Run `./gradlew assembleRelease` to build the release APK.
