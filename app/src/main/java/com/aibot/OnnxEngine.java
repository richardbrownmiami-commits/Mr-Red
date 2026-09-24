package com.aibot;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;
import ai.onnxruntime.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class OnnxEngine {
    private static final String TAG = "OnnxEngine";
    private Context ctx;
    private OrtEnvironment env;
    private OrtSession sessionMini, sessionPhi;
    private File modelDir;

    public OnnxEngine(Context ctx) {
        this.ctx = ctx;
        this.modelDir = new File(ctx.getExternalFilesDir(null), "AIBot/models");
        modelDir.mkdirs();
        try { env = OrtEnvironment.getEnvironment(); } catch (Exception e) {}
    }

    // TURBO for 32-bit - fix for freeze
    private SessionOptions turboOpts() throws OrtException {
        SessionOptions opts = new SessionOptions();
        opts.setIntraOpNumThreads(2);
        opts.setInterOpNumThreads(1);
        opts.setOptimizationLevel(SessionOptions.OptLevel.BASIC_OPT);
        opts.setExecutionMode(SessionOptions.ExecutionMode.SEQUENTIAL);
        opts.addConfigEntry("session.use_env_allocators", "1");
        return opts;
    }

    public boolean load(String name) {
        try {
            File modelFile = new File(modelDir, name);

            // Auto download if not exists
            if (!modelFile.exists()) {
                String url = getUrlFor(name);
                if (url!= null) {
                    showToast("⬇️ Downloading " + name);
                    downloadSync(url, modelFile);
                    showToast("✅ " + name + " ready");
                } else return false;
            }

            if (name.contains("minilm")) {
                if (sessionMini!= null) sessionMini.close();
                sessionMini = env.createSession(modelFile.getAbsolutePath(), turboOpts());
            } else if (name.contains("phi")) {
                if (sessionPhi!= null) sessionPhi.close();
                sessionPhi = env.createSession(modelFile.getAbsolutePath(), turboOpts());
            }
            Log.d(TAG, "Loaded: " + name);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Load fail " + name + ": " + e.getMessage());
            return false;
        }
    }

    // Embedding for memory
    public float[] embed(String text) {
        try {
            if (sessionMini == null) load("minilm.onnx");
            if (sessionMini == null) return null;

            long[] ids = tokenizeSimple(text, 128);
            long[][] inputIds = new long[1][128];
            long[][] mask = new long[1][128];
            System.arraycopy(ids, 0, inputIds[0], 0, ids.length);
            Arrays.fill(mask[0], 1);

            OnnxTensor t1 = OnnxTensor.createTensor(env, inputIds);
            OnnxTensor t2 = OnnxTensor.createTensor(env, mask);

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", t1);
            inputs.put("attention_mask", t2);

            OrtSession.Result result = sessionMini.run(inputs);
            float[][] embedding = (float[][]) result.get(0).getValue();

            t1.close(); t2.close(); result.close();
            return embedding[0];
        } catch (Exception e) {
            Log.e(TAG, "Embed error: " + e.getMessage());
            return null;
        }
    }

    private String getUrlFor(String name) {
        if (name.equals("minilm.onnx"))
            return "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx";
        if (name.equals("phi.onnx"))
            return "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx/resolve/main/cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4/model.onnx";
        return null;
    }

    private long[] tokenizeSimple(String text, int maxLen) {
        long[] ids = new long[maxLen];
        String[] words = text.toLowerCase().split("\\s+");
        for (int i = 0; i < Math.min(words.length, maxLen); i++) {
            ids[i] = Math.abs(words[i].hashCode() % 30000) + 1;
        }
        return ids;
    }

    private void downloadSync(String urlStr, File dest) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.connect();
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf))!= -1) out.write(buf, 0, len);
            out.close(); in.close();
        } catch (Exception e) {
            Log.e(TAG, "Download fail: " + e.getMessage());
        }
    }

    private void showToast(String msg) {
        try {
            android.os.Handler h = new android.os.Handler(ctx.getMainLooper());
            h.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {}
    }

    public void close() {
        try {
            if (sessionMini!= null) sessionMini.close();
            if (sessionPhi!= null) sessionPhi.close();
            if (env!= null) env.close();
        } catch (Exception e) {}
    }
                                  }
