# Giữ nguyên các lớp tiến trình con và cầu nối Surface của Chromium Embedder khi tối ưu R8
-keep class io.veko.browser.chromium.embedder.** { *; }
-dontwarn org.chromium.**
-dontwarn org.jni_zero.**
