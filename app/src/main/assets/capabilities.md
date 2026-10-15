# Tool Capabilities

Each capability is declared here so the orchestrator can expose only tools that are actually available.

- web_search: Search the internet for current information. Requires network permission/connectivity.
- web_fetch: Fetch and read a web page or supported URL. Requires network permission/connectivity.
- calculator: Perform deterministic mathematical calculations locally.
- open_app: Request an Android app/action through the Android intent system. The APK validates the requested action before launching it.
- memory_search: Retrieve relevant locally stored long-term memory.
- conversation_history: Retrieve relevant local conversation history.
- model_info: Read current model and inference runtime information.
