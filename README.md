# AIBot — ARMv7a Android 11 AI assistant

A self-contained Android AI assistant built around the existing Java neural network. The project is intentionally kept small enough for ARMv7a 32-bit devices and does not depend on GGUF/llama.cpp.

## Current architecture

- NeuralNetwork — custom 2-layer causal Transformer with learned embeddings, context association, bounded training, and ONNX export support.
- Tokenizer — persistent vocabulary with basic English surface-form normalization.
- CognitiveMemory — persistent conversation memory with local learned-space embeddings and similarity retrieval.
- AtomSpaceLite — Android-safe local graph memory inspired by AtomSpace concepts.
- NARSEngine / NarsTool — explicit reasoning tool. NARS is not injected into ordinary conversation.
- WebSearch / WebFetch — research path for information that the local brain cannot answer reliably.
- TaskParser / DeviceController — natural-language phone actions.
- OnnxEngine — optional ONNX model import, Hugging Face model download, embedding support, and generic causal-logit generation.
- HuggingFaceHub — public Hub search and file download without a separate SDK.
- SelfLearner — dataset training, conversation learning, memory indexing, and progress callbacks.

## One built-in baseline corpus

app/src/main/res/raw/core_assistant.jsonl is the built-in unified corpus. It contains greetings and normal conversation, identity and persona, English language behavior, common general knowledge, question/answer patterns, reasoning examples, uncertainty and source handling, memory behavior, device-agent behavior, and NARS/OpenCog/ONA concepts.

The app copies it into its dataset directory and automatically trains it once on first run. There is no "teach me first" startup requirement.

External datasets remain optional for further training.

## Training

The app shows a horizontal progress bar while a dataset is loaded, indexed, and trained. Training is done in the background.

Dataset samples are used to expand the vocabulary, train the local neural network, index useful exchanges in persistent memory, and add structured relations to AtomSpace-lite.

NARS is not trained on every ordinary sentence.

## Memory

Conversation exchanges are saved in the app's private files area. Relevant past exchanges are retrieved by similarity instead of dumping the whole history into every prompt.

Useful commands: !stats, !save, !reset.

## Reasoning

Use !nars <question> or "reason about <problem>".

NARS is an explicit tool. Ordinary greetings, statements, and normal questions do not automatically enter the reasoning engine.

The project also contains an Android-safe AtomSpace-style graph layer. The full OpenCog AtomSpace is a native C++/Guile system with Android porting constraints, so it is not incorrectly bundled as if it were a normal Android Java dependency. OpenNARS/ONA remain reference-compatible reasoning concepts; the APK uses the existing Java NARS engine for the on-device path.

## Hugging Face Hub

The app can search public Hugging Face models/datasets and download individual files:

- !hf model <query>
- !hf dataset <query>
- !hf files model <org/name>
- !hf files dataset <org/name>
- !hf download model <org/name> <file.onnx>
- !hf download dataset <org/name> <file.jsonl>

The menu also has a Hugging Face Hub search action.

## ONNX

The ONNX menu can enable/disable ONNX Runtime, show local .onnx models, download supported built-in models, import an ONNX model with the Android file picker, and load generic causal models that expose input_ids and vocabulary logits.

MiniLM is treated as an embedding model, not as a chat generator. Its WordPiece vocabulary is downloaded alongside the ONNX file.

The Java neural network can be exported with export_to_onnx.py. The exporter now mirrors the actual Java attention/FF/layer-norm implementation instead of substituting a different PyTorch Transformer.

## Build target

- Android 11+ (minSdk 30)
- compile/target SDK 34
- ARMv7a 32-bit only (armeabi-v7a)
- JDK 17
- AGP 8.2.2
- Gradle 8.2.1
- NDK 25.2.9519653
- normal non-debuggable APK only

ONNX Runtime officially supports Android ARM32v7; model size and runtime cost still depend on the selected ONNX model.


## Neural diagnostics
Open the three-dot menu and choose **Neural Trace** after sending a message. It shows whether the custom neural network actually ran, prompt/context token counts, generated token count, timing, and why a fallback was used.
