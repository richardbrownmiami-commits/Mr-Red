package com.aibot;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Offline factual lookup over the same unified corpus used for training. */
public final class KnowledgeBase {
    private static final class Entry {
        final String input;
        final String output;
        final String normalized;
        Entry(String input, String output) {
            this.input = input;
            this.output = output;
            this.normalized = normalize(input);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public KnowledgeBase(Context context) {
        load(context);
    }

    private void load(Context context) {
        try (InputStream in = context.getResources().openRawResource(R.raw.core_assistant);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                try {
                    JSONObject o = new JSONObject(line);
                    String input = o.optString("input", "").trim();
                    String output = o.optString("output", "").trim();
                    if (!input.isEmpty() && !output.isEmpty()) {
                        entries.add(new Entry(input, output));
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    public String lookup(String query) {
        String q = normalize(query);
        if (q.isEmpty()) return "";

        // Exact match first. This makes known definitions deterministic and
        // independent of the neural model, ONNX, or network availability.
        for (Entry e : entries) {
            if (e.normalized.equals(q)) return e.output;
        }

        String topic = factualTopic(q);
        if (topic.isEmpty()) return "";

        String best = "";
        int bestScore = 0;
        for (Entry e : entries) {
            if (!isFactualEntry(e.normalized)) continue;
            int score = overlap(topic, factualTopic(e.normalized));
            if (score > bestScore) {
                bestScore = score;
                best = e.output;
            }
        }
        return bestScore >= 1 ? best : "";
    }

    private static boolean isFactualEntry(String q) {
        return q.startsWith("what is ") || q.startsWith("what are ") ||
               q.startsWith("what's ") || q.startsWith("define ") ||
               q.startsWith("tell me about ") || q.startsWith("explain ") ||
               q.startsWith("meaning of ") || q.startsWith("who is ") ||
               q.startsWith("who are ");
    }

    private static String factualTopic(String q) {
        String[] p = {"what is ", "what are ", "what's ", "define ",
            "tell me about ", "explain ", "meaning of ", "who is ", "who are "};
        for (String x : p) {
            if (q.startsWith(x)) return q.substring(x.length()).trim();
        }
        return "";
    }

    private static int overlap(String a, String b) {
        String[] aa = a.split("\\s+");
        String[] bb = b.split("\\s+");
        int score = 0;
        for (String x : aa) {
            if (x.length() < 2) continue;
            for (String y : bb) {
                if (x.equals(y)) {
                    score++;
                    break;
                }
            }
        }
        return score;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US)
            .replaceAll("[^a-z0-9\\s']", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    public int size() {
        return entries.size();
    }
}
