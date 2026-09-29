package com.aibot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class HostPretrainer {
    private static final int EPOCHS = 5;
    private static final int MAX_SAMPLES = 1000;
    private static final Pattern INPUT = Pattern.compile("\"input\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern OUTPUT = Pattern.compile("\"output\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }
    private static List<String[]> load(Path file) throws Exception {
        List<String[]> out = new ArrayList<>();
        for(String line:Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher a=INPUT.matcher(line), b=OUTPUT.matcher(line);
            if(a.find()&&b.find()) out.add(new String[]{unescape(a.group(1)),unescape(b.group(1))});
        }
        return out;
    }
    private static void addAll(Tokenizer t,List<String[]> rows){ for(String[] r:rows){t.learnFromText(r[0]);t.learnFromText(r[1]);} }
    public static void main(String[] args) throws Exception {
        if(args.length<3) throw new IllegalArgumentException("usage: HostPretrainer <outDir> <corpus1> <corpus2>");
        Path out=Paths.get(args[0]); Files.createDirectories(out);
        List<String[]> rows=new ArrayList<>(); rows.addAll(load(Paths.get(args[1]))); rows.addAll(load(Paths.get(args[2])));
        if(rows.isEmpty()) throw new IllegalStateException("No training samples");
        if(rows.size()>MAX_SAMPLES) rows=new ArrayList<>(rows.subList(0,MAX_SAMPLES));
        Tokenizer tok=new Tokenizer(); addAll(tok,rows);
        NeuralNetwork nn=new NeuralNetwork(); nn.setActiveVocabSize(tok.getVocabSize());
        FullBackpropTrainer trainer=new FullBackpropTrainer(nn);
        Random shuffle=new Random(20260929L);
        long steps=0; double total=0;
        for(int epoch=1;epoch<=EPOCHS;epoch++){
            Collections.shuffle(rows,shuffle); double epochLoss=0; long epochSteps=0;
            for(String[] r:rows){
                int[] in=tok.encode(r[0],true,false), outIds=tok.encode(r[1],false,true);
                int[] full=new int[in.length+outIds.length]; System.arraycopy(in,0,full,0,in.length); System.arraycopy(outIds,0,full,in.length,outIds.length);
                for(int i=Math.max(0,in.length-1);i<full.length-1;i++){
                    float loss=trainer.train(Arrays.copyOfRange(full,0,i+1),full[i+1]);
                    epochLoss+=loss; total+=loss; epochSteps++; steps++;
                }
            }
            System.out.printf(Locale.US,"epoch %d/%d samples=%d steps=%d loss=%.6f%n",epoch,EPOCHS,rows.size(),steps,epochLoss/Math.max(1,epochSteps));
        }
        nn.saveWeights(out.resolve("model.bin").toFile()); tok.saveVocab(out.resolve("vocab.txt").toFile());
        Files.writeString(out.resolve("TRAINED.txt"),String.format(Locale.US,"epochs=%d samples=%d steps=%d average_loss=%.6f%n",EPOCHS,rows.size(),steps,total/Math.max(1,steps)),StandardCharsets.UTF_8);
        System.out.println("PRETRAINED model="+out.resolve("model.bin")+" vocab="+tok.getVocabSize());
    }
}