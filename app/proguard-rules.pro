# Giữ nguyên các lớp/phương thức cầu nối JNI của GeckoView khi bật R8 fullMode
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.geckoview.**

# Giữ thông tin package cho SnakeYAML nội bộ của GeckoView tránh lỗi NullPointerException dưới R8 fullMode
-keep,allowoptimization,allowshrinking class org.yaml.snakeyaml.TypeDescription {}
-keep,allowoptimization,allowshrinking class org.yaml.snakeyaml.introspector.PropertySubstitute {}
