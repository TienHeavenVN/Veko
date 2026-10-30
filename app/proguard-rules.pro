# Giữ nguyên các lớp/phương thức cầu nối JNI của GeckoView khi bật R8 fullMode
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.geckoview.**
