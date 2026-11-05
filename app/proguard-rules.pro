# Native JNI entry points are exported by name from bridge.cpp. Keep the class and
# external methods stable so R8 cannot rename/remove their JNI symbols.
-keep class com.arick.androidlocalllmlab.NativeLlmBridge { *; }

# bridge.cpp resolves these callback methods at runtime with GetMethodID.
-keep interface com.arick.androidlocalllmlab.NativeGenerationCallback { *; }
