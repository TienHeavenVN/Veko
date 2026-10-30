# Veko Browser (`io.veko.browser`)

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
├── build.gradle                         # Kho lưu trữ maven.mozilla.org + khai báo plugin gốc
├── settings.gradle                      # Cấu hình tên dự án Veko và module :app
├── gradle.properties                    # JVM heap 4GB cho đóng gói native GeckoView + R8 fullMode
├── gradlew / gradlew.bat / gradle/      # Gradle Wrapper 8.9 chính thức
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

1. Cài đặt **JDK 17** và **Android SDK 35** (khuyến nghị Android Studio Ladybug trở lên).
2. Kiểm tra phiên bản `org.mozilla.geckoview:geckoview` mới nhất tại:  
   `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/`  
   và cập nhật biến `geckoviewVersion` trong `app/build.gradle` nếu cần.
3. Chạy lệnh build:
   ```bash
   ./gradlew :app:assembleDebug
   # Hoặc xuất Android App Bundle phát hành:
   ./gradlew :app:bundleRelease
   ```
