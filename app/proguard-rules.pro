# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.tcgscanner.offline.**$$serializer { *; }
-keepclassmembers class com.tcgscanner.offline.** { *** Companion; }
-keepclasseswithmembers class com.tcgscanner.offline.** { kotlinx.serialization.KSerializer serializer(...); }
# LiteRT / TFLite optional GPU delegate classes
-dontwarn org.tensorflow.lite.gpu.**
# ONNX Runtime Mobile uses JNI and reflection from native code
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
