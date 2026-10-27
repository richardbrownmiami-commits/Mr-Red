# Complete

Only verified work belongs here.

## Verified so far
- Android project structure has been created in the repository.
- The project declares a 32-bit ARM (`armeabi-v7a`) target.
- Native build configuration has been switched to RWKV.cpp on the migration branch.
- A GitHub Actions build workflow has been added.
- Documentation structure has been added.

## Not yet verified
- Successful GitHub Actions APK build, including RWKV.cpp.
- Successful installation on a physical Redmi 9.
- Offline RWKV model loading on the device.
- Actual peak RAM below 2 GB.
- Acceptable generation speed.
- Long-running stability.

## Completion rule
`complete.md` records evidence, not intentions. If a later change invalidates a result, remove or revise the affected completion entry rather than leaving a false claim.


## RWKV migration status (not yet complete)
- RWKV.cpp CPU-only CMake integration added on `replace-llama-with-rwkv-armv7`.
- Native JNI bridge now uses RWKV model loading and runtime metadata APIs.
- Chat generation intentionally reports that tokenizer integration is pending; no fabricated model replies are returned.
- No successful APK build or physical-device test has been verified yet.
