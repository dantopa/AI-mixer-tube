# The AI DJ in a minified (release / profile) build.
# ONNX Runtime binds its Java classes from native code (JNI), which R8 cannot see.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
# The DJ's own classes are small; keeping them avoids surprises from reflection (Koin, serialization of stored analyses).
-keep class org.simpmusic.dj.** { *; }
