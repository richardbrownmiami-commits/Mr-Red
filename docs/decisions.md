# Decisions

## D001 — 32-bit ARM first
**Decision:** Target `armeabi-v7a` first.

**Reason:** The target device is a 32-bit Android environment, so a working ARMv7 path is required before optimizing other ABIs.

## D002 — CPU first
**Decision:** Do not depend on GPU acceleration for the first milestone.

**Reason:** CPU inference is the simplest baseline and avoids making the initial build dependent on a specific mobile GPU backend.

## D003 — Small model first
**Decision:** Start with a small GGUF model rather than a 7B-class model.

**Reason:** The project has a strict under-2-GB runtime target and limited 32-bit address space. Model size, context and buffers must be controlled.

## D004 — Model outside APK
**Decision:** Keep the initial model outside the APK package.

**Reason:** APK size and runtime RAM are different constraints. Separating the model makes testing and model replacement easier.

## D005 — Evidence-based completion
**Decision:** Code written is not automatically considered complete.

**Reason:** Compilation, installation, inference, RAM usage and stability are separate verification stages.
