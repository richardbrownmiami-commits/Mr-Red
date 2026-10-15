# Memory Design

This file documents the app's runtime memory system. It is not the same thing as GitHub project documentation.

## Runtime memory layers
1. Conversation memory: recent messages needed for the current response.
2. Persistent app memory: selected user-approved information stored locally (for example in Room/SQLite).
3. Model context: only the relevant memory and conversation are inserted into the prompt sent to the model.

## Constraints
- Local/offline by default.
- Do not send memory to a network service in the offline build.
- Keep only the amount of history that fits the RAM and context budget.
- Older history may be summarized or compressed rather than inserted in full.
- The user must be able to inspect/delete stored app memory.

## RAM policy
Memory storage on disk is not the same as RAM usage. RAM is affected by the loaded model, KV cache, prompt/context, inference buffers, Android process overhead and native allocations.

The implementation must measure peak process memory on the target device before declaring the under-2-GB goal achieved.

## Initial milestone
Do not build a large autonomous memory system first. Start with reliable local chat history and a small explicit memory store, then add retrieval/summarization only after the base inference path is stable.
