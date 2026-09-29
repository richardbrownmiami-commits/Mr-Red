package com.aibot;

import android.util.Log;
import java.util.*;

public class SelfLearner {
    private static final String TAG="SelfLearner";
    private static final int TRAIN_EPOCHS=1, MAX_DATASET_SAMPLES=600, SAVE_INTERVAL=25;
    private final NeuralNetwork nn; private final Tokenizer tokenizer; private final NARSEngine nars;
    private final WeightManager weightManager; private final CognitiveMemory memory; private final AtomSpaceLite atomSpace;
    private final FullBackpropTrainer trainer; private final LearningState state;
    private volatile float totalLoss=0; private volatile int trainSteps=0; private volatile boolean isTraining=false;

    public interface LearningCallback {
        void onProgress(int step,int total,float loss,String status);
        void onComplete(float avgLoss,int steps);
        void onError(String error);
    }

    public SelfLearner(NeuralNetwork n,Tokenizer t,NARSEngine na,WeightManager w){this(n,t,na,w,null,null,null);}
    public SelfLearner(NeuralNetwork n,Tokenizer t,NARSEngine na,WeightManager w,CognitiveMemory m,AtomSpaceLite a){this(n,t,na,w,m,a,null);}
    public SelfLearner(NeuralNetwork n,Tokenizer t,NARSEngine na,WeightManager w,CognitiveMemory m,AtomSpaceLite a,LearningState s){
        nn=n;tokenizer=t;nars=na;weightManager=w;memory=m;atomSpace=a;trainer=new FullBackpropTrainer(nn);state=s;
    }

    public void learnFromMessage(String user,String bot){
        new Thread(()->{try{tokenizer.learnFromText(user);tokenizer.learnFromText(bot);trainOnPair(user,bot);
            if(memory!=null)memory.remember(user,bot,"conversation");if(trainSteps%SAVE_INTERVAL==0)weightManager.saveAll();
        }catch(Exception e){Log.e(TAG,"conversation learning",e);}},"AIBot-ConversationLearner").start();
    }

    public synchronized float trainOnPair(String input,String output){
        tokenizer.learnFromText(input+" "+output);nn.setActiveVocabSize(tokenizer.getVocabSize());
        int[] in=tokenizer.encode(input,true,false),out=tokenizer.encode(output,false,true),full=concat(in,out);
        float total=0;int count=0;
        for(int i=in.length;i<full.length-1;i++){int target=full[i+1];if(target<0||target>=NeuralNetwork.VOCAB_SIZE)continue;
            float loss=trainer.train(Arrays.copyOfRange(full,0,i+1),target);total+=loss;count++;trainSteps++;totalLoss+=loss;}
        return count>0?total/count:0f;
    }

    public void learnFromDataset(List<DatasetLoader.TrainingSample> samples,LearningCallback cb){
        if(isTraining){if(cb!=null)cb.onError("Already training");return;}
        final List<DatasetLoader.TrainingSample> data=new ArrayList<>(samples.subList(0,Math.min(samples.size(),MAX_DATASET_SAMPLES)));
        new Thread(()->{
            isTraining=true;int total=data.size()*TRAIN_EPOCHS,step=0;float epochLoss=0;
            stateUpdate("Preparing training data",0,TRAIN_EPOCHS,0,total,0,data.size(),true);
            try{
                for(int epoch=0;epoch<TRAIN_EPOCHS;epoch++){
                    Collections.shuffle(data,new Random(1000+epoch));
                    for(DatasetLoader.TrainingSample s:data){
                        tokenizer.learnFromText(s.input);tokenizer.learnFromText(s.output);nn.setActiveVocabSize(tokenizer.getVocabSize());
                        if(atomSpace!=null){atomSpace.learnSentence(s.input);atomSpace.learnSentence(s.output);if(s.context!=null&&!s.context.isEmpty())atomSpace.learnSentence(s.context);}
                        float loss=trainOnPair(s.input,s.output);epochLoss+=loss;step++;float avg=epochLoss/step;
                        stateUpdate("Training neural network",epoch+1,TRAIN_EPOCHS,step,total,avg,data.size(),true);
                        if(cb!=null&&(step%2==0||step==total))cb.onProgress(step,total,avg,"Epoch "+(epoch+1)+"/"+TRAIN_EPOCHS);
                        if(memory!=null)memory.remember(s.input,s.output,"dataset");if(step%SAVE_INTERVAL==0)weightManager.saveAll();
                    }
                }
                if(memory!=null)memory.flush();weightManager.saveAll();float avg=step>0?epochLoss/step:0;
                stateUpdate("Training complete",TRAIN_EPOCHS,TRAIN_EPOCHS,step,total,avg,data.size(),false);if(cb!=null)cb.onComplete(avg,step);
            }catch(Throwable e){
                Log.e(TAG,"training error",e);stateUpdate("Training error: "+e.getClass().getSimpleName(),0,TRAIN_EPOCHS,step,total,step>0?epochLoss/step:0,data.size(),false);
                if(cb!=null)cb.onError(String.valueOf(e.getMessage()));
            }finally{isTraining=false;}
        },"AIBot-FullBackpropTrainer").start();
    }

    public void learnFromWebResults(List<String> facts){
        new Thread(()->{for(String fact:facts){if(fact==null||fact.length()<2)continue;tokenizer.learnFromText(fact);nn.setActiveVocabSize(tokenizer.getVocabSize());
            if(atomSpace!=null)atomSpace.learnSentence(fact);if(fact.length()>10){int[] t=tokenizer.encode(fact);
                for(int i=0;i<t.length-1;i++){trainer.train(Arrays.copyOfRange(t,0,i+1),t[i+1]);trainSteps++;}}}weightManager.saveAll();
        },"AIBot-WebLearner").start();
    }

    private void stateUpdate(String status,int epoch,int totalEpochs,int step,int total,float loss,int samples,boolean training){
        if(state!=null)state.update(status,epoch,totalEpochs,step,total,loss,samples,training);
    }
    public float getAverageLoss(){return trainSteps>0?totalLoss/trainSteps:0f;}
    public int getTrainSteps(){return trainSteps;}
    public boolean isTraining(){return isTraining;}
    private int[] concat(int[] a,int[] b){int[] r=new int[a.length+b.length];System.arraycopy(a,0,r,0,a.length);System.arraycopy(b,0,r,a.length,b.length);return r;}
}