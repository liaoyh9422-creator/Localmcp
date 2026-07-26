# Keep reandroid merge engine
-keep class com.reandroid.** { *; }
-keep class com.android.apksig.** { *; }
-dontwarn com.reandroid.**
-dontwarn com.android.apksig.**

# Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-keep class com.apkstoapk.app.mcp.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
-dontwarn rikka.sui.**