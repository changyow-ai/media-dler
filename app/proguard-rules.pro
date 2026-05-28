# yt-dlp / youtubedl-android relies on reflection into bundled Python; keep it intact.
-keep class com.yausername.** { *; }

# kotlinx.serialization generated serializers.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
