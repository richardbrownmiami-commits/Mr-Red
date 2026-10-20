# Changes

This is the engineering change log. Record what was actually changed, when it was changed, and why.

## 2026-09-12
- Added the project documentation structure.
- Added architecture definition for Android → JNI → llama.cpp → GGUF → ARMv7 CPU.
- Added runtime memory design and the under-2-GB measurement rule.
- Added phased implementation plan.
- Added completion and testing criteria.
- Added decision records explaining major design choices.

## Change rule
Every meaningful code, build-system or architecture change should be recorded here. A change entry is not proof that the change works; verification belongs in `testing.md` and `complete.md`.

## 2026-10-20
- Added `KnowledgeStore.kt`: local SQLite storage for cached topic summaries (`topic_cache`), confirmed Q&A (`confirmed_qa`), relational triples (`knowledge_graph`), and triage queue (`triage_log`).
- Added `TopicResearchClient.kt`: online research fetcher using DuckDuckGo Instant Answer API and Wikipedia REST summary API without third-party dependencies, populating local SQLite cache.
- Added `OfflineReasoningEngine.kt`: local question resolution, candidate option generation (up to 3 follow-ups/choices), relational knowledge graph exploration, and offline knowledge context injection.
- Updated `MainActivity.kt`: added "Topic Research & Knowledge" to the main menu, integrated offline knowledge fallback when no GGUF model is loaded, and injected local knowledge into chat prompt.
- Added `INTERNET` permission to `AndroidManifest.xml` for optional online topic research.
- Added architecture documentation in `docs/offline_knowledge.md`.
