# Planned

This is the forward-looking task list. Items belong here until they are actually implemented and verified.

## Phase 1 — Build foundation
- [x] Android project skeleton
- [x] ARMv7 (`armeabi-v7a`) build configuration
- [x] llama.cpp source integration for the native build
- [x] GitHub Actions workflow definition
- [ ] Successful GitHub Actions APK build
- [ ] Verify APK contains only `armeabi-v7a`

## Phase 2 — Runtime smoke test
- [ ] Launch APK on a real Redmi 9
- [ ] Load a small GGUF model
- [ ] Generate a response offline
- [ ] Measure peak RAM
- [ ] Measure generation speed
- [ ] Record crashes/errors

## Phase 3 — Product basics
- [ ] Chat UI
- [ ] Model selection/loading
- [ ] Local conversation history
- [ ] Basic settings
- [ ] Runtime memory controls

## Phase 4 — Memory and advanced features
- [ ] Persistent local memory
- [ ] Memory retrieval
- [ ] Conversation summarization
- [ ] Self-check/reasoning experiments
- [ ] Performance tuning

## Rule
Do not move an item to `complete.md` just because code exists. It must be tested at the level stated in the completion record.
