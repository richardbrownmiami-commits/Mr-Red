package com.aibot;

import java.io.*;
import java.util.*;

/**
 * Android-safe AtomSpace-style knowledge graph.
 * This is a small local Java implementation of the useful AtomSpace ideas:
 * unique typed atoms, relations, indexed lookup and persistence.
 * It does not bundle the native OpenCog C++ AtomSpace, which is not an Android dependency.
 */
public class AtomSpaceLite {
    public static class Atom {
        public String subject;
        public String relation;
        public String object;
        public float truth;
        public long updated;

        Atom(String s, String r, String o, float t) {
            subject=s; relation=r; object=o; truth=t; updated=System.currentTimeMillis();
        }

        public String readable() { return subject + " " + relation + " " + object; }
    }

    private final File file;
    private final Map<String, Atom> atoms = new LinkedHashMap<>();

    public AtomSpaceLite(File baseDir) {
        if (!baseDir.exists()) baseDir.mkdirs();
        file = new File(baseDir, "atomspace.bin");
        load();
    }

    public synchronized void add(String subject, String relation, String object, float truth) {
        if (subject == null || relation == null || object == null) return;
        subject=clean(subject); relation=clean(relation); object=clean(object);
        if (subject.isEmpty() || relation.isEmpty() || object.isEmpty()) return;
        String key=subject+"\u0001"+relation+"\u0001"+object;
        Atom a=atoms.get(key);
        if (a==null) atoms.put(key,new Atom(subject,relation,object,Math.max(0f,Math.min(1f,truth))));
        else { a.truth=Math.max(a.truth,truth); a.updated=System.currentTimeMillis(); }
        while (atoms.size()>5000) atoms.remove(atoms.keySet().iterator().next());
        save();
    }

    public synchronized List<Atom> query(String text, int limit) {
        String q=text==null?"":text.toLowerCase(Locale.US);
        List<Atom> out=new ArrayList<>();
        for (Atom a: atoms.values()) {
            String s=a.readable().toLowerCase(Locale.US);
            if (q.isEmpty() || s.contains(q) || q.contains(a.subject.toLowerCase(Locale.US))
                    || q.contains(a.object.toLowerCase(Locale.US))) out.add(a);
        }
        out.sort((x,y)->Float.compare(y.truth,x.truth));
        return out.subList(0,Math.min(limit,out.size()));
    }

    public synchronized int size(){return atoms.size();}

    public synchronized String stats(){return "AtomSpace-lite: "+atoms.size()+" atoms";}

    public synchronized void learnSentence(String sentence) {
        if (sentence==null) return;
        String s=sentence.trim().replaceAll("[.!?]+$","");
        String lower=s.toLowerCase(Locale.US);
        String[] patterns={" is "," are "," has "," needs "," uses "," can "," causes "};
        for(String p:patterns){
            int i=lower.indexOf(p);
            if(i>1 && i+p.length()<lower.length()-1){
                add(s.substring(0,i).trim(),p.trim(),s.substring(i+p.length()).trim(),0.65f);
                return;
            }
        }
    }

    private String clean(String s){return s.trim().replaceAll("\\s+"," ");}

    private synchronized void save(){
        File tmp=new File(file.getParentFile(),file.getName()+".part");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))){
            out.writeInt(1); out.writeInt(atoms.size());
            for(Atom a:atoms.values()){
                out.writeUTF(a.subject); out.writeUTF(a.relation); out.writeUTF(a.object);
                out.writeFloat(a.truth); out.writeLong(a.updated);
            }
            out.flush();
            if(!tmp.renameTo(file)){if(file.exists())file.delete();tmp.renameTo(file);}
        }catch(Exception ignored){}
    }

    private void load(){
        if(!file.exists())return;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            in.readInt(); int n=Math.min(in.readInt(),5000);
            for(int i=0;i<n;i++){
                String s=in.readUTF(),r=in.readUTF(),o=in.readUTF();
                float t=in.readFloat(); in.readLong();
                atoms.put(s+"\u0001"+r+"\u0001"+o,new Atom(s,r,o,t));
            }
        }catch(Exception ignored){atoms.clear();}
    }
}
