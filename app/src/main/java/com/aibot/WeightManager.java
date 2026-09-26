package com.aibot;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import java.io.*;
import java.util.*;

/**
 * WeightManager - FIXED for Redmi 9 / Android 11 auto-shutdown
 * - Adds null checks for getExternalFilesDir
 * - Creates baseDir first
 * - Never crashes if folder missing
 * - Runs without dataset (blank boot allowed)
 */
public class WeightManager {

    private static final String TAG = "WeightManager";
    private static final String APP_FOLDER = "AIBot";

    private File baseDir;
    private File weightDir;
    private File memoryDir;
    private File cacheDir;
    private File datasetDir;

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

    private void resolveStoragePaths() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                File extFiles = null;
                try {
                    extFiles = context.getExternalFilesDir(null);
                } catch (Exception e) {
                    Log.e(TAG, "getExternalFilesDir failed: " + e.getMessage());
                }
                if (extFiles == null) {
                    try {
                        extFiles = context.getFilesDir();
                    } catch (Exception e) {
                        Log.e(TAG, "getFilesDir failed: " + e.getMessage());
                    }
                }
                if (extFiles == null) {
                    try {
                        extFiles = context.getCacheDir();
                    } catch (Exception e) {
                        Log.e(TAG, "getCacheDir failed: " + e.getMessage());
                    }
                }
                if (extFiles == null) {
                    extFiles = new File("/data/data/" + context.getPackageName() + "/files");
                }
                baseDir = new File(extFiles, APP_FOLDER);
            } else {
                File sdcard = Environment.getExternalStorageDirectory();
                baseDir = new File(sdcard, APP_FOLDER);
            }
        } catch (Exception e) {
            Log.e(TAG, "resolveStoragePaths crash: " + e.getMessage());
            try {
                baseDir = new File(context.getFilesDir(), APP_FOLDER);
            } catch (Exception ex) {
                baseDir = new File(context.getCacheDir(), APP_FOLDER);
            }
        }

        weightDir  = new File(baseDir, "weights");
        memoryDir  = new File(baseDir, "memory");
        cacheDir   = new File(baseDir, "cache");
        datasetDir = new File(baseDir, "datasets");

        Log.d(TAG, "Storage path: " + baseDir.getAbsolutePath());
    }

    private void createDirs() {
        try {
            if (baseDir != null && !baseDir.exists()) {
                boolean ok = baseDir.mkdirs();
                Log.d(TAG, "baseDir mkdirs: " + ok + " -> " + baseDir.getAbsolutePath());
                if (!ok) {
                    baseDir.mkdir();
                }
            }
            if (weightDir != null) weightDir.mkdirs();
            if (memoryDir != null) memoryDir.mkdirs();
            if (cacheDir != null) cacheDir.mkdirs();
            if (datasetDir != null) datasetDir.mkdirs();
            
            // double check
            if (weightDir != null && !weightDir.exists()) weightDir.mkdir();
            if (datasetDir != null && !datasetDir.exists()) datasetDir.mkdir();
        } catch (Exception e) {
            Log.e(TAG, "createDirs error: " + e.getMessage());
        }
    }

    public File getDatasetDir()  { return datasetDir; }
    public File getBaseDir()     { return baseDir; }

    public String getDatasetPath() {
        try {
            return datasetDir != null ? datasetDir.getAbsolutePath() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private File modelFile()   { 
        try {
            if (weightDir != null && !weightDir.exists()) weightDir.mkdirs();
            return new File(weightDir, MODEL_FILE_NAME);
        } catch (Exception e) {
            return new File(baseDir, MODEL_FILE_NAME);
        }
    }
    private File vocabFile()   { 
        try {
            if (weightDir != null && !weightDir.exists()) weightDir.mkdirs();
            return new File(weightDir, VOCAB_FILE_NAME);
        } catch (Exception e) {
            return new File(baseDir, VOCAB_FILE_NAME);
        }
    }
    private File beliefsFile() { 
       
