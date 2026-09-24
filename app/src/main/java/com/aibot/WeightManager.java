package com.aibot;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import java.io.*;
import java.util.*;

/**
 * WeightManager - Android 11 (API 30) compatible storage
 *
 * Storage strategy:
 *   API 19-29 → /sdcard/AIBot/  (legacy external)
 *   API 30+   → context.getExternalFilesDir() (scoped, no permission needed)
 *              → /sdcard/Android/data/com.aibot/files/AIBot/
 *
 * Datasets folder is always shown to user so they know where to put files.
 */
public class WeightManager {

    private static final String TAG = "WeightManager";
    private static final String APP_FOLDER = "AIBot";

    // Resolved at runtime based on Android version
    private File baseDir;
    private File weightDir;
    private File memoryDir;
    private File cacheDir;
    private File datasetDir;

    // File names
    private static final String MODEL_FILE_NAME   = "model.bin";
    private static final String VOCAB_FILE_NAME   = "vocab.txt";
    private static final String BELIEFS_FILE_NAME = "beliefs.dat";
    private static final String HISTORY_FILE_NAME = "history.txt";
    private static final String META_FILE_NAME    = "meta.txt";

    private NeuralNetwork nn;
    private Tokenizer     tokenizer;
    private NARSEngine    nars;
    private Context       context;

    public WeightManager(Context context, NeuralNetwork nn,
                         Tokenizer tokenizer, NARSEngine nars) {
        this.context   = context.getApplicationContext();
        this.nn        = nn;
        this.tokenizer = tokenizer;
        this.nars      = nars;
        resolveStoragePaths();
        createDirs();
    }

    // ─── PATH RESOLUTION ──────────────────────────────────────────────────────

    private void resolveStoragePaths() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+): use scoped external storage
            // No permission needed for this path
            File extFiles = context.getExternalFilesDir(null);
            if (extFiles == null) {
                // Fallback to internal storage
                extFiles = context.getFilesDir();
            }
            baseDir = new File(extFiles, APP_FOLDER);
        } else {
            // Android 10 and below: use legacy /sdcard/AIBot/
            File sdcard = Environment.getExternalStorageDirectory();
            baseDir = new File(sdcard, APP_FOLDER);
        }

        weightDir  = new File(baseDir, "weights");
        memoryDir  = new File(baseDir, "memory");
        cacheDir   = new File(baseDir, "cache");
        datasetDir = new File(baseDir, "datasets");

        Log.d(TAG, "Storage path: " + baseDir.getAbsolutePath());
    }

    private void createDirs() {
        weightDir.mkdirs();
        memoryDir.mkdirs();
        cacheDir.mkdirs();
        datasetDir.mkdirs();
    }

    // ─── PUBLIC PATH GETTERS ──────────────────────────────────────────────────

    public File getDatasetDir()  { return datasetDir; }
    public File getBaseDir()     { return baseDir; }

    public String getDatasetPath() {
        return datasetDir.getAbsolutePath();
    }

    // ─── FILE GETTERS ─────────────────────────────────────────────────────────

    private File modelFile()   { return new File(weightDir, MODEL_FILE_NAME);   }
    private File vocabFile()   { return new File(weightDir, VOCAB_FILE_NAME);   }
    private File beliefsFile() { return new File(weightDir, BELIEFS_FILE_NAME); }
    private File historyFile() { return new File(memoryDir, HISTORY_FILE_NAME); }
    private File metaFile()    { return new File(weightDir, META_FILE_NAME);    }

    // ─── SAVE ALL ─────────────────────────────────────────────────────────────

    public boolean saveAll() {
        boolean ok = true;
        ok &= saveWeights();
        ok &= saveVocab();
        ok &= saveBeliefs();
        return ok;
    }

    public boolean saveWeights() {
        try {
            nn.saveWeights(modelFile());
            saveMeta();
            Log.d(TAG, "Weights saved → " + modelFile().getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Save weights error: " + e.getMessage());
            return false;
        }
    }

    public boolean saveVocab() {
        try {
            tokenizer.saveVocab(vocabFile());
            Log.d(TAG, "Vocab saved");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Save vocab error: " + e.getMessage());
            return false;
        }
    }

    public boolean saveBeliefs() {
        try {
            nars.saveBeliefs(beliefsFile());
            Log.d(TAG, "Beliefs saved: " + nars.getBeliefCount());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Save beliefs error: " + e.getMessage());
            return false;
        }
    }

    // ─── LOAD ALL ─────────────────────────────────────────────────────────────

    public boolean loadAll() {
        boolean ok = true;
        ok &= loadWeights();
        ok &= loadVocab();
        ok &= loadBeliefs();
        return ok;
    }

    public boolean loadWeights() {
        File f = modelFile();
        if (!f.exists()) {
            Log.d(TAG, "No saved weights, starting fresh");
            return false;
        }
        try {
            nn.loadWeights(f);
            Log.d(TAG, "Weights loaded from " + f.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Load weights error: " + e.getMessage());
            return false;
        }
    }

    public boolean loadVocab() {
        File f = vocabFile();
        if (!f.exists()) return false;
        try {
            tokenizer.loadVocab(f);
            Log.d(TAG, "Vocab loaded: " + tokenizer.getVocabSize() + " words");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Load vocab error: " + e.getMessage());
            return false;
        }
    }

    public boolean loadBeliefs() {
        File f = beliefsFile();
        if (!f.exists()) return false;
        try {
            nars.loadBeliefs(f);
            Log.d(TAG, "Beliefs loaded: " + nars.getBeliefCount());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Load beliefs error: " + e.getMessage());
            return false;
        }
    }

    // ─── CONVERSATION HISTORY ─────────────────────────────────────────────────

    public void appendHistory(String userMsg, String botMsg) {
        try {
            PrintWriter pw = new PrintWriter(
                new FileWriter(historyFile(), true));
            pw.println("USER: " + userMsg);
            pw.println("BOT: "  + botMsg);
            pw.println("---");
            pw.close();
        } catch (IOException e) {
            Log.e(TAG, "History write error: " + e.getMessage());
        }
    }

    public String loadRecentHistory(int maxLines) {
        File f = historyFile();
        if (!f.exists()) return "";
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            br.close();

            int start = Math.max(0, lines.size() - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.size(); i++)
                sb.append(lines.get(i)).append("\n");
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    public void clearHistory() {
        historyFile().delete();
    }

    // ─── META ─────────────────────────────────────────────────────────────────

    private void saveMeta() {
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(metaFile()));
            pw.println("saved="    + System.currentTimeMillis());
            pw.println("vocab="    + tokenizer.getVocabSize());
            pw.println("beliefs="  + nars.getBeliefCount());
            pw.println("android="  + Build.VERSION.SDK_INT);
            pw.println("path="     + baseDir.getAbsolutePath());
            pw.close();
        } catch (IOException e) {
            Log.e(TAG, "Meta save error: " + e.getMessage());
        }
    }

    public String getInfo() {
        long modelSize = modelFile().exists() ? modelFile().length() : 0;
        return "Model: " + (modelSize / 1024) + " KB | " +
               "Vocab: " + tokenizer.getVocabSize() + " | " +
               nars.getStats();
    }

    public String getStorageInfo() {
        return "Storage: " + baseDir.getAbsolutePath();
    }

    public boolean hasExistingWeights() {
        return modelFile().exists();
    }

    // ─── RESET ────────────────────────────────────────────────────────────────

    public void resetAll() {
        modelFile().delete();
        vocabFile().delete();
        beliefsFile().delete();
        historyFile().delete();
        metaFile().delete();
        Log.d(TAG, "All weights reset");
    }
}
