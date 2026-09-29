package com.aibot;
import java.io.*;
import java.util.Properties;
public final class LearningState {
    private final File file;private final Properties p=new Properties();
    public LearningState(File dir){if(!dir.exists())dir.mkdirs();file=new File(dir,"learning.properties");load();}
    private synchronized void load(){if(!file.exists())return;try(FileInputStream in=new FileInputStream(file)){p.load(in);}catch(Exception ignored){}}
    public synchronized void update(String status,int epoch,int totalEpochs,int step,int totalSteps,float loss,int samples,boolean training){
        p.setProperty("status",status==null?"":status);p.setProperty("epoch",String.valueOf(epoch));p.setProperty("totalEpochs",String.valueOf(totalEpochs));p.setProperty("step",String.valueOf(step));p.setProperty("totalSteps",String.valueOf(totalSteps));p.setProperty("loss",String.valueOf(loss));p.setProperty("samples",String.valueOf(samples));p.setProperty("training",String.valueOf(training));p.setProperty("updatedAt",String.valueOf(System.currentTimeMillis()));if(!training)p.setProperty("completedAt",String.valueOf(System.currentTimeMillis()));save();}
    public synchronized void markIdle(String status){p.setProperty("status",status==null?"Idle":status);p.setProperty("training","false");p.setProperty("updatedAt",String.valueOf(System.currentTimeMillis()));save();}
    private synchronized void save(){File tmp=new File(file.getParentFile(),file.getName()+".part");try(FileOutputStream out=new FileOutputStream(tmp)){p.store(out,"AIBot learning state");out.flush();if(!tmp.renameTo(file)){if(file.exists())file.delete();tmp.renameTo(file);}}catch(Exception ignored){}}
    public synchronized String get(String k,String d){return p.getProperty(k,d);}public synchronized int getInt(String k,int d){try{return Integer.parseInt(p.getProperty(k));}catch(Exception e){return d;}}public synchronized float getFloat(String k,float d){try{return Float.parseFloat(p.getProperty(k));}catch(Exception e){return d;}}public synchronized boolean isTraining(){return Boolean.parseBoolean(p.getProperty("training","false"));}public File getFile(){return file;}
}