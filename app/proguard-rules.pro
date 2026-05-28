# yt-dlp / youtubedl-android drives a bundled Python runtime via reflection;
# keep its API surface and silence warnings from its optional deps.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# youtubedl-android bundles Jackson for its (unused-by-us) VideoInfo mapper.
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.**

# kotlinx.serialization generated serializers.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
