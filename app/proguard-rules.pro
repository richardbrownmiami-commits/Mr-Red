# Preserve JNI bridge methods/classes referenced from native code.
-keep class com.arick.androidlocalllmlab.NativeLlmBridge { *; }
-keep class com.arick.androidlocalllmlab.LocalLlmRuntime { *; }
