# ProGuard & R8 Optimization Rules for APK Size Reduction

# Keep data models and Entities
-keep class com.example.data.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.* <fields>;
    @com.squareup.moshi.* <methods>;
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Retrofit API interfaces
-keep interface com.example.data.api.** { *; }

# Keep Room Database classes
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp references optional TLS providers that are not on the classpath.
# They are only used if present at runtime, so suppressing is correct here.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

