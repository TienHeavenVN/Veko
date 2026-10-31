# Veko Browser (`io.veko.browser`)

[![Build Veko APKs](https://github.com/TienHeavenVN/Zeko/actions/workflows/build-apk.yml/badge.svg)](https://github.com/TienHeavenVN/Zeko/actions/workflows/build-apk.yml)
[![Build Veko Official Website](https://github.com/TienHeavenVN/Zeko/actions/workflows/website.yml/badge.svg)](https://github.com/TienHeavenVN/Zeko/actions/workflows/website.yml)

> **"Lướt nhanh. Riêng tư. Mượt."** — *"Browse fast. Stay private. Flow smooth."*

Trình duyệt Android xây dựng trên **Mozilla GeckoView**, **Kotlin**, **Jetpack Compose**, **Material 3 (Dynamic Color)**, **Hilt (KSP)**, **Room**, **DataStore**, và kiến trúc **Single-Activity + Gesture-First**.

## Điểm nổi bật trong mã nguồn

* **Chuẩn vòng đời GeckoView:** Singleton `BrowserRuntimeManager` dùng `@ApplicationContext` + bảo vệ đa tiến trình (`:tab`, `:gpu`) ở `VekoApplication` + tự thu dọn session khi runtime dừng đột ngột.
* **Single-Surface, Single-Active-Session & Trần bộ nhớ LRU hai tầng:** Tái sử dụng duy nhất 1 `GeckoView` trong `BrowserScreen`; tự động `releaseSession()` + `setActive(false)` khi chuyển tab hoặc xuống nền; duy trì nghiêm ngặt giới hạn `MAX_LIVE_SESSIONS = 6` kể cả khi mở tab/popup liên tục lúc chưa tuần tự hóa `SessionState`, nhưng vẫn bảo toàn đầy đủ URL và metadata để khôi phục lười.
* **UX Cử chỉ một tay (One-handed Gesture Bar):**
  * Vuốt **ngang** trên thanh địa chỉ dưới đáy để đổi tab trước/sau kèm phản hồi xúc giác (haptic).
  * Vuốt **lên** trên thanh địa chỉ để mở ngay khay lưới **Tab Preview Sheet** (ảnh xem trước thu nhỏ `360px`).
* **Bảo mật điều hướng & Quyền riêng tư:**
  * Bật sẵn Enhanced Tracking Protection (ETP) + SafeBrowsing + cô lập cookie bên thứ ba.
  * Kiểm soát scheme theo ngữ cảnh (`UrlNavigationPolicy`), chặn chuyển hướng `data:`/`blob:` trái phép, escape đầy đủ ký tự đặc biệt/bidi và gắn CSP khóa script cho trang lỗi nội bộ.
  * Tab Riêng Tư bật `usePrivateMode(true)`, không ghi lịch sử vào `Room`, không lưu vào `DataStore` và không lưu nháp Omnibox vào `savedInstanceState`.
  * **Chính sách Sao lưu (Android Backup):** Cấu hình allowlist chặt trong `backup_rules.xml` (API 26–30) và `data_extraction_rules.xml` (API 31–36+) **chỉ** cho phép sao lưu cài đặt giao diện/công cụ tìm kiếm (`veko_user_settings.preferences_pb`), loại trừ toàn bộ lịch sử duyệt web, phiên tab đang mở và hồ sơ/cookie của GeckoView.
* **Tối ưu dung lượng gói cài đặt:** Bật sẵn Android App Bundle (`bundle.abi.enableSplit = true`), APK ABI splits (`arm64-v8a`, `armeabi-v7a`, `x86_64`), R8 minification + `shrinkResources`.

## Phân biệt 3 loại bản dựng APK (Quan trọng)

Veko tách biệt nghiêm ngặt khóa ký phát triển/thử nghiệm và khóa ký phát hành chính thức (không bao giờ dùng debug key cho bản `release`):

| Phân loại APK | Package ID (`applicationId`) | Tên hiển thị | Khóa ký & Tối ưu | Mục đích sử dụng |
| :--- | :--- | :--- | :--- | :--- |
| **APK Debug** (`app-*-debug.apk`) | `io.veko.browser.debug` | `Veko Debug` | Debug key, không bật R8 | Phát triển & gỡ lỗi cục bộ / kiểm tra nhanh từ CI (`veko-debug-apks`). |
| **APK Thử nghiệm / Preview** (`app-*-preview.apk`) | `io.veko.browser.preview` | `Veko Preview` | Debug key (chỉ dành cho bản `.preview`), **có bật R8 + `shrinkResources`** | Cài thử bản tối ưu hiệu năng/dung lượng trực tiếp từ GitHub Actions CI (`veko-preview-*`), cài song song không xung đột chữ ký với bản chính thức. |
| **APK Phát hành chính thức** (`app-*-release.apk`) | `io.veko.browser` | `Veko` | **Bắt buộc** ký bằng Release Keystore riêng + xác minh `apksigner` | Phát hành chính thức trên GitHub Releases từ tag `v*` thuộc nhánh `main`. Sẽ **báo lỗi dừng build ngay** nếu thiếu bất kỳ thông tin khóa ký nào. |

## Hướng dẫn Kiểm thử, Build & Phát hành

### 1. Kiểm thử tự động (Unit + Android Emulator) & Tải APK Thử nghiệm trên CI
Mỗi khi có commit hoặc Pull Request, GitHub Actions thực thi song song 2 job kiểm chứng độc lập (quyền tối thiểu `contents: read`, không truy cập khóa ký phát hành):
* **`ci-verify`**: Chạy toàn bộ JUnit 4 Unit Tests + Android Lint, xác minh chốt chặn khóa ký `verifyReleaseSigningConfig`, đóng gói & kiểm tra chữ ký `apksigner` / `sha256sum`, và tải lên:
  * `veko-preview-arm64-v8a` *(Bản thử nghiệm R8 cho hầu hết điện thoại Android hiện đại — cài dưới tên **Veko Preview**)*
  * `veko-preview-armeabi-v7a` *(Bản thử nghiệm R8 cho thiết bị ARM 32-bit)*
  * `veko-preview-x86_64` *(Bản thử nghiệm R8 cho Android Emulator / Chromebook)*
  * `veko-debug-apks` & `veko-ci-sha256-checksums`
* **`emulator-test`**: Sử dụng [`reactivecircus/android-emulator-runner`](https://github.com/marketplace/actions/android-emulator-runner) (đã pin commit SHA + bật tăng tốc phần cứng KVM trên Linux) khởi chạy máy ảo **Android 14 (API 34, `x86_64`)** để chạy `:app:connectedDebugAndroidTest` trực tiếp trên native `GeckoRuntime` (`libxul.so`), xác minh:
  * Khởi động `GeckoRuntime`, tiến trình con `:tab`, render trang web thật vào `GeckoView` trong Compose và lưu lịch sử `Room`.
  * Tái tạo `MainActivity` (`Activity.recreate()`) không nhân đôi tab `ACTION_VIEW` và gắn lại `GeckoView` an toàn.
  * Giữ trần cứng `MAX_LIVE_SESSIONS = 6` khi mở tab dồn dập và cô lập 100% dữ liệu Private Mode khỏi hệ thống tệp Android (`DataStore` & `SQLite`).
  * Xuất báo cáo HTML/XML tại artifact `veko-emulator-test-reports`.

### 2. Quy trình Phát hành chính thức (Job `release-official`)
Job `release-official` chỉ kích hoạt khi **cả `ci-verify` lẫn `emulator-test` đều PASS**, đồng thời push tag `v*` trỏ vào commit đã thuộc nhánh `main` và yêu cầu cấu hình đủ **4 GitHub Repository Secrets**:
* `VEKO_KEYSTORE_BASE64`: Nội dung file `.jks` mã hóa Base64 (`base64 -w 0 release.jks`).
* `VEKO_KEYSTORE_PASSWORD`: Mật khẩu mở kho khóa.
* `VEKO_KEY_ALIAS`: Tên alias của khóa ký phát hành.
* `VEKO_KEY_PASSWORD`: Mật khẩu của khóa ký theo alias.

Workflow tự động kiểm tra tính hợp lệ của keystore, từ chối chứng chỉ `CN=Android Debug`, đối chiếu SHA-256 certificate fingerprint giữa `keytool` và `apksigner`, kiểm tra `SHA256SUMS.txt` trước khi công bố GitHub Release.

### 3. Lệnh Build & Kiểm thử trên máy phát triển
1. Cài đặt **JDK 17** và **Android SDK 37** (Gradle `9.4.0` + AGP `9.1.1`).
2. Kiểm tra phiên bản `org.mozilla.geckoview:geckoview` mới nhất tại `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/`.
3. Chạy kiểm thử và đóng gói:
   ```bash
   # Chạy toàn bộ Unit Test hồi quy & Android Lint
   ./gradlew :app:testDebugUnitTest :app:lintDebug

   # Chạy bộ kiểm thử tích hợp trên Android Emulator / Thiết bị thật đang kết nối ADB
   ./gradlew :app:connectedDebugAndroidTest

   # Xuất APK Debug hoặc APK Thử nghiệm R8 (không cần khóa phát hành)
   ./gradlew :app:assembleDebug
   ./gradlew :app:assemblePreview

   # Xuất APK / AAB Phát hành chính thức (bắt buộc cung cấp đủ biến môi trường khóa ký)
   VEKO_KEYSTORE_FILE=/path/to/release.jks \
   VEKO_KEYSTORE_PASSWORD=... \
   VEKO_KEY_ALIAS=... \
   VEKO_KEY_PASSWORD=... \
   ./gradlew :app:assembleRelease
   ```

### 4. Website Giới thiệu Chính thức (`website/`)
Thư mục [`website/`](./website/README.md) chứa trang web tĩnh độc lập của Veko Browser xây dựng bằng **Astro 7**, **TypeScript**, **Tailwind CSS v4** và **Lucide Icons** (0 script theo dõi, 0 quảng cáo, 0 CDN ngoài):
```bash
cd website
npm ci
npm run verify
```

