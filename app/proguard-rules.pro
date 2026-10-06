# seed-mas release rules

# ML Kit — keep only scanner packages that may be accessed through generated/runtime components.
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# CameraX — keep public use-case/view surfaces; CameraX ships its own consumer rules.
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }

# org.json is used directly by backup/restore code.
-keep class org.json.** { *; }

# Kotlin metadata and runtime-visible annotations.
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Application classes referenced by Android and runtime callbacks.
-keep public class com.srooyesh.seedcounter.MainActivity { *; }
-keep public class com.srooyesh.seedcounter.CameraActivity { *; }
-keep public class com.srooyesh.seedcounter.ExportPreviewActivity { *; }
-keep public class com.srooyesh.seedcounter.FeedbackController { *; }
-keep class * implements android.speech.tts.TextToSpeech$OnInitListener { *; }
