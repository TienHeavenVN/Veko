# Veko Browser (`io.veko.browser`)

[![Build Veko APKs](https://github.com/TienHeavenVN/Zeko/actions/workflows/build-apk.yml/badge.svg)](https://github.com/TienHeavenVN/Zeko/actions/workflows/build-apk.yml)
[![Build Veko Official Website](https://github.com/TienHeavenVN/Zeko/actions/workflows/website.yml/badge.svg)](https://github.com/TienHeavenVN/Zeko/actions/workflows/website.yml)

> **"Lướt nhanh. Riêng tư. Mượt."** — *"Browse fast. Stay private. Flow smooth."*

Trình duyệt Android mã nguồn mở (`MPL-2.0`) vận hành thuần túy trên nhân **Mozilla GeckoView (`150.0.20260511200624`)**, xây dựng bằng **Kotlin**, **Jetpack Compose**, **Material 3 (Dynamic Color)**, **Hilt (KSP)**, **Room**, **DataStore**, **C++20 JNI (`libveko_adblock_jni.so`)** và **Rust (`brave/adblock-rust 0.13.4`)**.

> 🌐 **Website chính thức:** [`https://veko.kdns.fr/`](https://veko.kdns.fr/)
> 📘 **Tài liệu kiến trúc chi tiết:** Xem [`docs/GECKOVIEW_ARCHITECTURE.md`](./docs/GECKOVIEW_ARCHITECTURE.md) để nắm rõ kiến trúc 7 module thuần GeckoView, quản lý vòng đời `GeckoRuntime` / `GeckoSession`, tích hợp lá chắn Rust `adblock-rust` + WebExtension nội bộ và trạng thái kiểm toán F-Droid.

## Điểm nổi bật trong mã nguồn

* **Kiến trúc thuần Mozilla GeckoView (`io.veko.browser.engine.gecko.*`):**
  * Sử dụng duy nhất gói chính thức `org.mozilla.geckoview:geckoview:150.0.20260511200624` từ `maven.mozilla.org` (Rendering: **Gecko / WebRender**, JS: **SpiderMonkey** — tuyệt đối không dùng `android.webkit.WebView` hệ thống).
  * Khởi tạo lười và chia sẻ duy nhất một `GeckoRuntime` trên toàn ứng dụng (`BrowserRuntimeManager`), tách riêng `GeckoSession` cho từng tab và đóng/giải phóng sạch mọi delegate khi đóng tab hoặc hủy Activity để chống rò rỉ bộ nhớ.
* **Lá chắn Quyền riêng tư 3 lớp (`:privacy` + `veko-shield`):**
  * **Lớp 1 — Rust `adblock-rust` (`0.13.4`) qua C++20 JNI (`libveko_adblock_jni.so`):** Biên dịch trực tiếp từ nguồn bằng Cargo (`--locked`) và Android NDK Clang++ đạt chuẩn căn lề trang nhớ ELF 16 KB; lọc điều hướng, tính toán CSS ẩn khung quảng cáo và cắt bỏ 44+ tham số theo dõi trên URL (`utm_*`, `fbclid`, `gclid`, `ttclid`, ...).
  * **Lớp 2 — GeckoView Built-in WebExtension (`veko-shield@veko.browser`):** Kiểm tra tài nguyên con HTTP/HTTPS qua `webRequest.onBeforeRequest` (với cơ chế an toàn fail-open 450 ms) và tiêm quy tắc ẩn phần tử quảng cáo thẩm mỹ.
  * **Lớp 3 — GeckoView Strict ContentBlocking:** Bật sẵn Strict Enhanced Tracking Protection (ETP), cô lập cookie bên thứ ba động (dFPI), Global Privacy Control (`Sec-GPC: 1`) và tự động từ chối biểu ngữ cookie.
* **Single-Surface, Single-Active-Session & Trần bộ nhớ LRU 6 phiên:** Tái sử dụng một bề mặt `GeckoView` duy nhất qua `WeakReference`; tự động `releaseSession()` + `setActive(false)` khi chuyển tab hoặc xuống nền; duy trì nghiêm ngặt giới hạn `MAX_LIVE_SESSIONS = 6` và xử lý `onTrimMemory`.
* **UX Cử chỉ một tay & Đa ngôn ngữ (8 ngôn ngữ):**
  * Vuốt **ngang** trên thanh địa chỉ dưới đáy để đổi tab trước/sau kèm phản hồi xúc giác (haptic); vuốt **lên** để mở ngay khay lưới **Tab Preview Sheet**.
  * Hỗ trợ 8 ngôn ngữ giao diện (Anh, Việt, Nhật, Hàn, Trung giản thể, Pháp, Đức, Tây Ban Nha), tự xóa dữ liệu khi thoát (`Clear-on-Exit`), chặn tự phát âm thanh (`Autoplay`), bộ chọn tệp tải lên chuẩn SAF và nhập/xuất Dấu trang HTML/JSON.
* **Cô lập Tab Riêng Tư & Chính sách Sao lưu (Android Backup):**
  * Tab Riêng Tư bật `usePrivateMode(true)` + cờ cửa sổ `FLAG_SECURE`, không ghi lịch sử vào `Room`, không lưu vào `DataStore` và không lưu nháp Omnibox vào `savedInstanceState`.
  * `backup_rules.xml` (API 26–30) và `data_extraction_rules.xml` (API 31–36+) chỉ cho phép sao lưu tệp cài đặt giao diện, loại trừ toàn bộ lịch sử duyệt web, phiên tab đang mở và hồ sơ/cookie của GeckoView.
* **Tối ưu dung lượng gói cài đặt:** Bật sẵn Android App Bundle (`bundle.abi.enableSplit = true`), APK ABI splits (`arm64-v8a`, `armeabi-v7a`, `x86_64`), R8 minification + `shrinkResources` và tắt khối siêu dữ liệu `dependenciesInfo`.

## Phân biệt các loại bản dựng APK (Quan trọng)

Veko tách biệt nghiêm ngặt khóa ký phát triển/thử nghiệm, bản chưa ký cho F-Droid và khóa ký phát hành chính thức:

| Phân loại APK | Package ID (`applicationId`) | Tên hiển thị | Khóa ký & Tối ưu | Mục đích sử dụng |
| :--- | :--- | :--- | :--- | :--- |
| **APK Debug** (`app-*-debug.apk`) | `io.veko.browser.debug` | `Veko Debug` | Debug key, không bật R8 | Phát triển & gỡ lỗi cục bộ / kiểm tra nhanh từ CI (`veko-debug-apks`). |
| **APK Thử nghiệm / Preview** (`app-*-preview.apk`) | `io.veko.browser.preview` | `Veko Preview` | Debug key (chỉ dành cho bản `.preview`), **có bật R8 + `shrinkResources`** | Cài thử bản tối ưu hiệu năng/dung lượng trực tiếp từ GitHub Actions CI (`veko-preview-*`), cài song song không xung đột chữ ký với bản chính thức. |
| **APK Phát hành chính thức** (`app-*-release.apk`) | `io.veko.browser` | `Veko` | **Bắt buộc** ký bằng Release Keystore riêng + xác minh `apksigner` | Phát hành chính thức trên GitHub Releases từ tag `v*` thuộc nhánh `main`. Sẽ **báo lỗi dừng build ngay** nếu thiếu bất kỳ thông tin khóa ký nào. |
| **APK Release Unsigned cho F-Droid** (`app-*-release-unsigned.apk`) | `io.veko.browser` | `Veko` | **Không ký** (`-PfdroidUnsignedRelease=true`), bật R8 + tắt khối `dependenciesInfo` | Dành riêng cho máy chủ đóng gói từ nguồn như `fdroidserver` tự ký cách ly; cấm truyền biến `VEKO_KEYSTORE_*`. |

## Hướng dẫn Kiểm thử, Build & Phát hành

### 1. Kiểm thử tự động (Unit + Android Emulator) & Tải APK Thử nghiệm trên CI
Mỗi khi có commit hoặc Pull Request, GitHub Actions thực thi song song 2 job kiểm chứng độc lập (quyền tối thiểu `contents: read`, không truy cập khóa ký phát hành):
* **`ci-verify`**: Kiểm định `fdroidserver` metadata, kiểm tra Cargo vendor ngoại tuyến SHA-256 + bài test C++20 `veko_adblock_native_tests`, chạy `clean`, toàn bộ JUnit 4 Unit Tests trên cả 7 module + Android Lint, xác minh chốt chặn khóa ký `verifyReleaseSigningConfig`, kiểm định căn lề ELF 16 KB (`verify-elf-packaging.py`) và tải lên các gói `veko-preview-*`, `veko-debug-apks`, `veko-ci-sha256-checksums`.
* **`emulator-test`**: Khởi chạy máy ảo **Android 14 (API 34, `x86_64` KVM)** để chạy `:app:connectedDebugAndroidTest` trực tiếp trên native `GeckoRuntime` (`libxul.so`) và `libveko_adblock_jni.so`, xác minh thực thi JavaScript SpiderMonkey, điều hướng `Load -> Back -> Forward -> Reload`, giải phóng `GeckoSession`, trần `MAX_LIVE_SESSIONS = 6` và cô lập 100% dữ liệu Private Mode khỏi hệ thống tệp Android.

### 2. Lệnh Build & Kiểm thử trên máy phát triển
Yêu cầu **JDK 17**, **Android SDK 37 / NDK `27.2.12479018`** và **Rust `>= 1.86`**:

```bash
# Cài đặt chuỗi công cụ Rust >= 1.86, Android targets & NDK
bash scripts/setup-native-toolchains.sh --with-android

# Chạy toàn bộ Unit Test hồi quy trên 7 module & Android Lint
./gradlew clean test lintDebug

# Chạy bộ kiểm thử tích hợp trên Android Emulator / Thiết bị thật đang kết nối ADB
./gradlew :app:connectedDebugAndroidTest

# Xuất APK Debug hoặc APK Thử nghiệm R8 (không cần khóa phát hành)
./gradlew :app:assembleDebug
./gradlew :app:assemblePreview

# Xuất APK Release Unsigned chuẩn F-Droid kèm Rust vendor ngoại tuyến (không cần khóa ký cá nhân)
bash scripts/vendor-rust-deps.sh --prepare
VEKO_OFFLINE_BUILD=true ./gradlew :app:assembleRelease -PfdroidUnsignedRelease=true

# Xuất APK / AAB Phát hành chính thức (bắt buộc cung cấp đủ 4 biến môi trường khóa ký)
VEKO_KEYSTORE_FILE=/path/to/release.jks \
VEKO_KEYSTORE_PASSWORD=... \
VEKO_KEY_ALIAS=... \
VEKO_KEY_PASSWORD=... \
./gradlew :app:assembleRelease
```

### 3. Hồ sơ Kiểm toán & Chuẩn bị Đóng gói F-Droid (`metadata/fdroid/`)
Thư mục [`metadata/fdroid/`](./metadata/fdroid/README.md) lưu trữ công thức staging [`io.veko.browser.yml`](./metadata/fdroid/io.veko.browser.yml) (đã qua kiểm tra `fdroid readmeta` & `fdroid lint --format`), bảng kiểm toán phụ thuộc [`DEPENDENCIES.md`](./metadata/fdroid/DEPENDENCIES.md), hướng dẫn biên dịch ngoại tuyến [`BUILD.md`](./metadata/fdroid/BUILD.md) và cổng kiểm định xuất bản [`PUBLISHING.md`](./metadata/fdroid/PUBLISHING.md). Trạng thái nộp lên `fdroiddata` chính thức được giữ trung thực là **`BLOCKED`** cho tới khi hoàn tất quy trình biên dịch nguồn `MozFennec` ngoại tuyến cho `org.mozilla.geckoview`.

### 4. Website Giới thiệu Chính thức (`website/`)
Thư mục [`website/`](./website/README.md) chứa trang web tĩnh độc lập của Veko Browser xây dựng bằng **Astro 7**, **TypeScript**, **Tailwind CSS v4** và **Lucide Icons** (0 script theo dõi, 0 quảng cáo, 0 CDN ngoài):
```bash
cd website
npm ci
npm run verify
```
