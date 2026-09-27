package com.aibot;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Small Hugging Face Hub client.
 * Uses public Hub REST/search and resolve URLs; no HF SDK or native runtime is required.
 */
public class HuggingFaceHub {
    public static class Item {
        public String id;
        public String type;
        public String task;
        public long downloads;
        public String description;
        public List<String> files = new ArrayList<>();

        @Override public String toString() {
            String extra = task == null || task.isEmpty() ? "" : " [" + task + "]";
            return id + extra + (downloads > 0 ? " (" + downloads + " downloads)" : "");
        }
    }

    public List<Item> searchModels(String query, int limit) throws Exception {
        return search("https://huggingface.co/api/models?search=" + enc(query) +
                "&limit=" + Math.max(1, Math.min(limit, 30)) + "&sort=downloads&direction=-1", "model");
    }

    public List<Item> searchDatasets(String query, int limit) throws Exception {
        return search("https://huggingface.co/api/datasets?search=" + enc(query) +
                "&limit=" + Math.max(1, Math.min(limit, 30)) + "&sort=downloads&direction=-1", "dataset");
    }

    public List<String> listFiles(String repoId, String type) throws Exception {
        String prefix = "dataset".equalsIgnoreCase(type) ? "datasets/" : "";
        String url = "https://huggingface.co/api/" + ("dataset".equalsIgnoreCase(type) ? "datasets/" : "models/")
                + repoId + "/tree/main?recursive=true";
        JSONArray arr = new JSONArray(get(url));
        List<String> files = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String path = o.optString("path", "");
            String kind = o.optString("type", "file");
            if ("file".equals(kind) && !path.isEmpty()) files.add(path);
        }
        return files;
    }

    public File downloadModel(String repoId, String filename, File destination) throws Exception {
        return download(repoId, filename, false, destination);
    }

    public File downloadDataset(String repoId, String filename, File destination) throws Exception {
        return download(repoId, filename, true, destination);
    }

    private List<Item> search(String url, String type) throws Exception {
        JSONArray arr = new JSONArray(get(url));
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Item item = new Item();
            item.id = o.optString("id", o.optString("modelId", ""));
            item.type = type;
            item.task = o.optString("pipeline_tag", "");
            item.downloads = o.optLong("downloads", 0);
            item.description = o.optString("description", "");
            JSONArray siblings = o.optJSONArray("siblings");
            if (siblings != null) {
                for (int j = 0; j < siblings.length(); j++) {
                    JSONObject s = siblings.optJSONObject(j);
                    if (s != null) item.files.add(s.optString("rfilename", ""));
                }
            }
            if (!item.id.isEmpty()) result.add(item);
        }
        return result;
    }

    private File download(String repoId, String filename, boolean dataset, File destination) throws Exception {
        if (repoId == null || !repoId.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Use a public Hugging Face repo id such as org/name");
        if (filename == null || filename.contains("..") || filename.startsWith("/"))
            throw new IllegalArgumentException("Invalid Hub filename");
        if (destination.getParentFile() != null) destination.getParentFile().mkdirs();

        String type = dataset ? "datasets/" : "";
        URL u = new URL("https://huggingface.co/" + type + repoId + "/resolve/main/" + encodePath(filename));
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(180000);
        c.setRequestProperty("User-Agent", "AIBot/1.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IOException("Hugging Face download HTTP " + code);
        }
        File tmp = new File(destination.getParentFile(), destination.getName() + ".part");
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.getFD().sync();
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(destination)) {
            if (destination.exists()) destination.delete();
            if (!tmp.renameTo(destination)) throw new IOException("Could not finalize Hub download");
        }
        return destination;
    }

    private String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "AIBot/1.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("Hub HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally { c.disconnect(); }
        return sb.toString();
    }

    private String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private String encodePath(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('/');
            try { sb.append(URLEncoder.encode(p, "UTF-8").replace("+", "%20")); }
            catch (Exception e) { sb.append(p); }
        }
        return sb.toString();
    }
}
