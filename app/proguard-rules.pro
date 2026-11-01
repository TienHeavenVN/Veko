# Giữ nguyên các lớp cầu nối JNI cho VekoAdblockNative (Rust adblock-rust FFI) khi tối ưu R8
-keep class io.veko.browser.privacy.nativebridge.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Giữ nguyên các lớp/phương thức cầu nối JNI của Mozilla GeckoView khi bật R8 fullMode
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.geckoview.**

# Giữ thông tin package cho SnakeYAML nội bộ của GeckoView tránh lỗi NullPointerException dưới R8 fullMode
-keep,allowoptimization,allowshrinking class org.yaml.snakeyaml.TypeDescription {}
-keep,allowoptimization,allowshrinking class org.yaml.snakeyaml.introspector.PropertySubstitute {}
