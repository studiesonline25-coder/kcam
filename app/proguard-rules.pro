# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Keep Xposed classes
-keep class de.robv.android.xposed.** { *; }
-keep class com.virtucam.hooks.** { *; }

# Keep module class
-keep class com.virtucam.ModuleMain { *; }

# FFmpegKit - ignore missing smart-exception classes during R8 shrinking
-dontwarn com.arthenica.smartexception.**
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**
