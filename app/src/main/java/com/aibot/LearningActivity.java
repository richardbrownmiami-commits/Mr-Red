package com.aibot;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;

public class LearningActivity extends AppCompatActivity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView dashboard;
    private Runnable refresher;
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);setContentView(R.layout.activity_learning);
        dashboard=findViewById(R.id.learningDashboard);
        Button chat=findViewById(R.id.learningChatTab);
        Button refresh=findViewById(R.id.learningRefresh);
        if(chat!=null)chat.setOnClickListener(v->{startActivity(new Intent(this,MainActivity.class));finish();});
        if(refresh!=null)refresh.setOnClickListener(v->refresh());
        refresher=()->{refresh();handler.postDelayed(refresher,1000);};
        handler.post(refresher);
    }
    private void refresh(){
        try{
            File root=new File(getFilesDir(),"aibot_weights");
            File model=new File(root,"model.bin"),vocab=new File(root,"vocab.txt"),beliefs=new File(root,"beliefs.bin");
            File mem=new File(getFilesDir(),"aibot_memory/conversation_memory.bin");
            File atoms=new File(getFilesDir(),"aibot_memory");
            File stateFile=new File(getFilesDir(),"aibot_learning/learning.properties");
            LearningState s=new LearningState(new File(getFilesDir(),"aibot_learning"));
            String training=s.isTraining()?"RUNNING":"IDLE";
            int epoch=s.getInt("epoch",0),epochs=s.getInt("totalEpochs",1),step=s.getInt("step",0),total=s.getInt("totalSteps",0),samples=s.getInt("samples",0);
            float loss=s.getFloat("loss",0);
            long modelBytes=model.exists()?model.length():0;
            int vocabCount=countLines(vocab);
            int memoryCount=readMemoryCount(mem);
            String last=s.get("completedAt","never");
            String status=s.get("status","Waiting for first training run");
            StringBuilder x=new StringBuilder();
            x.append("LEARNING STATUS\n").append(training).append("\n\n");
            x.append("Current process\n").append(status).append("\n");
            x.append("Epoch: ").append(epoch).append(" / ").append(epochs).append("\n");
            x.append("Dataset samples: ").append(samples).append("\n");
            x.append("Training steps: ").append(step).append(" / ").append(total).append("\n");
            x.append("Average loss: ").append(String.format(java.util.Locale.US,"%.5f",loss)).append("\n\n");
            x.append("MODEL WEIGHTS\n");
            x.append(model.exists()?"Saved":"Not saved").append("\n");
            x.append("Size: ").append(formatBytes(modelBytes)).append("\n");
            x.append("Vocabulary rows: ").append(vocabCount).append("\n");
            x.append("Weights folder: ").append(root.getAbsolutePath()).append("\n\n");
            x.append("PERSISTENT MEMORY\n");
            x.append("Saved exchanges: ").append(memoryCount).append("\n");
            x.append("Memory file: ").append(mem.exists()?"present":"not created").append("\n");
            x.append("Cognitive store: ").append(atoms.exists()?"present":"not created").append("\n");
            x.append("NARS beliefs file: ").append(beliefs.exists()?formatBytes(beliefs.length()):"not created").append("\n\n");
            x.append("DATA + STATE\n");
            x.append("Learning state: ").append(stateFile.exists()?"saved":"not saved").append("\n");
            x.append("Last completed: ").append(last).append("\n");
            x.append("\nThe model, vocabulary, beliefs, and conversation memory are kept in app-private persistent storage. Reopening the app should load them instead of rebuilding them.");
            dashboard.setText(x.toString());
        }catch(Exception e){dashboard.setText("Learning dashboard error: "+e.getMessage());}
    }
    private int countLines(File f){if(!f.exists())return 0;int n=0;try(BufferedReader r=new BufferedReader(new FileReader(f))){while(r.readLine()!=null)n++;}catch(Exception ignored){}return n;}
    private int readMemoryCount(File f){if(!f.exists())return 0;try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(f)))){in.readInt();return Math.max(0,in.readInt());}catch(Exception e){return 0;}}
    private String formatBytes(long b){if(b<1024)return b+" B";if(b<1024*1024)return (b/1024)+" KB";return String.format(java.util.Locale.US,"%.1f MB",b/1048576f);}
    @Override protected void onDestroy(){super.onDestroy();if(refresher!=null)handler.removeCallbacks(refresher);}
}