# Veko Browser (`io.veko.browser`)

[![Build Veko APKs](https://github.com/TienHeavenVN/Zeko/actions/workflows/build-apk.yml/badge.svg)](https://github.com/TienHeavenVN/Zeko/actions/workflows/build-apk.yml)

> **"Lướt nhanh. Riêng tư. Mượt."** — *"Browse fast. Stay private. Flow smooth."*

Trình duyệt Android thế hệ mới xây dựng trên **Mozilla GeckoView**, **Kotlin**, **Jetpack Compose**, **Material 3 (Dynamic Color)**, **Hilt (KSP)**, **Room**, **DataStore**, và kiến trúc **Single-Activity + Gesture-First**.

## Điểm nổi bật trong mã nguồn

* **Chuẩn vòng đời GeckoView:** Singleton `BrowserRuntimeManager` dùng `@ApplicationContext` + bảo vệ đa tiến trình (`:tab`, `:gpu`) ở `VekoApplication`.
* **Single-Surface & Single-Active-Session:** Tái sử dụng duy nhất 1 `GeckoView` trong `BrowserScreen`; tự động `releaseSession()` + `setActive(false)` khi chuyển tab hoặc khi ứng dụng xuống nền (`ON_STOP`); giới hạn LRU `MAX_LIVE_SESSIONS = 6` kèm khôi phục lười qua `GeckoSession.SessionState`.
* **UX Cử chỉ một tay (One-handed Gesture Bar):**
  * Vuốt **ngang** trên thanh địa chỉ dưới đáy để đổi tab trước/sau kèm phản hồi xúc giác (haptic).
  * Vuốt **lên** trên thanh địa chỉ để mở ngay khay lưới **Tab Preview Sheet** (ảnh xem trước thu nhỏ `360px` tiết kiệm ~16 lần RAM).
* **Quyền riêng tư mặc định:** Bật sẵn Enhanced Tracking Protection (ETP) + SafeBrowsing + cô lập cookie bên thứ ba; Tab Riêng Tư bật `usePrivateMode(true)` và bị chặn 100% khỏi cả `DataStore` lẫn `Room`.
* **Tối ưu dung lượng gói cài đặt:** Bật sẵn Android App Bundle (`bundle.abi.enableSplit = true`), APK ABI splits (`arm64-v8a`, `armeabi-v7a`, `x86_64`), R8 `fullMode` + `shrinkResources`.

## Cấu trúc thư mục

```text
.
├── .github/workflows/build-apk.yml      # GitHub Actions CI/CD tự động build APK ABI-split & phát hành Release
├── build.gradle                         # Khai báo plugin gốc (AGP 9.1.1, Kotlin 2.3.20, Hilt 2.59.2, KSP 2.3.6)
├── settings.gradle                      # Kho lưu trữ maven.mozilla.org giới hạn nhóm + module :app
├── gradle.properties                    # JVM heap 4GB cho đóng gói native GeckoView + Gradle caching
├── gradlew / gradlew.bat / gradle/      # Gradle Wrapper 9.4.0 chính thức
├── docs/
│   └── VEKO_6_STAGES_BLUEPRINT.md       # Tài liệu kiến trúc 6 giai đoạn, Branding Kit & Self-Audit
└── app/
    ├── build.gradle                     # Java 17, Compose BOM, M3, Hilt (KSP), Room, DataStore, ABI splits
    ├── proguard-rules.pro               # Keep rules cho cầu nối JNI của GeckoView
    └── src/main/
        ├── AndroidManifest.xml          # INTERNET, hardwareAccelerated, singleTask, adjustResize, BROWSABLE
        ├── res/                         # Adaptive Icon Kinetic V-Ribbon + Monochrome Android 13+
        └── java/io/veko/browser/
            ├── VekoApplication.kt       # Hilt Application + kiểm tra tiến trình chính + onTrimMemory
            ├── MainActivity.kt          # Single-Activity Compose + BrowserViewModel + ACTION_VIEW
            ├── di/
            │   └── DatabaseModule.kt    # Hilt Module cung cấp Room VekoDatabase
            ├── engine/
            │   └── BrowserRuntimeManager.kt  # Singleton GeckoRuntime + ContentBlocking ETP
            ├── tab/
            │   ├── Tab.kt               # Data class bất biến Tab, TabSessionState, BrowserTabsUiState
            │   └── TabManager.kt        # Attach/Detach đúng 1 session active, snapshot preview, LRU & DataStore
            ├── data/
            │   ├── local/               # Room Entities, DAOs & VekoDatabase (History & Bookmarks)
            │   └── repository/          # BrowserDataRepository & SettingsRepository (DataStore)
            └── ui/
                ├── BrowserScreen.kt     # AndroidView(GeckoView) + Gesture Bar + Tab/Library/Settings Sheets
                └── theme/               # Material 3 Dynamic Color + bảng màu & Typography Veko
```

## Hướng dẫn Build & Chạy thử

### 1. Tải APK tự động từ GitHub Actions (Không cần cài Android Studio)
Mỗi khi có commit đẩy lên nhánh `main`, `arena/**`, mở Pull Request, hoặc bấm **Run workflow** tại mục **Actions → Build Veko APKs**, GitHub Actions sẽ tự động biên dịch và đính kèm Artifacts gồm:
* `veko-release-arm64-v8a` *(khuyên dùng cho hầu hết điện thoại Android hiện đại — đã tối ưu R8)*
* `veko-release-armeabi-v7a` *(dành cho máy ARM 32-bit đời cũ)*
* `veko-release-x86_64` *(dành cho Android Emulator trên PC / Chromebook)*
* `veko-debug-apks` & `veko-sha256-checksums`
* Khi đẩy tag dạng `v*` (ví dụ `git tag v0.1.0 && git push origin v0.1.0`), workflow tự động tạo **GitHub Release** đính kèm trọn bộ APK + mã băm SHA-256.
* *(Tùy chọn)* Có thể cấu hình 4 GitHub Repository Secrets (`VEKO_KEYSTORE_BASE64`, `VEKO_KEYSTORE_PASSWORD`, `VEKO_KEY_ALIAS`, `VEKO_KEY_PASSWORD`) để ký bằng khóa chính thức; nếu chưa cấu hình, bản Release vẫn được ký tự động bằng debug key để cài thử ngay trên điện thoại.

### 2. Build trực tiếp trên máy phát triển
1. Cài đặt **JDK 17** và **Android SDK 37** (AGP 9.1.1 có thể tự tải SDK components khi cấu hình `ANDROID_HOME`).
2. Kiểm tra phiên bản `org.mozilla.geckoview:geckoview` mới nhất tại:  
   `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/`  
   và cập nhật biến `geckoviewVersion` trong `app/build.gradle` nếu cần.
3. Chạy lệnh build:
   ```bash
   ./gradlew :app:assembleDebug
   # Hoặc xuất APK Release tối ưu R8 / Android App Bundle phát hành:
   ./gradlew :app:assembleRelease
   ./gradlew :app:bundleRelease
   ```
