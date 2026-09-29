package com.aibot;

import java.util.Arrays;

public final class FullBackpropTrainer {
    private final NeuralNetwork nn;
    private static final float LR=0.00035f,CLIP=1.0f,SCALE=1.0f/5.656854249f;
    private static final int MEMORY_DIM=64;
    private static final class C{float[][] input,q,k,v,attended,projected,res1,norm1,preFF,ffHidden,ffOut,res2,out;float[][][] probs;float[] m1,iv1,m2,iv2;}
    public FullBackpropTrainer(NeuralNetwork nn){this.nn=nn;}
    public synchronized float train(int[] inputTokens,int targetToken){
        if(inputTokens==null||inputTokens.length==0||targetToken<0||targetToken>=nn.getActiveVocabSize())return 0f;
        int s=Math.min(inputTokens.length,NeuralNetwork.MAX_SEQ_LEN);int[] ids=inputTokens.length==s?inputTokens:Arrays.copyOfRange(inputTokens,inputTokens.length-s,inputTokens.length);
        float[][] x=new float[s][NeuralNetwork.EMBED_DIM];
        for(int i=0;i<s;i++){int t=safe(ids[i]);for(int d=0;d<NeuralNetwork.EMBED_DIM;d++)x[i][d]=nn.tokenEmbedding[t][d]+nn.posEmbedding[i][d];}
        C[] cs=new C[NeuralNetwork.NUM_LAYERS];for(int l=0;l<NeuralNetwork.NUM_LAYERS;l++){cs[l]=forward(x,l);x=cs[l].out;}
        int av=nn.getActiveVocabSize(),last=s-1;float[] mem=memoryState(ids),logits=new float[av];float max=-Float.MAX_VALUE;
        for(int v=0;v<av;v++){float z=nn.bout[v];for(int d=0;d<NeuralNetwork.EMBED_DIM;d++)z+=x[last][d]*nn.Wout[d][v];for(int d=0;d<MEMORY_DIM;d++)z+=0.35f*mem[d]*nn.contextOut[d][v];logits[v]=z;if(z>max)max=z;}
        float[] p=new float[av];float sum=0;for(int v=0;v<av;v++){p[v]=(float)Math.exp(Math.max(-30f,logits[v]-max));sum+=p[v];}float inv=1f/Math.max(sum,1e-12f);for(int v=0;v<av;v++)p[v]*=inv;
        float loss=-(float)Math.log(Math.max(p[targetToken],1e-10f));p[targetToken]-=1f;float[][] dx=new float[s][NeuralNetwork.EMBED_DIM];
        for(int v=0;v<av;v++){float g=p[v];nn.bout[v]-=LR*clip(g);for(int d=0;d<NeuralNetwork.EMBED_DIM;d++){dx[last][d]+=g*nn.Wout[d][v];nn.Wout[d][v]-=LR*clip(g*x[last][d]);}}
        for(int l=NeuralNetwork.NUM_LAYERS-1;l>=0;l--)dx=backward(cs[l],dx,l);
        for(int i=0;i<s;i++){int t=safe(ids[i]);for(int d=0;d<NeuralNetwork.EMBED_DIM;d++){float g=clip(dx[i][d]);nn.tokenEmbedding[t][d]-=LR*g;nn.posEmbedding[i][d]-=LR*g;}}return loss;
    }
    private C forward(float[][] in,int l){
        int s=in.length,e=NeuralNetwork.EMBED_DIM;C c=new C();c.input=in;c.q=new float[s][e];c.k=new float[s][e];c.v=new float[s][e];c.probs=new float[NeuralNetwork.NUM_HEADS][s][s];c.attended=new float[s][e];c.projected=new float[s][e];c.res1=new float[s][e];c.norm1=new float[s][e];c.preFF=new float[s][NeuralNetwork.FF_DIM];c.ffHidden=new float[s][NeuralNetwork.FF_DIM];c.ffOut=new float[s][e];c.res2=new float[s][e];c.out=new float[s][e];c.m1=new float[s];c.iv1=new float[s];c.m2=new float[s];c.iv2=new float[s];
        for(int i=0;i<s;i++)for(int o=0;o<e;o++){float q=0,k=0,v=0;for(int d=0;d<e;d++){q+=in[i][d]*nn.Wq[l][d][o];k+=in[i][d]*nn.Wk[l][d][o];v+=in[i][d]*nn.Wv[l][d][o];}c.q[i][o]=q;c.k[i][o]=k;c.v[i][o]=v;}
        for(int h=0;h<NeuralNetwork.NUM_HEADS;h++){int st=h*NeuralNetwork.HEAD_DIM;for(int i=0;i<s;i++){float mx=-Float.MAX_VALUE;for(int j=0;j<=i;j++){float dot=0;for(int d=0;d<NeuralNetwork.HEAD_DIM;d++)dot+=c.q[i][st+d]*c.k[j][st+d];float z=dot*SCALE;c.probs[h][i][j]=z;if(z>mx)mx=z;}float tot=0;for(int j=0;j<=i;j++){c.probs[h][i][j]=(float)Math.exp(c.probs[h][i][j]-mx);tot+=c.probs[h][i][j];}float ii=1f/Math.max(tot,1e-12f);for(int j=0;j<=i;j++)c.probs[h][i][j]*=ii;for(int d=0;d<NeuralNetwork.HEAD_DIM;d++){float z=0;for(int j=0;j<=i;j++)z+=c.probs[h][i][j]*c.v[j][st+d];c.attended[i][st+d]=z;}}}
        for(int i=0;i<s;i++){for(int o=0;o<e;o++){float z=0;for(int d=0;d<e;d++)z+=c.attended[i][d]*nn.Wo[l][d][o];c.projected[i][o]=z;c.res1[i][o]=in[i][o]+z;}lnForward(c.res1[i],nn.ln1_gamma[l],nn.ln1_beta[l],c.norm1[i],c.m1,c.iv1,i);}
        for(int i=0;i<s;i++){for(int f=0;f<NeuralNetwork.FF_DIM;f++){float z=nn.b1[l][f];for(int d=0;d<e;d++)z+=c.norm1[i][d]*nn.W1[l][d][f];c.preFF[i][f]=z;c.ffHidden[i][f]=gelu(z);}for(int o=0;o<e;o++){float z=nn.b2[l][o];for(int f=0;f<NeuralNetwork.FF_DIM;f++)z+=c.ffHidden[i][f]*nn.W2[l][f][o];c.ffOut[i][o]=z;c.res2[i][o]=c.norm1[i][o]+z;}lnForward(c.res2[i],nn.ln2_gamma[l],nn.ln2_beta[l],c.out[i],c.m2,c.iv2,i);}return c;
    }
    private float[][] backward(C c,float[][] dout,int l){
        int s=c.input.length,e=NeuralNetwork.EMBED_DIM;float[][] dr2=lnBackward(dout,c.res2,nn.ln2_gamma[l],c.m2,c.iv2,l,true),dn1=copy(dr2);
        for(int i=0;i<s;i++)for(int o=0;o<e;o++)nn.b2[l][o]-=LR*clip(dr2[i][o]);
        for(int i=0;i<s;i++)for(int f=0;f<NeuralNetwork.FF_DIM;f++){float dh=0;for(int o=0;o<e;o++){dh+=dr2[i][o]*nn.W2[l][f][o];nn.W2[l][f][o]-=LR*clip(dr2[i][o]*c.ffHidden[i][f]);}float dz=dh*geluDeriv(c.preFF[i][f]);nn.b1[l][f]-=LR*clip(dz);for(int d=0;d<e;d++){dn1[i][d]+=dz*nn.W1[l][d][f];nn.W1[l][d][f]-=LR*clip(dz*c.norm1[i][d]);}}
        float[][] dr1=lnBackward(dn1,c.res1,nn.ln1_gamma[l],c.m1,c.iv1,l,false),din=copy(dr1),da=new float[s][e];
        for(int i=0;i<s;i++)for(int o=0;o<e;o++){float g=dr1[i][o];for(int d=0;d<e;d++){da[i][d]+=g*nn.Wo[l][d][o];nn.Wo[l][d][o]-=LR*clip(g*c.attended[i][d]);}}
        for(int h=0;h<NeuralNetwork.NUM_HEADS;h++){int st=h*NeuralNetwork.HEAD_DIM;float[][] dq=new float[s][NeuralNetwork.HEAD_DIM],dk=new float[s][NeuralNetwork.HEAD_DIM],dv=new float[s][NeuralNetwork.HEAD_DIM];
            for(int i=0;i<s;i++)for(int j=0;j<=i;j++){float dp=0;for(int d=0;d<NeuralNetwork.HEAD_DIM;d++)dp+=da[i][st+d]*c.v[j][st+d];float dot=0;for(int k=0;k<=i;k++){float dpk=0;for(int d=0;d<NeuralNetwork.HEAD_DIM;d++)dpk+=da[i][st+d]*c.v[k][st+d];dot+=dpk*c.probs[h][i][k];}float ds=c.probs[h][i][j]*(dp-dot)*SCALE;for(int d=0;d<NeuralNetwork.HEAD_DIM;d++){dv[j][d]+=c.probs[h][i][j]*da[i][st+d];dq[i][d]+=ds*c.k[j][st+d];dk[j][d]+=ds*c.q[i][st+d];}}
            for(int i=0;i<s;i++)for(int o=0;o<NeuralNetwork.HEAD_DIM;o++){int col=st+o;for(int d=0;d<e;d++){float gq=dq[i][o],gk=dk[i][o],gv=dv[i][o];din[i][d]+=gq*nn.Wq[l][d][col]+gk*nn.Wk[l][d][col]+gv*nn.Wv[l][d][col];nn.Wq[l][d][col]-=LR*clip(gq*c.input[i][d]);nn.Wk[l][d][col]-=LR*clip(gk*c.input[i][d]);nn.Wv[l][d][col]-=LR*clip(gv*c.input[i][d]);}}}return din;
    }
    private float[][] lnBackward(float[][] dy,float[][] x,float[] gamma,float[] mean,float[] inv,int l,boolean second){
        int s=x.length,e=NeuralNetwork.EMBED_DIM;float[][] dx=new float[s][e];for(int i=0;i<s;i++){float a=0,b=0;for(int d=0;d<e;d++){float xh=(x[i][d]-mean[i])*inv[i],gy=dy[i][d]*gamma[d];a+=gy;b+=gy*xh;if(second){nn.ln2_gamma[l][d]-=LR*clip(dy[i][d]*xh);nn.ln2_beta[l][d]-=LR*clip(dy[i][d]);}else{nn.ln1_gamma[l][d]-=LR*clip(dy[i][d]*xh);nn.ln1_beta[l][d]-=LR*clip(dy[i][d]);}}for(int d=0;d<e;d++){float xh=(x[i][d]-mean[i])*inv[i];dx[i][d]=(gamma[d]*inv[i]/e)*(e*dy[i][d]-a-xh*b);}}return dx;
    }
    private void lnForward(float[] x,float[] gamma,float[] beta,float[] out,float[] mean,float[] inv,int row){float m=0;for(float v:x)m+=v;m/=x.length;float var=0;for(float v:x){float q=v-m;var+=q*q;}var/=x.length;mean[row]=m;inv[row]=1f/(float)Math.sqrt(var+1e-5f);for(int i=0;i<x.length;i++)out[i]=gamma[i]*(x[i]-m)*inv[row]+beta[i];}
    private float[][] copy(float[][] a){float[][] r=new float[a.length][];for(int i=0;i<a.length;i++)r[i]=a[i].clone();return r;}
    private float[] memoryState(int[] ids){float[] r=new float[MEMORY_DIM];int n=0;for(int t:ids){int id=safe(t);for(int d=0;d<MEMORY_DIM;d++)r[d]+=nn.contextIn[id][d];n++;}if(n>0)for(int d=0;d<MEMORY_DIM;d++)r[d]/=n;return r;}
    private int safe(int t){return t>=0&&t<NeuralNetwork.VOCAB_SIZE?t:Tokenizer.UNK_TOKEN;}private float gelu(float x){return x/(1f+(float)Math.exp(-1.702f*x));}private float geluDeriv(float x){float s=1f/(1f+(float)Math.exp(-1.702f*x));return s+1.702f*x*s*(1f-s);}private float clip(float x){return x>CLIP?CLIP:(x<-CLIP?-CLIP:x);}
}