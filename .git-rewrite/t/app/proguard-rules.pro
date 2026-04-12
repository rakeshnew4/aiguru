# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ── Stack traces ──────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Strip all Log calls from release builds ───────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$Companion { *; }

# ── Firestore / Firebase data models ─────────────────────────────────────────
# All classes in the models package are serialized by Firestore — keep them.
-keep class com.aiguruapp.student.models.** { *; }
-keepclassmembers class com.aiguruapp.student.models.** {
    public <init>();
    <fields>;
}

# ── Firebase / Google Play Services ──────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── OkHttp / networking ───────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Gson (used by AdminConfig, etc.) ─────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }

# ── App-specific keep rules ───────────────────────────────────────────────────
# Firestore uses @PropertyName — keep annotated fields
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
}

# BlackboardGenerator data classes (passed across boundaries)
-keep class com.aiguruapp.student.chat.BlackboardGenerator$BlackboardStep { *; }
-keep class com.aiguruapp.student.chat.BlackboardGenerator$BlackboardFrame { *; }

# ── Razorpay ──────────────────────────────────────────────────────────────────
-keepclassmembers class * {
    @com.razorpay.** *;
}
-keep class com.razorpay.** { *; }
-dontwarn com.razorpay.**
-keep class proguard.annotation.Keep { *; }
-keep class proguard.annotation.KeepClassMembers { *; }

# ── UCrop ─────────────────────────────────────────────────────────────────────
-dontwarn com.yalantis.ucrop.**
-keep class com.yalantis.ucrop.** { *; }

# ── Glide ─────────────────────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── DataStore ─────────────────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ── AndroidSVG ────────────────────────────────────────────────────────────────
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# ── iText PDF ─────────────────────────────────────────────────────────────────
-dontwarn com.itextpdf.**
-keep class com.itextpdf.** { *; }

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}