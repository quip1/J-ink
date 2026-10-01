# Readable stack traces; shrinking is where the size win is.
-dontobfuscate

# Onyx pen/device SDK: reflection into itself and the framework, JNI callbacks, EventBus.
-keep class com.onyx.** { *; }
-keep interface com.onyx.** { *; }
-keepclassmembers class * { @org.greenrobot.eventbus.Subscribe <methods>; }
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
-keep class com.tencent.mmkv.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# PdfBox-Android loads resources and font classes reflectively.
-keep class com.tom_roush.** { *; }

# Hidden API bypass works through reflection and Unsafe.
-keep class org.lsposed.hiddenapibypass.** { *; }

# Our own code is small; keep it whole so nothing is stripped out from under the SDK callbacks.
-keep class dev.slate.notes.** { *; }

-dontwarn **
-ignorewarnings
