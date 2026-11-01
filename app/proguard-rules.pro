# Giữ nguyên các lớp cầu nối JNI C++20 và tiến trình con của Chromium Embedder khi tối ưu R8
-keep class io.veko.browser.chromium.nativebridge.** { *; }
-keep class io.veko.browser.chromium.embedder.** { *; }
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

# Giữ nguyên các lớp/phương thức cầu nối JNI của GeckoView khi bật R8 fullMode
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.geckoview.**

# Giữ thông tin package cho SnakeYAML nội bộ của GeckoView tránh lỗi NullPointerException dưới R8 fullMode
-keep,allowoptimization,allowshrinking class org.yaml.snakeyaml.TypeDescription {}
-keep,allowoptimization,allowshrinking class org.yaml.snakeyaml.introspector.PropertySubstitute {}
