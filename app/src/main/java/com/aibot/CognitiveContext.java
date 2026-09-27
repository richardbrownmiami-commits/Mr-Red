package com.aibot;

import java.util.List;

/**
 * Unified cognitive context layer.
 *
 * NARS, AtomSpace/OpenCog and ONA are embedded cognition here. They do not
 * generate the normal chat response. They only contribute compact context
 * that the neural network can use when relevant.
 */
public class CognitiveContext {
    private final CognitiveMemory memory;
    private final NARSEngine nars;
    private final AtomSpaceLite atomSpace;
    private final OnaEngine ona;

    public CognitiveContext(CognitiveMemory memory, NARSEngine nars,
                            AtomSpaceLite atomSpace, OnaEngine ona) {
        this.memory = memory;
        this.nars = nars;
        this.atomSpace = atomSpace;
        this.ona = ona;
    }

    public String retrieve(String input) {
        String q = input == null ? "" : input.trim();
        if (q.isEmpty()) return "";

        StringBuilder out = new StringBuilder();

        if (memory != null) {
            append(out, memory.buildContext(q, 3));
        }

        if (atomSpace != null) {
            List<AtomSpaceLite.Atom> atoms = atomSpace.query(q, 6);
            if (!atoms.isEmpty()) {
                append(out, "Semantic knowledge:");
                for (AtomSpaceLite.Atom a : atoms) {
                    append(out, a.readable() + " [confidence " +
                            String.format(java.util.Locale.US, "%.2f", a.truth) + "]");
                }
            }
        }

        if (nars != null) {
            String narsContext = nars.buildContextFromBeliefs(q);
            if (narsContext != null && !narsContext.trim().isEmpty()) {
                append(out, "Reasoning context: " + narsContext);
            }
        }

        if (ona != null) {
            String goalContext = ona.buildCognitiveContext(q);
            if (goalContext != null && !goalContext.trim().isEmpty()) {
                append(out, goalContext);
            }
        }

        return out.toString();
    }

    public void rememberConversation(String input, String response) {
        if (memory != null) memory.remember(input, response, "conversation");
    }

    private void append(StringBuilder out, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (out.length() > 0) out.append("\n");
        out.append(value.trim());
    }
}
