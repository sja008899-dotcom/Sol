# R8 / ProGuard keep rules for Sol release builds

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Native JNI methods must never be renamed or stripped
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Room Database, Entities, FTS4, DAOs & Migrations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Fts4 class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class com.example.sol.memory.** { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# Vosk & JNA (native libvosk / libjna-dispatch)
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Structure { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    public <fields>;
}
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# LiteRT-LM JNI
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# ONNX Runtime Android (Piper TTS + Speaker ID)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# PDFBox-Android & FontBox
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.commons.logging.**

# Jsoup HTML parser
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# AndroidX Security Crypto / Tink
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# WorkManager Workers instantiated via reflection
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.example.sol.background.** { *; }

# Keep B10 SLM JSON proposal data models & enum names referenced by String
-keep class com.example.sol.brain.SolAction { *; }
-keep class com.example.sol.brain.SlmIntentProposal { *; }
-keep class com.example.sol.tts.PiperConfig { *; }

# Coroutines & Kotlin reflection safety
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
