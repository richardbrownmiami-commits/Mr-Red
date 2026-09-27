package com.aibot;

import java.util.List;

/**
 * Android-safe OpenCog-style bridge.
 *
 * The native OpenCog AtomSpace is a C++/Linux stack, so this APK uses the
 * existing Java AtomSpaceLite as its on-device representation while exposing
 * OpenCog concepts: typed concepts, relations, graph queries and simple
 * graph-based inference.
 */
public class OpenCogBridge {
    private final AtomSpaceLite atomSpace;

    public OpenCogBridge(AtomSpaceLite atomSpace) {
        this.atomSpace = atomSpace;
    }

    public String execute(String request) {
        if (atomSpace == null) return "OpenCog knowledge space is not available.";
        String q = request == null ? "" : request.trim();
        if (q.isEmpty()) return "Give me a concept or relationship to query.";

        if (q.toLowerCase(java.util.Locale.US).startsWith("learn ")) {
            String fact = q.substring(6).trim();
            atomSpace.learnSentence(fact);
            return "Stored in the AtomSpace-style graph: " + fact;
        }

        List<AtomSpaceLite.Atom> atoms = atomSpace.query(q, 8);
        if (atoms.isEmpty()) {
            atomSpace.learnSentence(q);
            atoms = atomSpace.query(q, 8);
        }

        if (atoms.isEmpty()) {
            return "No matching atoms are known for that request.";
        }

        StringBuilder sb = new StringBuilder("OpenCog-style graph context:");
        for (AtomSpaceLite.Atom atom : atoms) {
            sb.append("\n• ").append(atom.readable())
              .append(" [truth=").append(String.format(java.util.Locale.US, "%.2f", atom.truth)).append("]");
        }
        return sb.toString();
    }

    public void learn(String text) {
        if (atomSpace != null && text != null) atomSpace.learnSentence(text);
    }

    public String stats() {
        return atomSpace == null ? "OpenCog bridge unavailable" :
            "OpenCog bridge: AtomSpace-style graph active; " + atomSpace.size() + " atoms";
    }
}
