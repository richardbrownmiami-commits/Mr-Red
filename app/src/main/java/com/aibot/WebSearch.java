package com.aibot;

import android.util.Log;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.*;
import org.json.JSONObject;

/**
 * Web Search using DuckDuckGo (no API key needed)
 * Parses results and returns clean text
 */
public class WebSearch {

    private static final String TAG = "WebSearch";
    private static final String DDG_URL = "https://html.duckduckgo.com/html/?q=";
    private static final int TIMEOUT_MS = 6000;
    private static final int MAX_RESULTS = 5;

    public interface SearchCallback {
        void onResults(List<SearchResult> results);
        void onError(String error);
    }

    public static class SearchResult {
        public String title;
        public String snippet;
        public String url;

        public SearchResult(String title, String snippet, String url) {
            this.title   = title;
            this.snippet = snippet;
            this.url     = url;
        }

        @Override
        public String toString() {
            return title + ": " + snippet;
        }
    }

    /** Lightweight factual lookup. Avoids HTML parsing for simple "what is" questions. */
    public List<SearchResult> searchKnowledge(String query) {
        List<SearchResult> out = new ArrayList<>();
        String topic = query == null ? "" : query.trim();
        String low = topic.toLowerCase(Locale.US);
        String[] prefixes = {"what is ", "what are ", "define ", "tell me about ", "explain ", "meaning of "};
        for (String p : prefixes) if (low.startsWith(p)) { topic = topic.substring(p.length()).trim(); break; }
        if (topic.isEmpty()) return out;
        HttpURLConnection conn = null;
        try {
            String path = URLEncoder.encode(topic.replace(' ', '_'), "UTF-8").replace("+", "%20");
            URL u = new URL("https://en.wikipedia.org/api/rest_v1/page/summary/" + path);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mr-Red-Android/1.0");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return out;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder(); String line;
            while ((line = br.readLine()) != null && body.length() < 200000) body.append(line);
            br.close();
            JSONObject json = new JSONObject(body.toString());
            String extract = json.optString("extract", "").trim();
            if (!extract.isEmpty()) {
                String page = json.optString("content_urls", "");
                out.add(new SearchResult(json.optString("title", topic), extract, page));
            }
        } catch (Exception e) {
            Log.w(TAG, "Knowledge lookup failed: " + e.getMessage());
        } finally { if (conn != null) conn.disconnect(); }
        return out;
    }

    // ─── SYNC SEARCH ──────────────────────────────────────────────────────────

    public List<SearchResult> search(String query) {
        List<SearchResult> results = new ArrayList<>();
        try {
            String url = DDG_URL + query.replace(" ", "+");
            Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Android; Mobile)")
                .timeout(TIMEOUT_MS)
                .get();

            Elements resultDivs = doc.select(".result");
            int count = 0;

            for (Element div : resultDivs) {
                if (count >= MAX_RESULTS) break;

                Element titleEl   = div.selectFirst(".result__title");
                Element snippetEl = div.selectFirst(".result__snippet");
                Element urlEl     = div.selectFirst(".result__url");

                if (titleEl == null || snippetEl == null) continue;

                String title   = titleEl.text().trim();
                String snippet = snippetEl.text().trim();
                String link    = urlEl != null ? urlEl.text().trim() : "";

                if (!title.isEmpty() && !snippet.isEmpty()) {
                    results.add(new SearchResult(title, snippet, link));
                    count++;
                }
            }

            // Fallback: try different selectors
            if (results.isEmpty()) {
                Elements links = doc.select("a.result__a");
                Elements snips = doc.select(".result__snippet");
                for (int i = 0; i < Math.min(links.size(), snips.size()) && i < MAX_RESULTS; i++) {
                    results.add(new SearchResult(
                        links.get(i).text(),
                        snips.get(i).text(),
                        links.get(i).attr("href")
                    ));
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Search error: " + e.getMessage());
        }
        return results;
    }

    // ─── ASYNC SEARCH ─────────────────────────────────────────────────────────

    public void searchAsync(String query, SearchCallback callback) {
        new Thread(() -> {
            List<SearchResult> results = search(query);
            if (results.isEmpty()) {
                callback.onError("No results found for: " + query);
            } else {
                callback.onResults(results);
            }
        }).start();
    }

    // ─── SUMMARIZE RESULTS ────────────────────────────────────────────────────

    /**
     * Convert search results to a readable summary string
     * for feeding into the bot's context
     */
    public String summarizeResults(List<SearchResult> results) {
        if (results.isEmpty()) return "No information found.";

        StringBuilder sb = new StringBuilder();
        sb.append("Web search results: ");
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            SearchResult r = results.get(i);
            sb.append(r.title).append(": ").append(r.snippet).append(" ");
        }
        return sb.toString().trim();
    }

    /**
     * Extract facts from search results for NARS
     */
    public List<String> extractFacts(List<SearchResult> results) {
        List<String> facts = new ArrayList<>();
        for (SearchResult r : results) {
            // Split snippet into sentences
            String[] sentences = r.snippet.split("[.!?]");
            for (String s : sentences) {
                s = s.trim();
                if (s.length() > 10 && s.length() < 200) {
                    facts.add(s);
                }
            }
        }
        return facts;
    }
}
