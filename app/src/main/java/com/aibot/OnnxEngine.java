package com.aibot;

import ai.onnxruntime.*;
import android.content.Context;
import android.util.Log;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.*;

/**
 * OnnxEngine - loads and runs .onnx models on ARMv7a
 *
 * Use cases in AIBot:
 * 1. Run exported NeuralNetwork.onnx for better inference
 * 2. Run personality model (whisper_personality.onnx)
 * 3. Run any HuggingFace exported .onnx model
 *
 * Models go in: /sdcard/Android/data/com.aibot/files/AIBot/models/
 */
public class OnnxEngine {

    private static final String TAG = "OnnxEngine";

    private OrtEnvironment environment;
    private OrtSession    session;
    private boolean       isLoaded = false;
    private String        loadedModelName = "";

    // Model folder inside app storage
    public static final String MODEL_SUBFOLDER = "models";

    private Context context;
    private File    modelDir;

    public OnnxEngine(Context context, WeightManager weightManager) {
        this.context  = context.getApplicationContext();
        this.modelDir = new File(weightManager.getBaseDir(), MODEL_SUBFOLDER);
        this.modelDir.mkdirs();
        initEnvironment();
    }

    // ─── INIT ─────────────────────────────────────────────────────────────────

    private void initEnvironment() {
        try {
            environment = OrtEnvironment.getEnvironment();
            Log.d(TAG, "ONNX Runtime environment initialized");
        } catch (Exception e) {
            Log.e(TAG, "ONNX init error: " + e.getMessage());
        }
    }

    // ─── LOAD MODEL ───────────────────────────────────────────────────────────

    public boolean loadModel(String filename) {
        File modelFile = new File(modelDir, filename);
        if (!modelFile.exists()) {
            Log.e(TAG, "Model not found: " + modelFile.getAbsolutePath());
            return false;
        }
        return loadModelFromFile(modelFile);
    }

    public boolean loadModelFromFile(File modelFile) {
        try {
            // Close existing session first
            closeSession();

            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            // Optimize for mobile/ARM
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            opts.setIntraOpNumThreads(2); // 2 threads safe for ARMv7a

            session         = environment.createSession(modelFile.getAbsolutePath(), opts);
            isLoaded        = true;
            loadedModelName = modelFile.getName();

            Log.d(TAG, "Model loaded: " + loadedModelName);
            Log.d(TAG, "Inputs:  " + session.getInputNames());
            Log.d(TAG, "Outputs: " + session.getOutputNames());
            return true;

        } catch (OrtException e) {
            Log.e(TAG, "Load model error: " + e.getMessage());
            isLoaded = false;
            return false;
        }
    }

    // ─── LOAD FROM ASSETS / RAW ───────────────────────────────────────────────

    public boolean loadModelFromAssets(String assetName) {
        try {
            // Copy from assets to file system first
            File outFile = new File(modelDir, assetName);
            if (!outFile.exists()) {
                InputStream is = context.getAssets().open(assetName);
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                is.close();
                fos.close();
            }
            return loadModelFromFile(outFile);
        } catch (Exception e) {
            Log.e(TAG, "Load from assets error: " + e.getMessage());
            return false;
        }
    }

    // ─── INFERENCE - FLOAT INPUT ──────────────────────────────────────────────

    /**
     * Run inference with float array input
     * Used for: our NeuralNetwork exported embeddings
     */
    public float[] runFloat(String inputName, float[] inputData, long[] shape) {
        if (!isLoaded) return null;
        try {
            OnnxTensor inputTensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(inputData),
                shape
            );

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, inputTensor);

            OrtSession.Result result = session.run(inputs);
            OnnxTensor output = (OnnxTensor) result.get(0);
            float[][] raw = (float[][]) output.getValue();

            inputTensor.close();
            result.close();

            // Flatten if needed
            if (raw.length > 0) return raw[0];
            return new float[0];

        } catch (OrtException e) {
            Log.e(TAG, "Inference error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Run inference with long (token ID) input
     * Used for: transformer models, token-based models
     */
    public float[] runTokens(String inputName, long[] tokenIds) {
        if (!isLoaded) return null;
        try {
            long[] shape = {1, tokenIds.length}; // [batch=1, seq_len]
            OnnxTensor inputTensor = OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(tokenIds),
                shape
            );

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, inputTensor);

            OrtSession.Result result = session.run(inputs);
            OnnxTensor output = (OnnxTensor) result.get(0);

            // Get logits [1, seq_len, vocab_size] or [1, vocab_size]
            Object raw = output.getValue();
            float[] logits = extractLastLogits(raw);

            inputTensor.close();
            result.close();
            return logits;

        } catch (OrtException e) {
            Log.e(TAG, "Token inference error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Run with multiple named inputs (attention_mask etc.)
     */
    public float[] runWithMask(long[] inputIds, long[] attentionMask) {
        if (!isLoaded) return null;
        try {
            long[] shape = {1, inputIds.length};

            OnnxTensor idsTensor = OnnxTensor.createTensor(
                environment, LongBuffer.wrap(inputIds), shape);
            OnnxTensor maskTensor = OnnxTensor.createTensor(
                environment, LongBuffer.wrap(attentionMask), shape);

            Map<String, OnnxTensor> inputs = new HashMap<>();
            // Try common input names
            inputs.put("input_ids",      idsTensor);
            inputs.put("attention_mask", maskTensor);

            OrtSession.Result result = session.run(inputs);
            OnnxTensor output = (OnnxTensor) result.get(0);
            float[] logits = extractLastLogits(output.getValue());

            idsTensor.close();
            maskTensor.close();
            result.close();
            return logits;

        } catch (OrtException e) {
            Log.e(TAG, "Run with mask error: " + e.getMessage());
            return null;
        }
    }

    // ─── PERSONALITY INFERENCE ────────────────────────────────────────────────

    /**
     * Run personality model to style a response
     * Input: token IDs of prompt
     * Output: logits for next token prediction
     */
    public int predictNextToken(long[] inputIds, float temperature) {
        float[] logits = runTokens("input_ids", inputIds);
        if (logits == null) return -1;

        // Apply temperature
        if (temperature > 0 && temperature != 1.0f) {
            for (int i = 0; i < logits.length; i++) {
                logits[i] /= temperature;
            }
        }

        // Softmax + sample
        return sampleFromLogits(logits);
    }

    /**
     * Generate tokens autoregressively from ONNX model
     */
    public long[] generateTokens(long[] promptIds, int maxNewTokens, float temperature) {
        if (!isLoaded) return null;

        List<Long> generated = new ArrayList<>();
        long[] current = promptIds;

        for (int i = 0; i < maxNewTokens; i++) {
            int next = predictNextToken(current, temperature);
            if (next <= 0) break; // EOS or error

            generated.add((long) next);

            // Extend sequence
            long[] extended = new long[current.length + 1];
            System.arraycopy(current, 0, extended, 0, current.length);
            extended[current.length] = next;
            current = extended;

            // Trim to safe length
            if (current.length > 128) {
                current = Arrays.copyOfRange(current,
                    current.length - 128, current.length);
            }
        }

        long[] result = new long[generated.size()];
        for (int i = 0; i < generated.size(); i++) result[i] = generated.get(i);
        return result;
    }

    // ─── EMBEDDING INFERENCE ──────────────────────────────────────────────────

    /**
     * Get sentence embedding from ONNX embedding model
     * Useful for semantic similarity
     */
    public float[] getEmbedding(long[] tokenIds) {
        if (!isLoaded) return null;
        try {
            long[] shape = {1, tokenIds.length};
            OnnxTensor tensor = OnnxTensor.createTensor(
                environment, LongBuffer.wrap(tokenIds), shape);

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", tensor);

            OrtSession.Result result = session.run(inputs);
            OnnxTensor output = (OnnxTensor) result.get(0);
            float[][][] raw = (float[][][]) output.getValue();

            tensor.close();
            result.close();

            // Mean pooling over sequence
            return meanPool(raw[0]);

        } catch (OrtException e) {
            Log.e(TAG, "Embedding error: " + e.getMessage());
            return null;
        }
    }

    // ─── MODEL INFO ───────────────────────────────────────────────────────────

    public String getModelInfo() {
        if (!isLoaded) return "No model loaded";
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Model: ").append(loadedModelName).append("\n");
            sb.append("Inputs: ");
            for (String name : session.getInputNames()) {
                NodeInfo info = session.getInputInfo().get(name);
                sb.append(name);
                if (info != null) sb.append(info.getInfo().toString());
                sb.append(" ");
            }
            sb.append("\nOutputs: ");
            for (String name : session.getOutputNames()) {
                sb.append(name).append(" ");
            }
            return sb.toString();
        } catch (OrtException e) {
            return "Error getting model info: " + e.getMessage();
        }
    }

    public List<String> listAvailableModels() {
        List<String> models = new ArrayList<>();
        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".onnx")) {
                    models.add(f.getName());
                }
            }
        }
        return models;
    }

    public String getModelPath() { return modelDir.getAbsolutePath(); }
    public boolean isLoaded()    { return isLoaded; }
    public String  getLoadedModelName() { return loadedModelName; }

    // ─── UTILS ────────────────────────────────────────────────────────────────

    private float[] extractLastLogits(Object raw) {
        try {
            if (raw instanceof float[][][]) {
                float[][][] tensor = (float[][][]) raw;
                // Shape [1, seq_len, vocab] → take last position
                int lastPos = tensor[0].length - 1;
                return tensor[0][lastPos];
            } else if (raw instanceof float[][]) {
                float[][] tensor = (float[][]) raw;
                return tensor[0];
            } else if (raw instanceof float[]) {
                return (float[]) raw;
            }
        } catch (Exception e) {
            Log.e(TAG, "Extract logits error: " + e.getMessage());
        }
        return new float[0];
    }

    private float[] meanPool(float[][] hidden) {
        if (hidden.length == 0) return new float[0];
        int dim = hidden[0].length;
        float[] mean = new float[dim];
        for (float[] token : hidden) {
            for (int d = 0; d < dim; d++) mean[d] += token[d];
        }
        for (int d = 0; d < dim; d++) mean[d] /= hidden.length;
        return mean;
    }

    private int sampleFromLogits(float[] logits) {
        // Softmax
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) max = Math.max(max, v);
        float sum = 0;
        float[] probs = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            probs[i] = (float) Math.exp(logits[i] - max);
            sum += probs[i];
        }
        for (int i = 0; i < probs.length; i++) probs[i] /= sum;

        // Sample
        float r = (float) Math.random();
        float cumSum = 0;
        for (int i = 0; i < probs.length; i++) {
            cumSum += probs[i];
            if (r < cumSum) return i;
        }
        return probs.length - 1;
    }

    // ─── CLEANUP ──────────────────────────────────────────────────────────────

    private void closeSession() {
        if (session != null) {
            try { session.close(); } catch (OrtException ignored) {}
            session  = null;
            isLoaded = false;
        }
    }

    public void close() {
        closeSession();
        if (environment != null) {
            try { environment.close(); } catch (OrtException ignored) {}
        }
    }
}
