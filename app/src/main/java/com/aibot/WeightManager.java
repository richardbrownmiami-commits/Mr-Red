package com.aibot;

import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.BufferedReader;
import android.util.Log;

public class WeightManager {
    private android.content.Context context;
    private NeuralNetwork nn;
    private Tokenizer tokenizer;
    private NARSEngine nars;
    private File baseDir;
    private File datasetDir;

    public WeightManager(android.content.Context ctx, NeuralNetwork nn, Tokenizer tokenizer, NARSEngine nars) {
        this.context = ctx;
        this.nn = nn;
        this.tokenizer = tokenizer;
        this.nars = nars;
        this.baseDir = new File(ctx.getFilesDir(), "aibot_weights");
        if (!baseDir.exists()) baseDir.mkdirs();
        this.datasetDir = new File(ctx.getExternalFilesDir(null), "datasets");
        if (datasetDir == null) datasetDir = new File(baseDir, "datasets");
        if (!datasetDir.exists()) datasetDir.mkdirs();
    }

    public File getDatasetDir() { return datasetDir; }
    public String getDatasetPath() { return datasetDir.getAbsolutePath(); }
    public String getModelPath() { return baseDir.getAbsolutePath(); }

    public boolean hasExistingWeights() {
        File f = new File(baseDir, "model.bin");
        return f.exists() && f.length() > 1000;
    }

    public void loadAll() {
        try {
            File model = new File(baseDir, "model.bin");
            if (model.exists() && nn != null) nn.loadWeights(model);

            File vocab = new File(baseDir, "vocab.txt");
            if (vocab.exists() && tokenizer != null) tokenizer.loadVocab(vocab);

            File bel = new File(baseDir, "beliefs.bin");
            if (bel.exists() && nars != null) nars.loadBeliefs(bel);
        } catch (Exception e) {
            Log.e("WeightManager", "loadAll", e);
        }
    }

    public synchronized void saveAll() {
        try {
            File model = new File(baseDir, "model.bin");
            if (nn != null) nn.saveWeights(model);

            File vocab = new File(baseDir, "vocab.txt");
            if (tokenizer != null) tokenizer.saveVocab(vocab);

            File bel = new File(baseDir, "beliefs.bin");
            if (nars != null) nars.saveBeliefs(bel);

            File marker = new File(baseDir, "weights.json");
            FileWriter fw = new FileWriter(marker);
            fw.write("{\"version\":2,\"model\":\"model.bin\",\"vocab\":\"vocab.txt\",\"beliefs\":\"beliefs.bin\"}");
            fw.close();
        } catch (Exception e) {
            Log.e("WeightManager", "saveAll", e);
        }
    }

    public void appendHistory(String input, String response) {
        try {
            File hist = new File(baseDir, "history.jsonl");
            FileWriter fw = new FileWriter(hist, true);
            fw.write(input + " -> " + response + "\n");
            fw.close();
        } catch (Exception e) {}
    }

    public String getInfo() {
        int beliefCount = 0;
        try {
            beliefCount = nars != null ? nars.getBeliefCount() : 0;
        } catch (Exception e) {}
        return "Beliefs:" + beliefCount + " | " + baseDir.getName();
    }

    public void resetAll() {
        try {
            File[] files = baseDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            // NARSEngine has no clear() in this version - just ignore
            // If you want clear, add public void clear() { beliefs.clear(); } to NARSEngine
        } catch (Exception e) {}
    }

    private File beliefsFile() {
        return new File(baseDir, "beliefs.txt");
    }