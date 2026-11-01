# Giữ nguyên toàn bộ lớp/phương thức cầu nối JNI C++20 và chú thích JNI của Chromium khi tối ưu R8
-keep class io.veko.browser.chromium.nativebridge.** { *; }
-keep class io.veko.browser.chromium.embedder.VekoSandboxedProcessService* { *; }
-keep class io.veko.browser.chromium.embedder.VekoPrivilegedProcessService* { *; }

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep @org.jni_zero.CalledByNative class * { *; }
-keepclassmembers class * {
    @org.jni_zero.CalledByNative <methods>;
}
-keep @org.chromium.base.annotations.CalledByNative class * { *; }
-keepclassmembers class * {
    @org.chromium.base.annotations.CalledByNative <methods>;
}
-dontwarn org.chromium.**
-dontwarn org.jni_zero.**
