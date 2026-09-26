package com.aibot;

import java.util.List;

/**
 * NarsTool - explicit reasoning tool for the assistant.
 * NARS is available on demand instead of being forced into every conversation.
 */
public class NarsTool {
    private final NARSEngine nars;

    public NarsTool(NARSEngine nars) {
        this.nars = nars;
    }

    public String execute(String request) {
        if (nars == null) return "NARS is not available.";
        String q = request == null ? "" : request.trim();
        if (q.isEmpty()) return "Give me something to reason about.";

        String answer = nars.answerQuestion(q);
        if (answer != null && !answer.startsWith("I don't")) return answer;

        List<Belief> learned = nars.parseAndLearn(q);
        if (!learned.isEmpty()) {
            StringBuilder sb = new StringBuilder("I added this to my reasoning memory:");
            for (Belief b : learned) sb.append("
• ").append(b.toReadable());
            String inferred = nars.answerQuestion(q);
            if (inferred != null && !inferred.startsWith("I don't")) sb.append("

").append(inferred);
            return sb.toString();
        }

        List<Belief> related = nars.getRelatedBeliefs(q);
        if (!related.isEmpty()) {
            StringBuilder sb = new StringBuilder("NARS reasoning context:");
            for (Belief b : related) sb.append("
• ").append(b.toReadable());
            return sb.toString();
        }

        return "NARS could not derive an answer from its current beliefs.";
    }

    public String getStats() {
        return nars == null ? "NARS unavailable" : nars.getStats();
    }
}
