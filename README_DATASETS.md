# Unified AIBot dataset

The app now ships with one built-in baseline corpus:

app/src/main/res/raw/core_assistant.jsonl

It is intentionally a single mixed corpus rather than a collection of unrelated datasets. It covers persona, conversation, English communication, identity, common knowledge, reasoning examples, memory behavior, and Android-agent behavior.

## Automatic training

On first startup AIBot copies the corpus into its private dataset directory and trains it automatically. A progress bar shows the training state.

You do not need to teach the assistant basic conversation before using it.

## Optional external data

The Dataset Loader still accepts .jsonl, .json, .csv, and .txt.

Hugging Face files can be downloaded directly with the built-in Hub commands:

- !hf dataset <query>
- !hf files dataset <org/name>
- !hf download dataset <org/name> <file.jsonl>

After download, the file appears in the dataset list and can be trained with !load <filename>.

External data is optional. The built-in baseline is the default starting point.
