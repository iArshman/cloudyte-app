# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line numbers for debugging stack traces.
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

# Keep Kotlin Metadata so Moshi KotlinJsonAdapterFactory and Retrofit suspend functions work under R8
-keep class kotlin.Metadata { *; }

# Keep Moshi generated adapters and @JsonClass annotated models
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class **JsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
    *;
}

# Keep all API models, DTOs, Room entities, and security envelopes
-keep class com.cloudyte.app.api.** { *; }
-keep class com.cloudyte.app.data.remote.** { *; }
-keep class com.cloudyte.app.db.** { *; }
-keep class com.cloudyte.app.core.security.** { *; }
-keep class com.cloudyte.app.domain.model.** { *; }

# Retrofit 2 & OkHttp 4 rules
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

