# Bảo toàn lớp cầu nối JNI gốc để JNI_OnLoad đăng ký RegisterNatives trong bản dựng staging/release bật R8
-keep class io.veko.browser.chromium.nativebridge.VekoChromiumNative {
    *;
}
