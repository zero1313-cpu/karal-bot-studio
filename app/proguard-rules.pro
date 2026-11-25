# KARAL Bot Studio release rules
-keep class com.quickjs.** { *; }
-keep class com.karal.studio.core.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn com.quickjs.**
