"""
Export the actual Java NeuralNetwork to ONNX.

This exporter mirrors the Java implementation instead of substituting
PyTorch's TransformerEncoder. It therefore preserves the learned Java weights.

Usage:
  1. Copy model.bin and vocab.txt from the app's files/aibot_weights directory.
  2. Run: python export_to_onnx.py model.bin vocab.txt aibot_model.onnx
  3. Import aibot_model.onnx from the app's ONNX picker.

Requirements:
  pip install torch onnx onnxruntime numpy
"""

import os
import struct
import sys
import shutil
import numpy as np
import torch
import torch.nn as nn

VOCAB_SIZE=8000
EMBED_DIM=128
NUM_HEADS=4
HEAD_DIM=32
FF_DIM=256
NUM_LAYERS=2
MAX_SEQ_LEN=128
MEMORY_DIM=64

class JavaTransformer(nn.Module):
    def __init__(self):
        super().__init__()
        self.token_embedding=nn.Embedding(VOCAB_SIZE,EMBED_DIM)
        self.pos_embedding=nn.Embedding(MAX_SEQ_LEN,EMBED_DIM)
        self.wq=nn.Parameter(torch.empty(NUM_LAYERS,EMBED_DIM,EMBED_DIM))
        self.wk=nn.Parameter(torch.empty(NUM_LAYERS,EMBED_DIM,EMBED_DIM))
        self.wv=nn.Parameter(torch.empty(NUM_LAYERS,EMBED_DIM,EMBED_DIM))
        self.wo=nn.Parameter(torch.empty(NUM_LAYERS,EMBED_DIM,EMBED_DIM))
        self.w1=nn.Parameter(torch.empty(NUM_LAYERS,EMBED_DIM,FF_DIM))
        self.w2=nn.Parameter(torch.empty(NUM_LAYERS,FF_DIM,EMBED_DIM))
        self.b1=nn.Parameter(torch.empty(NUM_LAYERS,FF_DIM))
        self.b2=nn.Parameter(torch.empty(NUM_LAYERS,EMBED_DIM))
        self.ln1g=nn.Parameter(torch.ones(NUM_LAYERS,EMBED_DIM))
        self.ln1b=nn.Parameter(torch.zeros(NUM_LAYERS,EMBED_DIM))
        self.ln2g=nn.Parameter(torch.ones(NUM_LAYERS,EMBED_DIM))
        self.ln2b=nn.Parameter(torch.zeros(NUM_LAYERS,EMBED_DIM))
        self.wout=nn.Parameter(torch.empty(EMBED_DIM,VOCAB_SIZE))
        self.bout=nn.Parameter(torch.empty(VOCAB_SIZE))
        self.context_in=nn.Parameter(torch.empty(VOCAB_SIZE,MEMORY_DIM))
        self.context_out=nn.Parameter(torch.empty(MEMORY_DIM,VOCAB_SIZE))

    def layer_norm(self,x,g,b):
        return torch.nn.functional.layer_norm(x,(EMBED_DIM,),g,b,1e-5)

    def attention(self,x,l):
        # Java stores Q/K/V as [embed, embed] and slices heads from columns.
        q=torch.matmul(x,self.wq[l]).view(x.shape[0],x.shape[1],NUM_HEADS,HEAD_DIM)
        k=torch.matmul(x,self.wk[l]).view(x.shape[0],x.shape[1],NUM_HEADS,HEAD_DIM)
        v=torch.matmul(x,self.wv[l]).view(x.shape[0],x.shape[1],NUM_HEADS,HEAD_DIM)
        q=q.permute(0,2,1,3)
        k=k.permute(0,2,1,3)
        v=v.permute(0,2,1,3)
        scores=torch.matmul(q,k.transpose(-2,-1))*(HEAD_DIM**-0.5)
        seq=x.shape[1]
        mask=torch.triu(torch.ones(seq,seq,device=x.device,dtype=torch.bool),1)
        scores=scores.masked_fill(mask,-1e9)
        a=torch.softmax(scores,dim=-1)
        out=torch.matmul(a,v).permute(0,2,1,3).contiguous().view(x.shape[0],seq,EMBED_DIM)
        return torch.matmul(out,self.wo[l])

    def forward(self,input_ids):
        seq=input_ids.shape[1]
        positions=torch.arange(seq,device=input_ids.device).clamp(max=MAX_SEQ_LEN-1)
        x=self.token_embedding(input_ids)+self.pos_embedding(positions).unsqueeze(0)
        for l in range(NUM_LAYERS):
            x=self.layer_norm(x+self.attention(x,l),self.ln1g[l],self.ln1b[l])
            h=torch.matmul(x,self.w1[l])+self.b1[l]
            h=torch.relu(h)
            ff=torch.matmul(h,self.w2[l])+self.b2[l]
            x=self.layer_norm(x+ff,self.ln2g[l],self.ln2b[l])
        last=x[:,-1,:]
        safe=input_ids.clamp(0,VOCAB_SIZE-1)
        mem=self.context_in[safe].mean(dim=1)
        logits=torch.matmul(last,self.wout)+self.bout
        logits=logits+0.35*torch.matmul(mem,self.context_out)
        return logits

def read_matrix(f,rows,cols):
    data=np.frombuffer(f.read(rows*cols*4),dtype='>f4').astype(np.float32)
    if data.size!=rows*cols: raise EOFError("Unexpected end of model.bin")
    return torch.from_numpy(data.reshape(rows,cols).copy())

def read_vector(f,n):
    return read_matrix(f,n,1).view(n)

def load_java_weights(model,path):
    with open(path,'rb') as f:
        model.token_embedding.weight.data.copy_(read_matrix(f,VOCAB_SIZE,EMBED_DIM))
        model.pos_embedding.weight.data.copy_(read_matrix(f,MAX_SEQ_LEN,EMBED_DIM))
        for l in range(NUM_LAYERS):
            model.wq.data[l].copy_(read_matrix(f,EMBED_DIM,EMBED_DIM))
            model.wk.data[l].copy_(read_matrix(f,EMBED_DIM,EMBED_DIM))
            model.wv.data[l].copy_(read_matrix(f,EMBED_DIM,EMBED_DIM))
            model.wo.data[l].copy_(read_matrix(f,EMBED_DIM,EMBED_DIM))
            model.w1.data[l].copy_(read_matrix(f,EMBED_DIM,FF_DIM))
            model.w2.data[l].copy_(read_matrix(f,FF_DIM,EMBED_DIM))
            model.b1.data[l].copy_(read_vector(f,FF_DIM))
            model.b2.data[l].copy_(read_vector(f,EMBED_DIM))
            model.ln1g.data[l].copy_(read_vector(f,EMBED_DIM))
            model.ln1b.data[l].copy_(read_vector(f,EMBED_DIM))
            model.ln2g.data[l].copy_(read_vector(f,EMBED_DIM))
            model.ln2b.data[l].copy_(read_vector(f,EMBED_DIM))
        model.wout.data.copy_(read_matrix(f,EMBED_DIM,VOCAB_SIZE))
        model.bout.data.copy_(read_vector(f,VOCAB_SIZE))
        model.context_in.data.copy_(read_matrix(f,VOCAB_SIZE,MEMORY_DIM))
        model.context_out.data.copy_(read_matrix(f,MEMORY_DIM,VOCAB_SIZE))
    return model

def verify(path):
    import onnxruntime as ort
    sess=ort.InferenceSession(path)
    out=sess.run(None,{"input_ids":np.zeros((1,5),dtype=np.int64)})
    print("ONNX output:",out[0].shape)
    return out[0]

def main():
    model_bin=sys.argv[1] if len(sys.argv)>1 else "model.bin"
    vocab=sys.argv[2] if len(sys.argv)>2 else "vocab.txt"
    output=sys.argv[3] if len(sys.argv)>3 else "aibot_model.onnx"
    model=load_java_weights(JavaTransformer().eval(),model_bin)
    dummy=torch.zeros((1,8),dtype=torch.long)
    torch.onnx.export(
        model,dummy,output,
        input_names=["input_ids"],output_names=["logits"],
        dynamic_axes={"input_ids":{0:"batch",1:"seq_len"},"logits":{0:"batch"}},
        opset_version=17,do_constant_folding=True
    )
    if os.path.exists(vocab):
        shutil.copyfile(vocab,os.path.splitext(output)[0]+"_vocab.txt")
    print("Exported",output,os.path.getsize(output)/1024/1024,"MB")
    try: verify(output)
    except Exception as e: print("Verification skipped/failed:",e)

if __name__=="__main__":
    main()
