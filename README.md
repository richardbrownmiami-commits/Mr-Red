# Mr-Red — Offline AI & Device Automation Agent for Android (ARMv7 32-bit)

Mr-Red is an offline Android LLM lab and device automation agent specifically ported and optimized for **32-bit ARMv7 (`armeabi-v7a`)** devices running **Android 11 (API 30+)** such as the Redmi 9.

## Architecture

- **In-App LLM Runtime**: Direct JNI bridge (`native_llm`) to pinned `llama.cpp` with ARMv7 NEON hardware acceleration (`-mfpu=neon-vfpv4 -mfloat-abi=softfp`).
- **32-Bit Address Space Hardening**:
  - Conservative KV cache: default `n_ctx = 512` (options 256, 512, 1024, 2048).
  - Scratch memory caps: `n_batch = 64`, `n_ubatch = 32`, `no_perf = true`.
  - Memory-mapped GGUF loading (`use_mmap = true`, `use_mlock = false`).
- **Tool Calling & Device Automation**:
  - `open_settings`: Quick access to WiFi, Bluetooth, Battery, Display, and System settings.
  - `open_app`: Fuzzy launcher for installed packages (WhatsApp, YouTube, Chrome, Maps, Camera, etc.).
  - `send_message`: WhatsApp direct link draft & SMS composer.
  - `make_call`: Phone dialer launcher.
  - `set_timer`: Countdown timer launcher.
  - `device_vitals`: Real-time battery %, available storage, and free/total RAM.
  - `get_weather`: Open-Meteo weather forecasts.
  - `web_search`: Tavily API search with DuckDuckGo fallback.

## Build Requirements

- JDK 17
- Android SDK (API 35, minSdk 30)
- Android NDK 26.3.11579264
- CMake 3.22.1

Run `./gradlew assembleRelease` to generate `app/build/outputs/apk/release/Mr-Red-release-armeabi-v7a.apk`.
