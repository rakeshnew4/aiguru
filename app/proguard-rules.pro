# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ── Stack traces ──────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$Companion { *; }

# ── Firestore / Firebase data models ─────────────────────────────────────────
# All classes in the models package are serialized by Firestore — keep them.
-keep class com.example.aiguru.models.** { *; }
-keepclassmembers class com.example.aiguru.models.** {
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
-keep class com.example.aiguru.chat.BlackboardGenerator$BlackboardStep { *; }
-keep class com.example.aiguru.chat.BlackboardGenerator$BlackboardFrame { *; }

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}