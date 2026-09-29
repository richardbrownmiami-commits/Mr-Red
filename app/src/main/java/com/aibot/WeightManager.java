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
        File external = ctx.getExternalFilesDir(null);
        this.datasetDir = external != null ? new File(external, "datasets") : new File(baseDir, "datasets");
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
            if (model.exists() && nn != null) {
                try {
                    nn.loadWeights(model);
                } catch (Exception incompatible) {
                    Log.w("WeightManager", "Old/incompatible model; rebuilding", incompatible);
                    model.delete();
                }
            }

            File vocab = new File(baseDir, "vocab.txt");
            if (vocab.exists() && tokenizer != null) {
                tokenizer.loadVocab(vocab);
                if (nn != null) nn.setActiveVocabSize(tokenizer.getVocabSize());
            }

            File bel = new File(baseDir, "beliefs.bin");
            if (bel.exists() && nars != null) nars.loadBeliefs(bel);
        } catch (Exception e) {
            Log.e("WeightManager", "loadAll", e);
        }
    }

    public synchronized boolean saveAll() {
        try {
            if (!baseDir.exists() && !baseDir.mkdirs()) throw new IOException("Cannot create weights directory");
            File model = new File(baseDir, "model.bin");
            File vocab = new File(baseDir, "vocab.txt");
            File bel = new File(baseDir, "beliefs.bin");
            if (nn != null) atomicModelSave(model);
            if (tokenizer != null) atomicVocabSave(vocab);
            if (nars != null) atomicBeliefSave(bel);
            if (!model.exists() || model.length() <= 1000) throw new IOException("Model save incomplete");
            try (FileWriter fw = new FileWriter(new File(baseDir, "weights.json"))) {
                fw.write("{\"version\":3,\"model\":\"model.bin\",\"vocab\":\"vocab.txt\",\"beliefs\":\"beliefs.bin\"}");
            }
            return true;
        } catch (Exception e) {
            Log.e("WeightManager", "saveAll", e);
            return false;
        }
    }

    private void atomicModelSave(File destination) throws IOException {
        File tmp = new File(destination.getParentFile(), destination.getName() + ".part");
        if (tmp.exists()) tmp.delete();
        nn.saveWeights(tmp);
        replaceAtomically(tmp, destination);
    }

    private void atomicVocabSave(File destination) throws IOException {
        File tmp = new File(destination.getParentFile(), destination.getName() + ".part");
        if (tmp.exists()) tmp.delete();
        tokenizer.saveVocab(tmp);
        replaceAtomically(tmp, destination);
    }

    private void atomicBeliefSave(File destination) throws IOException {
        File tmp = new File(destination.getParentFile(), destination.getName() + ".part");
        if (tmp.exists()) tmp.delete();
        nars.saveBeliefs(tmp);
        replaceAtomically(tmp, destination);
    }

    private void replaceAtomically(File tmp, File destination) throws IOException {
        if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace " + destination.getName());
        if (!tmp.renameTo(destination)) throw new IOException("Cannot finalize " + destination.getName());
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
}
