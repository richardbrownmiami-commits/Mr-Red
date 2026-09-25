package com.aibot;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import ai.onnxruntime.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.util.*;

public class OnnxEngine {
    private static final String TAG = "OnnxEngine";
    private Context ctx;
    private WeightManager wm;
    private OrtEnvironment env;
    private OrtSession sessionMini, sessionYolo;
    private File modelDir;
    private String loadedName = "none";
    private String[] LABELS = {"person","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light","fire hydrant","stop sign","bench","bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack","umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple","sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair","couch","potted plant","bed","dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone","microwave","oven","toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear","hair drier","toothbrush"};

    // FIX 1: 2 constructors
    public OnnxEngine(Context ctx, WeightManager wm) {
        this.ctx = ctx; this.wm = wm;
        this.modelDir = new File(ctx.getExternalFilesDir(null), "AIBot/models");
        modelDir.mkdirs();
        try { env = OrtEnvironment.getEnvironment(); } catch (Exception e) {}
    }
    public OnnxEngine(Context ctx) { this(ctx, null); }

    // FIX 2: OrtSession.SessionOptions
    private OrtSession.SessionOptions turboOpts() throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        opts.setInterOpNumThreads(1);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        return opts;
    }

    // FIX 3: Methods needed by MainActivity
    public String getModelPath(){ return modelDir.getAbsolutePath(); }
    public String getLoadedModelName(){ return loadedName; }
    public List<String> listAvailableModels(){
        List<String> l=new ArrayList<>();
        File[] fs=modelDir.listFiles();
        if(fs!=null) for(File f:fs) if(f.getName().endsWith(".onnx")) l.add(f.getName());
        if(l.isEmpty()){ l.add("minilm.onnx"); l.add("yolo.onnx"); }
        return l;
    }
    public boolean loadModel(String n){
        loadedName=n;
        if(n.contains("yolo")) return loadYolo();
        return loadText();
    }
    public long[] generateTokens(long[] ids,int max,float t){ return ids; }
    public boolean load(String name){ return loadModel(name); }

    // TEXT 22MB
    public boolean loadText(){
        try{
            File f=new File(modelDir,"minilm.onnx");
            if(!f.exists()) dl("https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx",f);
            if(sessionMini!=null) sessionMini.close();
            sessionMini=env.createSession(f.getAbsolutePath(),turboOpts());
            loadedName="minilm.onnx"; return true;
        }catch(Exception e){ Log.e(TAG,e.getMessage()); return false; }
    }

    // YOLO 6MB
    public boolean loadYolo(){
        try{
            File f=new File(modelDir,"yolo.onnx");
            if(!f.exists()) dl("https://huggingface.co/ultralytics/yolov8n/resolve/main/yolov8n.onnx",f);
            if(sessionYolo!=null) sessionYolo.close();
            sessionYolo=env.createSession(f.getAbsolutePath(),turboOpts());
            loadedName="yolo.onnx"; return true;
        }catch(Exception e){ Log.e(TAG,e.getMessage()); return false; }
    }

    // EMBED for memory
    public float[] embed(String text){ return embedText(text); }
    public float[] embedText(String txt){
        try{
            if(sessionMini==null) loadText();
            long[] ids=tok(txt,128);
            long[][] in=new long[1][128]; long[][] m=new long[1][128];
            System.arraycopy(ids,0,in[0],0,ids.length); Arrays.fill(m[0],1);
            OnnxTensor t1=OnnxTensor.createTensor(env,in);
            OnnxTensor t2=OnnxTensor.createTensor(env,m);
            Map<String,OnnxTensor> mp=new HashMap<>(); mp.put("input_ids",t1); mp.put("attention_mask",t2);
            OrtSession.Result r=sessionMini.run(mp);
            float[][][] out=(float[][][])r.get(0).getValue();
            float[] avg=new float[384]; for(int i=0;i<128;i++) for(int j=0;j<384;j++) avg[j]+=out[0][i][j]; for(int j=0;j<384;j++) avg[j]/=128f;
            t1.close(); t2.close(); r.close(); return avg;
        }catch(Exception e){ return null; }
    }

    // VISION YOLO
    public String detectToString(Bitmap bmp){
        try{
            if(sessionYolo==null) loadYolo();
            float[] inp=pre(bmp);
            OnnxTensor t=OnnxTensor.createTensor(env,FloatBuffer.wrap(inp),new long[]{1,3,640,640});
            Map<String,OnnxTensor> mp=new HashMap<>(); mp.put("images",t);
            OrtSession.Result r=sessionYolo.run(mp);
            float[][][] out=(float[][][])r.get(0).getValue(); t.close();
            StringBuilder sb=new StringBuilder();
            for(int i=0;i<8400;i++){float best=0; int cls=-1; for(int c=4;c<84;c++){float cf=out[0][c][i]; if(cf>best){best=cf; cls=c-4;}} if(best>0.5f) sb.append(LABELS[Math.min(cls,LABELS.length-1)]).append(" ").append((int)(best*100)).append("%, ");}
            r.close(); if(sb.length()==0) return "I see nothing"; return "I see: "+sb.toString();
        }catch(Exception e){ return "Vision error"; }
    }

    private float[] pre(Bitmap bmp){Bitmap rs=Bitmap.createScaledBitmap(bmp,640,640,true); float[] out=new float[3*640*640]; int[] px=new int[640*640]; rs.getPixels(px,0,640,0,0,640,640); for(int i=0;i<px.length;i++){int p=px[i]; out[i]=((p>>16&0xFF)/255f); out[640*640+i]=((p>>8&0xFF)/255f); out[2*640*640+i]=((p&0xFF)/255f);} return out;}
    private long[] tok(String s,int n){long[] a=new long[n]; String[] w=s.toLowerCase().split("\\s+"); for(int i=0;i<Math.min(w.length,n);i++) a[i]=Math.abs(w[i].hashCode()%30000)+1; return a;}
    private void dl(String u,File d){try{HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection(); c.setConnectTimeout(30000); InputStream in=c.getInputStream(); FileOutputStream o=new FileOutputStream(d); byte[] b=new byte[8192]; int l; while((l=in.read(b))!=-1) o.write(b,0,l); o.close(); in.close();}catch(Exception e){}}
    public void close(){try{if(sessionMini!=null) sessionMini.close(); if(sessionYolo!=null) sessionYolo.close();}catch(Exception e){}}
    }
