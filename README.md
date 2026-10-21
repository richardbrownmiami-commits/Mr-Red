# Redmi 9 Offline AI

Experimental 32-bit Android offline AI build targeting `armeabi-v7a`.

## Current target
- Android APK
- 32-bit ARM (`armeabi-v7a`) only
- CPU inference first
- llama.cpp integration
- GGUF model kept outside the APK; model import supports files up to 2 GB
- Model weights are memory-mapped to reduce eager RAM use
- RAM/context settings default conservatively for 32-bit devices
- GitHub Actions build + APK artifact

## Important limits
- Importing a 1.5 GB GGUF is supported, but loading it is not guaranteed on Redmi 9: Android 32-bit process address space, free RAM, model architecture, and context/KV cache determine whether it fits.
- Lower context (512 or 1024) may help memory use, but can reduce how much prompt/history fits. The app retrieves relevant local conversation/knowledge excerpts rather than placing the entire database in the model context.
- Memory-mapping does not make a model smaller or faster by itself. Actual speed and stability must be measured on the target phone; CPU inference can be slow.
- The first milestone is a reproducible 32-bit Android build. Device-level RAM and performance must be measured on the actual Redmi 9 before claiming the final target is met.
