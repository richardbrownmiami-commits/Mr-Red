package com.aibot;

import java.util.Locale;

/**
 * Decides which cognitive resources are relevant to a turn.
 * It never writes the visible answer; it only selects context/tools.
 */
public class ToolRouter {
    public static final class Decision {
        public final boolean memory;
        public final boolean semantic;
        public final boolean reasoning;
        public final boolean web;
        public final boolean device;
        public final String intent;
        public final float confidence;

        Decision(boolean memory, boolean semantic, boolean reasoning,
                 boolean web, boolean device, String intent, float confidence) {
            this.memory = memory;
            this.semantic = semantic;
            this.reasoning = reasoning;
            this.web = web;
            this.device = device;
            this.intent = intent;
            this.confidence = confidence;
        }

        public String summary() {
            return "intent=" + intent +
                " confidence=" + String.format(Locale.US, "%.2f", confidence) +
                " memory=" + memory +
                " semantic=" + semantic +
                " reasoning=" + reasoning +
                " web=" + web +
                " device=" + device;
        }
    }

    private final OnnxEngine onnx;

    public ToolRouter(OnnxEngine onnx) {
        this.onnx = onnx;
    }

    public Decision decide(String input) {
        String q = input == null ? "" : input.trim();
        String s = q.toLowerCase(Locale.US);

        if (TaskParser.isDeviceCommand(q)) {
            return new Decision(false, false, false, false, true, "device_action", 0.99f);
        }

        boolean memory = containsAny(s,
            "remember", "do you remember", "what did i tell you",
            "what did i say", "yesterday", "last time", "my name",
            "my project", "you told me", "i told you");

        boolean reasoning = containsAny(s,
            "deduce", "infer", "prove", "logical", "logic",
            "implication", "if and only if", "therefore",
            "what follows from", "reason about", "reason this",
            "derive a conclusion", "is it logically");

        boolean web = containsAny(s,
            "latest", "today", "tonight", "now", "currently", "current",
            "recent", "recently", "this week", "this month", "news",
            "weather", "stock price", "price today", "live score",
            "breaking", "update on", "what happened today", "who won today");

        boolean semantic = onnx != null && onnx.isSemanticModelLoaded();

        // Semantic retrieval is useful for ordinary knowledge and memory
        // matching, but it does not force a web search or a reasoning tool.
        if (semantic && (memory || isKnowledgeLike(s))) {
            return new Decision(memory, true, reasoning, web, false,
                web ? "current_knowledge" : (memory ? "memory" : "knowledge"), 0.92f);
        }

        if (web) {
            return new Decision(memory, semantic, reasoning, true, false,
                "current_knowledge", 0.96f);
        }

        if (reasoning) {
            return new Decision(memory, semantic, true, false, false,
                "reasoning", 0.90f);
        }

        if (memory) {
            return new Decision(true, semantic, false, false, false,
                "memory", 0.90f);
        }

        if (isKnowledgeLike(s)) {
            return new Decision(false, semantic, false, false, false,
                "knowledge", 0.78f);
        }

        return new Decision(false, semantic, false, false, false,
            "conversation", 0.86f);
    }

    private boolean isKnowledgeLike(String s) {
        return s.contains("?") ||
            containsAny(s, "what is", "what are", "who is", "who are",
                "where is", "when was", "why does", "why is", "how does",
                "how do", "explain", "define", "meaning of", "difference between");
    }

    private boolean containsAny(String s, String... terms) {
        for (String term : terms) {
            if (s.contains(term)) return true;
        }
        return false;
    }
}
