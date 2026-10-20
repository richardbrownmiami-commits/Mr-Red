# Offline Knowledge & Relational Reasoning

## Architecture & Data Flow

1. **Online Research (When Network Available)**:
   - Queries DuckDuckGo Instant Answer API (`https://api.duckduckgo.com/?q={query}&format=json&no_html=1`).
   - Queries Wikipedia Summary REST API (`https://en.wikipedia.org/api/rest_v1/page/summary/{title}`).
   - Extracts summaries, disambiguation snippets, and subject-predicate-object knowledge triples.
   - Saves clean structured data into local SQLite `topic_cache` and `knowledge_graph`.

2. **Offline Reasoning (Zero Network Required)**:
   - Queries `confirmed_qa` for high-confidence local answers.
   - Searches `topic_cache` across cached topics and summaries using keyword scoring.
   - Explores relational facts in `knowledge_graph` (subject -> predicate -> object).
   - Generates candidate answers and up to 3 options for the user to confirm.
   - Unresolved questions are logged to `triage_log` for future clarification or online research.