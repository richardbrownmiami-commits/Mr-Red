package com.redmi9.offlineai

data class ReasoningResult(
    val matched: Boolean,
    val directAnswer: String?,
    val topicSummary: String?,
    val relatedFacts: List<String>,
    val candidateOptions: List<String>,
    val confidence: Double,
    val source: String
) {
    fun formattedResponse(): String = buildString {
        if (!directAnswer.isNullOrBlank()) {
            append(directAnswer)
        } else if (!topicSummary.isNullOrBlank()) {
            append(topicSummary)
        } else {
            append("I don't have this in my local knowledge store yet.")
        }

        if (relatedFacts.isNotEmpty()) {
            append("\n\nRelated local facts:\n")
            relatedFacts.take(4).forEach { fact ->
                append("• ").append(fact).append("\n")
            }
        }

        if (candidateOptions.isNotEmpty()) {
            append("\n\nOptions to explore or confirm:\n")
            candidateOptions.forEachIndexed { idx, opt ->
                append("${idx + 1}. ").append(opt).append("\n")
            }
        }
    }.trimEnd()
}

/**
 * Offline reasoning engine that searches local confirmed Q&A,
 * cached Wikipedia/DuckDuckGo topic summaries, and relational triples
 * without sending data off-device or requiring active internet.
 */
class OfflineReasoningEngine(private val store: KnowledgeStore) {

    fun evaluate(query: String): ReasoningResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return ReasoningResult(
                matched = false,
                directAnswer = "Please ask a question or specify a topic.",
                topicSummary = null,
                relatedFacts = emptyList(),
                candidateOptions = emptyList(),
                confidence = 0.0,
                source = "none"
            )
        }

        // 1. Check confirmed Q&A table
        val confirmedList = store.findConfirmedQa(cleanQuery)
        val bestQa = confirmedList.firstOrNull()
        if (bestQa != null && bestQa.confidence >= 0.8) {
            val triples = store.findTriples(bestQa.topic).map { "${it.subject} ${it.predicate} ${it.objectVal}" }
            return ReasoningResult(
                matched = true,
                directAnswer = bestQa.answer,
                topicSummary = null,
                relatedFacts = triples,
                candidateOptions = listOf(
                    "Tell me more about ${bestQa.topic}",
                    "Update or refine this answer",
                    "Ask a different question"
                ),
                confidence = bestQa.confidence,
                source = "confirmed_qa"
            )
        }

        // 2. Check cached topic summaries
        val topics = store.findTopicEntries(cleanQuery)
        val topTopic = topics.firstOrNull()
        if (topTopic != null) {
            val triples = store.findTriples(topTopic.topic).map { "${it.subject} ${it.predicate} ${it.objectVal}" }
            val options = mutableListOf<String>()
            options.add("Save this summary as confirmed answer")
            options.add("Explore key facts for ${topTopic.title}")
            options.add("Mark for online update when connected")

            return ReasoningResult(
                matched = true,
                directAnswer = null,
                topicSummary = "[Cached Topic: ${topTopic.title}]\n${topTopic.summary}",
                relatedFacts = triples,
                candidateOptions = options,
                confidence = 0.75,
                source = "topic_cache (${topTopic.source})"
            )
        }

        // 3. Check relational knowledge graph directly
        val graphTriples = store.findTriples(cleanQuery)
        if (graphTriples.isNotEmpty()) {
            val factStrings = graphTriples.map { "${it.subject} ${it.predicate} ${it.objectVal}" }
            return ReasoningResult(
                matched = true,
                directAnswer = "Found related facts in local knowledge graph for '$cleanQuery':",
                topicSummary = null,
                relatedFacts = factStrings,
                candidateOptions = listOf(
                    "Clarify relationship",
                    "Add new fact",
                    "Search online when connected"
                ),
                confidence = 0.65,
                source = "knowledge_graph"
            )
        }

        // 4. Fuzzy typo / misspelling fallback check
        val fuzzyQa = store.findFuzzyQaEntries(cleanQuery).firstOrNull()
        if (fuzzyQa != null) {
            val qa = fuzzyQa.entry
            val triples = store.findTriples(qa.topic).map { "${it.subject} ${it.predicate} ${it.objectVal}" }
            return ReasoningResult(
                matched = true,
                directAnswer = "Did you mean '${fuzzyQa.matchedTerm}'? (matched '${qa.question}')\n\n${qa.answer}",
                topicSummary = null,
                relatedFacts = triples,
                candidateOptions = listOf(
                    "Confirm answer for '${cleanQuery}'",
                    "Teach a different answer",
                    "Research online when connected"
                ),
                confidence = 0.70,
                source = "confirmed_qa (fuzzy match: '${fuzzyQa.originalTerm}' -> '${fuzzyQa.matchedTerm}')"
            )
        }

        val fuzzyTopic = store.findFuzzyTopicEntries(cleanQuery).firstOrNull()
        if (fuzzyTopic != null) {
            val topTopic = fuzzyTopic.entry
            val triples = store.findTriples(topTopic.topic).map { "${it.subject} ${it.predicate} ${it.objectVal}" }
            return ReasoningResult(
                matched = true,
                directAnswer = "Did you mean '${fuzzyTopic.matchedTerm}'? (showing cached topic '${topTopic.title}')",
                topicSummary = topTopic.summary,
                relatedFacts = triples,
                candidateOptions = listOf(
                    "Save summary as confirmed answer",
                    "Teach a different answer",
                    "Research online when connected"
                ),
                confidence = 0.65,
                source = "topic_cache (fuzzy match: '${fuzzyTopic.originalTerm}' -> '${fuzzyTopic.matchedTerm}')"
            )
        }

        // 5. Unmatched query -> triage
        store.logTriage(cleanQuery, "needs_online_fetch", "No local match found offline.")
        return ReasoningResult(
            matched = false,
            directAnswer = "No offline match found for '$cleanQuery'. Saved to triage queue.",
            topicSummary = null,
            relatedFacts = emptyList(),
            candidateOptions = listOf(
                "Fetch topic info from Wikipedia / DuckDuckGo when online",
                "Teach Mr-Red the answer directly",
                "Rephrase query with simpler keywords"
            ),
            confidence = 0.0,
            source = "triage_queue"
        )
    }

    fun learnConfirmedAnswer(question: String, answer: String, topic: String): Long {
        return store.saveConfirmedQa(question, answer, topic, 1.0)
    }

    /**
     * Compact offline knowledge snippet for injecting into system prompt.
     */
    fun relevantKnowledgeContext(query: String, result: ReasoningResult = evaluate(query)): String {
        if (!result.matched) return ""

        return buildString {
            append("[Local Knowledge Base (Offline)]\n")
            if (!result.directAnswer.isNullOrBlank()) {
                append("Verified Answer: ").append(result.directAnswer).append("\n")
            }
            if (!result.topicSummary.isNullOrBlank()) {
                append(result.topicSummary).append("\n")
            }
            if (result.relatedFacts.isNotEmpty()) {
                append("Known Facts: ").append(result.relatedFacts.take(3).joinToString("; ")).append("\n")
            }
        }.trimEnd()
    }
}
