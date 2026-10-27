# Architecture

## Purpose
Define the software structure before adding features.

## Runtime architecture

Android UI
→ AI manager
→ JNI bridge
→ RWKV.cpp native engine
→ RWKV.cpp-format model
→ CPU (ARMv7 / armeabi-v7a)

## Main layers
- `app/`: Android application, UI, settings, chat, history and runtime memory.
- `app/src/main/cpp/`: JNI/native integration and native build configuration.
- `rwkv.cpp`: inference engine compiled as part of the native build.
- Model: RWKV.cpp-format model kept outside the APK during the initial milestone.
- `.github/workflows/`: reproducible CI build and test process.
- `docs/`: project architecture, plans, changes, completion and testing records.

## Hardware target
- 32-bit Android ARM (`armeabi-v7a`).
- Redmi 9 target device.
- CPU-first; no GPU dependency for the first milestone.

## Memory principle
The under-2-GB target applies to runtime process memory, not APK file size. Context size, model size, KV cache, temporary buffers and Android overhead must all be measured.

## Milestone rule
A build succeeding in GitHub Actions proves compilation/package success only. It does not prove acceptable RAM, speed or stability on the physical Redmi 9.


## Native dependency policy
- Build RWKV.cpp CPU inference only for `armeabi-v7a`.
- Disable GPU backends, OpenMP, BLAS, tests, examples, and server targets.
- The Android JNI bridge currently loads RWKV-format models and reports model/runtime information.
- Chat tokenization and generation are not yet complete; do not treat the engine as a working chat build until tokenizer integration and device testing pass.
