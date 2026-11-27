package com.arick.androidlocalllmlab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
class LocalLlmRuntime { @Volatile private var loaded=false
 suspend fun load(path:String,contextSize:Int=512)=withContext(Dispatchers.IO){check(!loaded);loaded=NativeLlmBridge.load(path,contextSize);loaded}
 suspend fun answer(prompt:String,maxTokens:Int=128):String=withContext(Dispatchers.IO){check(loaded){"Load a GGUF model first"};NativeLlmBridge.generate(prompt,maxTokens)}
 suspend fun unload()=withContext(Dispatchers.IO){if(loaded){NativeLlmBridge.unload();loaded=false}}
 val isLoaded get()=loaded }