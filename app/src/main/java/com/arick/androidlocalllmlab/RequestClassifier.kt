package com.arick.androidlocalllmlab

enum class RequestCategory(val label: String, val table: String?) {
    CHAT("A", null),
    DEVICE_LIVE("B", null),
    NOTIFICATIONS("C", "events"),
    SAVED_FACTS("D", "facts"),
    CONVERSATION("E", "conversation_turns")
}

data class RequestClassification(
    val category: RequestCategory,
    val probabilities: List<Float>
)

/** Pure, deterministic policy over the five candidate-token logits. */
internal object RequestClassifier {
    fun decide(logits: FloatArray): RequestClassification {
        val categories = RequestCategory.values()
        if (logits.size != categories.size || logits.any { !it.isFinite() }) {
            return RequestClassification(RequestCategory.CHAT, List(5) { 0f })
        }
        val maximum = logits.maxOrNull()
            ?: return RequestClassification(RequestCategory.CHAT, List(5) { 0f })
        val exponentials = logits.map { kotlin.math.exp((it - maximum).toDouble()) }
        val denominator = exponentials.sum()
        if (!denominator.isFinite() || denominator <= 0.0) {
            return RequestClassification(RequestCategory.CHAT, List(5) { 0f })
        }
        val probabilities = exponentials.map { (it / denominator).toFloat() }
        val strongestNonChat = (1 until probabilities.size).maxByOrNull { probabilities[it] }
            ?: return RequestClassification(RequestCategory.CHAT, probabilities)
        val selected = if (
            probabilities[strongestNonChat] >= 0.60f &&
            probabilities[strongestNonChat] - probabilities[0] >= 0.25f
        ) categories[strongestNonChat] else RequestCategory.CHAT
        return RequestClassification(selected, probabilities)
    }
}
