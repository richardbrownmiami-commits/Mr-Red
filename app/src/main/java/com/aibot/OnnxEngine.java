package com.aibot;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Optional ONNX support.
 *
 * ONNX is deliberately disabled by default so the app can start on small
 * ARMv7 devices. It is enabled from the existing Menu -> Load ONNX Model
 * action. No ONNX native environment is created while disabled.
 */
public class OnnxEngine {
    private static final String TAG = "OnnxEngine";
    private static final String PREFS = "onnx_settings";
    private static final String ENABLED = "enabled";
    private static final String ENABLE_ACTION = "__enable_onnx__";

    private final Context ctx;
    private final WeightManager wm;
    private final File modelDir;
    private OrtEnvironment env;
    private OrtSession sessionMini;
    private OrtSession sessionYolo;
    private OrtSession sessionGeneric;
    private String genericModelName = null;
    private String loadedName = "none";
    private final Map<String, Integer> bertVocab = new LinkedHashMap<>();

    public OnnxEngine(Context ctx, WeightManager wm) {
        this.ctx = ctx.getApplicationContext();
        this.wm = wm;
        File external = this.ctx.getExternalFilesDir(null);
        this.modelDir = new File(
            external != null ? external : this.ctx.getFilesDir(),
            "AIBot/models"
        );
        modelDir.mkdirs();
        // Important: do not call OrtEnvironment.getEnvironment() here.
    }

    public OnnxEngine(Context ctx) {
        this(ctx, null);
    }

    /**
     * Activates the bundled semantic ONNX model. It is used for semantic
     * retrieval/routing only; it is not used as a chat generator.
     */
    public boolean activateEmbeddedSemanticModel() {
        try {
            setEnabled(true);
            File model = new File(modelDir, "minilm.onnx");
            File vocab = new File(modelDir, "minilm-vocab.txt");
            if (!model.exists()) copyAsset("embedded_onnx/minilm.onnx", model);
            if (!vocab.exists()) copyAsset("embedded_onnx/vocab.txt", vocab);
            if (!model.exists() || !vocab.exists()) return false;
            // Keep ONNX Runtime/session creation lazy. Copying the bundled model is cheap;\n            // constructing the ~23 MB semantic session at every app launch is not.\n            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Embedded semantic ONNX activation failed", e);
            return false;
        }
    }

    public boolean isSemanticModelLoaded() {
        return sessionMini != null;
    }

    public boolean isSemanticModelAvailable() {
        return isEnabled() && new File(modelDir, "minilm.onnx").exists() &&
               new File(modelDir, "minilm-vocab.txt").exists();
    }

    private void copyAsset(String assetPath, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = ctx.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.getFD().sync();
        }
    }

    public boolean isEnabled() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply();
        if (!enabled) close();
    }

    private boolean ensureEnvironment() {
        if (!isEnabled()) return false;
        if (env != null) return true;
        try {
            env = OrtEnvironment.getEnvironment();
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "ONNX Runtime is unavailable on this device", e);
            env = null;
            return false;
        }
    }

    private OrtSession.SessionOptions options() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(1);
        options.setInterOpNumThreads(1);
        // XNNPACK is included by the Android package and is optimized for
        // ARM floating-point inference. Keep ORT's own threadpool small.
        options.addXnnpack(new HashMap<String, String>());
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        return options;
    }

    public String getModelPath() {
        return modelDir.getAbsolutePath();
    }

    public String getLoadedModelName() {
        return loadedName;
    }

    public boolean isGenerativeModelLoaded() {
        return sessionGeneric != null;
    }

    /** Models visible in the ONNX picker. */
    public List<String> listAvailableModels() {
        List<String> models = new ArrayList<>();

        if (!isEnabled()) {
            models.add(ENABLE_ACTION);
        }

        File[] files = modelDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase().endsWith(".onnx")
                        && !models.contains(file.getName())) {
                    models.add(file.getName());
                }
            }
        }

        // Downloadable built-in models.
        if (!models.contains("minilm.onnx")) models.add("minilm.onnx");
        if (!models.contains("yolo.onnx")) models.add("yolo.onnx");

        models.add("Import ONNX from storage");
        models.add("Disable ONNX Runtime");
        return models;
    }

    public boolean loadModel(String name) {
        if (ENABLE_ACTION.equals(name)) {
            setEnabled(true);
            loadedName = "enabled (no model loaded)";
            return true;
        }
        if ("Disable ONNX Runtime".equals(name) ||
            "Import ONNX from storage".equals(name)) {
            if ("Disable ONNX Runtime".equals(name)) {
                setEnabled(false);
                loadedName = "none";
                return true;
            }
            return false;
        }
        if (!isEnabled() || name == null || name.trim().isEmpty()) return false;

        String lower = name.toLowerCase();
        if (lower.contains("yolo")) return loadYolo();

        if (lower.equals("minilm.onnx")) return loadText();

        return loadGeneric(name);
    }

    public boolean load(String name) {
        return loadModel(name);
    }

    public boolean loadText() {
        if (!ensureEnvironment()) return false;
        try {
            File file = new File(modelDir, "minilm.onnx");
            if (!file.exists()) {
                download(
                    "https://huggingface.co/onnx-models/all-MiniLM-L6-v2-onnx/resolve/main/model.onnx",
                    file
                );
            }
            File vocabFile = new File(modelDir, "minilm-vocab.txt");
            if (!vocabFile.exists()) {
                download(
                    "https://huggingface.co/onnx-models/all-MiniLM-L6-v2-onnx/resolve/main/vocab.txt",
                    vocabFile
                );
            }
            loadBertVocab(vocabFile);
            if (sessionMini != null) sessionMini.close();
            sessionMini = env.createSession(file.getAbsolutePath(), options());
            loadedName = "minilm.onnx";
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Unable to load text ONNX model", e);
            return false;
        }
    }

    public boolean loadYolo() {
        if (!ensureEnvironment()) return false;
        try {
            File file = new File(modelDir, "yolo.onnx");
            if (!file.exists()) {
                download(
                    "https://huggingface.co/ultralytics/yolov8n/resolve/main/yolov8n.onnx",
                    file
                );
            }
            if (sessionYolo != null) sessionYolo.close();
            if (sessionGeneric != null) sessionGeneric.close();
            sessionYolo = env.createSession(file.getAbsolutePath(), options());
            loadedName = "yolo.onnx";
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Unable to load vision ONNX model", e);
            return false;
        }
    }

    /**
     * Run a generative ONNX model whose output is vocabulary logits.
     * Embedding/detection models are not treated as text generators.
     */
    public long[] generateTokens(long[] ids, int max, float temperature) {
        if (!isEnabled() || !ensureEnvironment() || sessionGeneric == null || ids == null)
            return ids;

        try {
            long[] current = Arrays.copyOf(ids, Math.min(ids.length, 128));
            int steps = Math.max(1, Math.min(max, 64));

            for (int step = 0; step < steps; step++) {
                long[][] input = new long[][] { current };
                OnnxTensor tensor = OnnxTensor.createTensor(env, input);
                Map<String, OnnxTensor> values = new HashMap<>();
                values.put("input_ids", tensor);

                OrtSession.Result result = sessionGeneric.run(values);
                Object raw = result.get(0).getValue();
                float[] logits = extractLastLogits(raw);

                tensor.close();
                result.close();

                if (logits == null || logits.length == 0) return current;

                int next = argmax(logits, temperature);
                long[] expanded = Arrays.copyOf(current, current.length + 1);
                expanded[expanded.length - 1] = next;
                current = expanded;

                // Common EOS ids for the small custom models.
                if (next == 2 || next == 3) break;
            }
            return current;
        } catch (Throwable e) {
            Log.e(TAG, "ONNX generation failed", e);
            return ids;
        }
    }

    private float[] extractLastLogits(Object raw) {
        if (raw instanceof float[][]) {
            float[][] a = (float[][]) raw;
            return a.length == 0 ? null : a[0];
        }
        if (raw instanceof float[][][]) {
            float[][][] a = (float[][][]) raw;
            if (a.length == 0 || a[0].length == 0) return null;
            return a[0][a[0].length - 1];
        }
        if (raw instanceof float[]) return (float[]) raw;
        return null;
    }

    private int argmax(float[] logits, float temperature) {
        float scale = temperature > 0f ? temperature : 1f;
        int best = 0;
        float bestValue = logits[0] / scale;
        for (int i = 1; i < logits.length; i++) {
            float v = logits[i] / scale;
            if (v > bestValue) {
                bestValue = v;
                best = i;
            }
        }
        return best;
    }

    private boolean loadGeneric(String name) {
        if (!ensureEnvironment()) return false;
        try {
            File file = new File(modelDir, name);
            if (!file.exists()) return false;

            if (sessionGeneric != null) sessionGeneric.close();
            sessionGeneric = env.createSession(file.getAbsolutePath(), options());
            genericModelName = name;
            loadedName = name;
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Unable to load generic ONNX model", e);
            return false;
        }
    }

    /**
     * Copy an ONNX model selected from Android storage into the app model folder.
     * Android 11+ scoped storage does not require broad storage permission for this.
     */
    public String importModel(Uri uri) {
        if (uri == null) return null;
        try {
            String name = "imported_model.onnx";
            String path = uri.getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < path.length()) {
                    String candidate = path.substring(slash + 1);
                    if (candidate.toLowerCase().endsWith(".onnx")) name = candidate;
                }
            }

            File destination = new File(modelDir, name);
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(destination)) {
                if (in == null) return null;
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            return destination.getName();
        } catch (Throwable e) {
            Log.e(TAG, "Unable to import ONNX model", e);
            return null;
        }
    }

    public long getModelSize(String name) {
        if (name == null) return 0;
        File f = new File(modelDir, name);
        return f.exists() ? f.length() : 0;
    }

    public float[] embed(String text) {
        return embedText(text);
    }

    public float[] embedText(String text) {
        if (!isEnabled() || !ensureEnvironment()) return null;
        try {
            if (sessionMini == null && !loadText()) return null;
            TokenizedBert tok = tokenizeBert(text, 128);
            long[][] input = new long[][] { tok.ids };
            long[][] mask = new long[][] { tok.mask };

            OnnxTensor inputTensor = OnnxTensor.createTensor(env, input);
            OnnxTensor maskTensor = OnnxTensor.createTensor(env, mask);
            OnnxTensor typeTensor = null;
            Map<String, OnnxTensor> values = new HashMap<>();
            values.put("input_ids", inputTensor);
            if (sessionMini.getInputNames().contains("attention_mask")) values.put("attention_mask", maskTensor);
            if (sessionMini.getInputNames().contains("token_type_ids")) {
                values.put("token_type_ids", typeTensor = OnnxTensor.createTensor(env, new long[][] { new long[128] }));
            }

            OrtSession.Result result = sessionMini.run(values);
            Object raw = result.get(0).getValue();
            float[][][] output = raw instanceof float[][][] ? (float[][][]) raw : null;
            if (output == null || output.length == 0) return null;
            int dim = output[0][0].length;
            float[] average = new float[dim];
            float denom = 0f;
            for (int i = 0; i < output[0].length; i++) {
                if (tok.mask[i] == 0) continue;
                for (int j = 0; j < dim; j++) average[j] += output[0][i][j];
                denom += 1f;
            }
            if (denom > 0f) for (int j = 0; j < dim; j++) average[j] /= denom;
            inputTensor.close();
            maskTensor.close();
            if (typeTensor != null) typeTensor.close();
            result.close();
            return average;
        } catch (Throwable e) {
            Log.e(TAG, "ONNX embedding failed", e);
            return null;
        }
    }

    public String detectToString(Bitmap bitmap) {
        return "Vision is unavailable until a YOLO ONNX model is loaded.";
    }

    private static class TokenizedBert {
        long[] ids;
        long[] mask;
        TokenizedBert(long[] ids, long[] mask) { this.ids=ids; this.mask=mask; }
    }

    private void loadBertVocab(File file) throws IOException {
        if (!bertVocab.isEmpty()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line; int id=0;
            while ((line=r.readLine()) != null) {
                bertVocab.put(line.trim(), id++);
            }
        }
        if (!bertVocab.containsKey("[CLS]") || !bertVocab.containsKey("[SEP]"))
            throw new IOException("Invalid MiniLM WordPiece vocabulary");
    }

    private TokenizedBert tokenizeBert(String text, int count) {
        long[] ids = new long[count];
        long[] mask = new long[count];
        Arrays.fill(ids, bertVocab.getOrDefault("[PAD]", 0).longValue());
        List<Integer> pieces = new ArrayList<>();
        pieces.add(bertVocab.getOrDefault("[CLS]", 101));
        String normalized = text == null ? "" : text.toLowerCase(java.util.Locale.US)
            .replaceAll("([.,!?;:()])", " $1 ").replaceAll("\\s+"," ").trim();
        if (!normalized.isEmpty()) {
            for (String word : normalized.split(" ")) {
                if (pieces.size() >= count - 1) break;
                pieces.addAll(wordPieceIds(word));
            }
        }
        pieces.add(bertVocab.getOrDefault("[SEP]", 102));
        int n=Math.min(pieces.size(),count);
        for(int i=0;i<n;i++){ids[i]=pieces.get(i);mask[i]=1L;}
        return new TokenizedBert(ids,mask);
    }

    private List<Integer> wordPieceIds(String word) {
        List<Integer> out=new ArrayList<>();
        if(word.isEmpty()) return out;
        Integer direct=bertVocab.get(word);
        if(direct!=null){out.add(direct);return out;}
        int start=0;
        while(start<word.length()){
            int end=word.length();
            int best=-1;
            while(start<end){
                String sub=word.substring(start,end);
                if(start>0) sub="##"+sub;
                Integer id=bertVocab.get(sub);
                if(id!=null){best=id;break;}
                end--;
            }
            if(best<0){out.add(bertVocab.getOrDefault("[UNK]",100));break;}
            out.add(best);
            start = end;
        }
        return out;
    }

    private void download(String address, File destination) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "AIBot/1.0");

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException("Model download HTTP " + code);
        }

        File temp = new File(destination.getParentFile(), destination.getName() + ".part");
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }

        if (!temp.renameTo(destination)) {
            if (destination.exists()) destination.delete();
            if (!temp.renameTo(destination))
                throw new IllegalStateException("Could not finalize model file");
        }
    }

    public void close() {
        try {
            if (sessionMini != null) sessionMini.close();
            if (sessionYolo != null) sessionYolo.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing ONNX sessions", e);
        }
        sessionMini = null;
        sessionYolo = null;
        sessionGeneric = null;
        genericModelName = null;
        env = null;
        loadedName = "none";
    }
}
