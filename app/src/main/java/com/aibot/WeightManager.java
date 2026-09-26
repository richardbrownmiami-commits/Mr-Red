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
        File f = new File(baseDir, "weights.json");
        return f.exists() && f.length() > 10;
    }

    public void loadAll() {
        try {
            File w = new File(baseDir, "weights.json");
            if (w.exists()) {
                // nn load placeholder
            }
            File tok = new File(baseDir, "tokenizer.json");
            if (tok.exists() && tokenizer != null) {}
            File bel = beliefsFile();
            if (bel.exists() && nars != null) {
                BufferedReader br = new BufferedReader(new FileReader(bel));
                String line;
                while ((line = br.readLine()) != null) {
                    nars.parseAndLearn(line);
                }
                br.close();
            }
        } catch (Exception e) {
            Log.e("WeightManager", "loadAll", e);
        }
    }

    public void saveAll() {
        try {
            File w = new File(baseDir, "weights.json");
            FileWriter fw = new FileWriter(w);
            fw.write("{}");
            fw.close();
            if (nars != null) {
                File bel = beliefsFile();
                FileWriter bw = new FileWriter(bel);
                for (Belief b : nars.getAllBeliefs()) {
                    bw.write(b.toString() + "\n");
                }
                bw.close();
            }
        } catch (Exception e) {
            Log.e("WeightManager", "saveAll", e);
        }
    }

    public void appendHistory(String input, String response) {
        try {
            File hist = new File(baseDir, "history.jsonl");
            FileWriter fw = new FileWriter(hist, true);
            fw.write("{\"in\":\"" + input.replace("\"","") + "\",\"out\":\"" + response.replace("\"","") + "\"}\n");
            fw.close();
        } catch (Exception e) {}
    }

    public String getInfo() {
        int beliefCount = nars != null ? nars.getBeliefCount() : 0;
        return "Beliefs:" + beliefCount + " | " + baseDir.getName();
    }

    public void resetAll() {
        try {
            File[] files = baseDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            if (nars != null) nars.clear();
        } catch (Exception e) {}
    }

    private File beliefsFile() {
        return new File(baseDir, "beliefs.txt");
    }
                     }
