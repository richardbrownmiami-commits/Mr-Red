package com.aibot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * ONA-style application agent.
 *
 * ONA (OpenNARS for Applications) is normally a native C implementation.
 * This Android-safe adapter keeps the application-agent pattern local:
 * prioritized goals are queued, AtomSpace provides structured context, and
 * the existing Java NARS engine performs formal inference on demand.
 */
public class OnaEngine {
    private static class Goal {
        final String text;
        final float priority;
        Goal(String text, float priority) {
            this.text = text;
            this.priority = priority;
        }
    }

    private final NARSEngine nars;
    private final AtomSpaceLite atomSpace;
    private final Deque<Goal> goals = new ArrayDeque<>();
    private int cycles = 0;

    public OnaEngine(NARSEngine nars, AtomSpaceLite atomSpace) {
        this.nars = nars;
        this.atomSpace = atomSpace;
    }

    public synchronized void addGoal(String goal, float priority) {
        if (goal == null || goal.trim().isEmpty()) return;
        goals.addLast(new Goal(goal.trim(), Math.max(0f, Math.min(1f, priority))));
        while (goals.size() > 64) goals.removeFirst();
    }

    public synchronized String execute(String request) {
        String q = request == null ? "" : request.trim();
        if (q.isEmpty()) return "Give me an application goal or reasoning task.";

        addGoal(q, 0.8f);
        cycles++;

        String narsAnswer = nars != null ? nars.answerQuestion(q) : null;
        if (narsAnswer != null && !narsAnswer.startsWith("I don't")) {
            return "ONA agent result:\n" + narsAnswer;
        }

        if (atomSpace != null) {
            List<AtomSpaceLite.Atom> atoms = atomSpace.query(q, 5);
            if (!atoms.isEmpty()) {
                StringBuilder sb = new StringBuilder("ONA agent context:");
                for (AtomSpaceLite.Atom a : atoms) sb.append("\n• ").append(a.readable());
                return sb.toString();
            }
        }

        return "ONA queued the goal for application reasoning: " + q;
    }

    public synchronized String stats() {
        return "ONA agent: cycles=" + cycles + ", queuedGoals=" + goals.size();
    }

    public synchronized void clear() {
        goals.clear();
    }
}
