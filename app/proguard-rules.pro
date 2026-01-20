# Chaquopy: a minimal ProGuard configuration.
# See https://chaquo.com/chaquopy/doc/current/android.html#proguard.

-keep class com.chaquo.python.** { *; }
-keep class com.chaquo.python.runtime.** { *; }

# GPU ASTC Compression - 供 Python/Chaquopy 透過 JNI 調用
-keep class com.example.bd2modmanager.gpu.AstcCompressorBridge { *; }
-keep class com.example.bd2modmanager.gpu.GpuAstcEncoder { *; }
-keep class com.example.bd2modmanager.gpu.GpuCapability { *; }