# Preserve sherpa-onnx JNI classes and native methods
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Preserve Kotlinx Serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.phy4l.tcfy.**$$serializer { *; }
-keepclassmembers class com.phy4l.tcfy.** {
    *** Companion;
}
-keepclasseswithmembers class com.phy4l.tcfy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
