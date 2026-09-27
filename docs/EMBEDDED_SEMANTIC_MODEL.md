# Embedded semantic model

The normal APK bundles the pinned Hugging Face model `blobbybob/potion-mxbai-micro`.

Source: https://huggingface.co/blobbybob/potion-mxbai-micro
Pinned revision: `d93b2e520cc717b5c21342c7194360ff89c76e95`
License: Apache-2.0

The model is a Model2Vec static sentence embedder. Hugging Face lists the model at about 0.7 MB and 256 dimensions. The CI workflow downloads the pinned `model.safetensors`, `tokenizer.json`, and `config.json` into `app/src/main/assets/embedded_semantic_model` before the APK is built, so the files are packaged inside the APK rather than downloaded on first launch.

This model is an embedding component only. It is not a conversational LLM and does not replace the custom ARMv7a neural network.

The cognitive architecture uses embeddings for retrieval/context selection; NARS, AtomSpace/OpenCog, and ONA remain reasoning/knowledge/agent subsystems rather than chat personalities.
