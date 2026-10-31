# VEKO BROWSER — BÁO CÁO KIỂM TOÁN KIẾN TRÚC & KHẢ THI TÍCH HỢP CHROMIUM CONTENT (PHASE 1)

> **Trạng thái tài liệu:** Phase 1 — Repository & Feasibility Audit  
> **Nhánh làm việc:** `arena/657defaf-zeko` (tách từ `main` `a9c4348`)  
> **Phiên bản Chromium khóa cứng:** `156.0.8078.85` (`0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5` — xem `chromium/CHROMIUM_VERSION.lock`)  
> **Phiên bản Filter Engine khóa cứng:** `brave/adblock-rust` (`adblock = 0.13.4`)  
> **Nguyên tắc bất biến:** Không thay thế ngầm bằng `android.webkit.WebView`; không giả lập nhân Chromium bằng placeholder; không gỡ bỏ `GeckoView` đang chạy ổn định trước khi nhị phân Chromium `//content` được biên dịch và xác thực đầu-cuối trên thiết bị/máy ảo Android.

---

## 1. Kiểm toán Hiện trạng Kho mã nguồn (`Zeko`)

### 1.1 Cấu trúc hiện tại trước khi phân tách đa module
Kho mã nguồn hiện là dự án Android đơn module (`:app`) sử dụng Gradle Groovy (`build.gradle`, `settings.gradle`, `app/build.gradle`):

| Thành phần hiện hữu | Đường dẫn hiện tại | Công nghệ & Vai trò |
| :--- | :--- | :--- |
| **Application & DI** | `VekoApplication.kt`, `di/DatabaseModule.kt`, `di/EngineModule.kt` | Hilt `2.59.2` (KSP `2.3.6`), chặn khởi tạo trùng runtime ở tiến trình con `:tab` / `:gpu` |
| **Engine hiện tại** | `engine/BrowserRuntimeManager.kt`, `tab/EngineSessionBridge.kt` | `org.mozilla.geckoview:geckoview:150.0.20260511200624` (SpiderMonkey + WebRender) |
| **Điều phối Tab & Phiên** | `tab/TabManager.kt`, `tab/Tab.kt`, `tab/SessionPersistenceCodec.kt` | Giữ trần cứng `MAX_LIVE_SESSIONS = 6` theo LRU hai tầng, bảo vệ handoff popup `onNewSession`, lưu phiên tab thường vào DataStore JSON v2 |
| **Điều hướng & Quyền** | `tab/UrlNavigationPolicy.kt`, `permission/SitePermissionManager.kt` | Chuẩn hóa RFC 6454 origin, chặn `javascript:`/`file:`/auto-redirect sang app ngoài, lưu quyền Origin thường vào DataStore và quyền riêng tư trên RAM |
| **Lưu trữ cục bộ** | `data/local/*`, `data/repository/*` | Room SQLite `2.8.4` (`VekoDatabase` v1: `browsing_history`, `bookmarks`) + Jetpack DataStore `1.2.1` (`veko_user_settings`) |
| **Tải xuống** | `download/VekoDownloadManager.kt` | `MediaStore.Downloads` (API 29+) & `getExternalFilesDir` (API 26–28), khử độc tên tệp nhiều dấu chấm |
| **Giao diện người dùng** | `MainActivity.kt`, `ui/BrowserScreen.kt`, `ui/BrowserOverlays.kt`, `ui/home/VekoHomePage.kt`, `ui/theme/*` | Jetpack Compose (`compose-bom:2026.03.01`), Material 3, Trang chủ **Veko Horizon Deck**, Smart Omnibox, Find in Page, Page Zoom, Tab Switcher |
| **Cấu hình Bảo mật & CI/CD** | `AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`, `.github/workflows/build-apk.yml` | Allowlist sao lưu chỉ cho `veko_user_settings.preferences_pb`, tách 3 biến thể `debug` / `preview` / `release` (chặn fallback debug key), pin SHA 40 ký tự mọi GitHub Action, kiểm thử tự động trên máy ảo API 34 `x86_64` |
| **Bộ kiểm thử hiện có** | `app/src/test/...` (7 suite, 35 test) & `app/src/androidTest/...` (5 test) | Kiểm thử JUnit 4 trên JVM + kiểm thử tích hợp thực tế trên Android Emulator KVM |

### 1.2 Những khoảng trống so với Kiến trúc Mục tiêu (Chromium Content + Rust Privacy)
1. **Build system**: Đang dùng Gradle Groovy đơn module thay vì **Gradle Kotlin DSL (`*.gradle.kts`)** đa module + **GN + Ninja** cho tầng C++.
2. **Kiến trúc trạng thái**: `TabManager` kết hợp trực tiếp logic điều phối phiên với `MutableStateFlow`, chưa tách thành mô hình **MVI / UDF thuần khiết** (`BrowserMviState` bất biến + `BrowserIntent` + pure `BrowserReducer` + `BrowserEffect`) trong module `:browser-core` độc lập.
3. **Nhân trình duyệt**: Đang gắn với `GeckoView` thay vì tầng nhúng chính thức **Chromium Content API (`content::ContentMainDelegate`, `ContentBrowserClient`, `BrowserContext`, `StoragePartition`, `WebContents`, `NavigationController`, `WebContentsObserver`, Blink + V8)**.
4. **Lọc quảng cáo & trình theo dõi gốc**: Chưa có engine lọc chuyên dụng viết bằng **Rust (`brave/adblock-rust`)** nối trực tiếp vào đường ống kiểm duyệt request C++ (`blink::URLLoaderThrottle` / `WillStartRequest` / `WillRedirectRequest`) qua FFI/JNI, chưa có thống kê request bị chặn theo thời gian thực và chưa có màn hình **Privacy Dashboard**.
5. **Thư viện ảnh**: Chưa tích hợp **Coil** với chính sách khóa truy vấn mạng ngoài luồng bảo mật.

---

## 2. Kiến trúc Đích 9 Module & Luồng Dữ liệu MVI / Native

### 2.1 Sơ đồ phân rã 9 Module chuẩn hóa

```text
                                   ┌──────────────┐
                                   │     :app     │  (Compose + Material 3 + Coil + Hilt Entry)
                                   └──────┬───────┘
                     ┌────────────────────┼────────────────────┐
                     ▼                    ▼                    ▼
            ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
            │  :browser-core  │  │    :privacy     │  │   :downloads    │
            │  (MVI Reducer,  │  │ (Filter Lists,  │  │ (Scoped Storage,│
            │   Tabs, Session)│  │  Cookie Policy, │  │  MIME Validator)│
            └────────┬────────┘  │  Shield Stats)  │  └────────┬────────┘
                     │           └────────┬────────┘           │
                     ▼                    ▼                    │
            ┌─────────────────┐  ┌─────────────────┐           │
            │:chromium-embedder│  │:chromium-native │           │
            │ (WebContents,   │──▶ (C++20 + JNI +  │           │
            │  BrowserContext,│  │  GN/Ninja +     │           │
            │  OTR Partition) │  │  Rust FFI Link) │           │
            └────────┬────────┘  └────────┬────────┘           │
                     │                    │                    │
                     └──────────┬─────────┴──────────┬─────────┘
                                ▼                    ▼
                       ┌─────────────────┐  ┌─────────────────┐
                       │    :storage     │  │    :security    │
                       │ (Room SQLite v1,│  │ (Strict TLS,    │
                       │  DataStore,     │  │  Safe Nav,      │
                       │  OTR Firewall)  │  │  Origin Perms)  │
                       └─────────────────┘  └─────────────────┘
                                          ▲
                                   ┌──────┴───────┐
                                   │   :testing   │  (Kotlin + C++ + Rust + Emulator Harnesses)
                                   └──────────────┘
```

### 2.2 Kết nối Thực tế giữa Rust Adblock Engine và Request Pipeline của Chromium
Theo mã nguồn thượng nguồn Chromium `156.0.8078.85` (`content/public/browser/content_browser_client.h` và `third_party/blink/public/common/loader/url_loader_throttle.h`), **JNI không tự động chặn được các subresource request** của Blink/NetworkService. Cơ chế đúng phải diễn ra ngay trong không gian địa chỉ C++ của Browser Process:

1. **Khởi tạo Filter Engine (Rust `adblock 0.13.4`)**:
   - Module Rust `privacy/rust/veko-adblock` biên dịch với `default-features = false, features = ["embedded-domain-resolver", "full-regex-handling"]` (tắt `single-thread` để `adblock::Engine` thỏa mãn `Send + Sync`), bọc trong `RwLock<Engine>` và xuất giao diện C ABI tĩnh (`libveko_adblock_ffi.a`).
2. **Đánh chặn Điều hướng & Tài nguyên con trong C++ (`chromium-native`)**:
   - **Tài nguyên điều hướng khung chính / iframe**: `VekoContentBrowserClient::CreateURLLoaderThrottles(...)` gắn thực thể `VekoURLLoaderThrottle` (kế thừa `blink::URLLoaderThrottle`).
   - **Tài nguyên con (`script`, `image`, `stylesheet`, `xmlhttprequest`, `fetch`, `subdocument`, `ping`, Worker)**: `VekoContentBrowserClient::WillCreateURLLoaderFactory(...)` và `CreateURLLoaderThrottlesForKeepAlive(...)` chèn proxy/throttle ngay tại Browser Process trước khi đẩy sang `NetworkService` (cùng mô hình kiến trúc với `BraveProxyingURLLoaderFactory`, tránh nhân bản bộ lọc 40–60 MB sang từng tiến trình Renderer bị sandbox).
   - Tại `WillStartRequest(network::ResourceRequest* request, bool* defer)` và `WillRedirectRequest(...)`:
     - C++ gọi trực tiếp hàm C ABI `veko_adblock_evaluate_request(...)` của Rust **mà không tốn chi phí nhảy qua JNI/JVM** trên đường nóng (hot path ~5–25 µs/request).
     - Nếu `should_block == true`: gọi `delegate_->CancelWithError(net::ERR_BLOCKED_BY_CLIENT, "VekoShield")` và tăng bộ đếm nguyên tử (`atomic`) để tầng UI đọc qua JNI/Flow.
     - Nếu có quy tắc `$removeparam` (`rewritten_url` khác rỗng, ví dụ gỡ `fbclid`, `gclid`, `utm_*`): gán lại `request->url = GURL(rewritten_url)` trước khi gửi gói tin.
     - Nếu phát hiện Mixed Content (`http://` tải từ trang gốc `https://`) khi bật khóa nghiêm ngặt: hủy với `net::ERR_MIXED_CONTENT_BLOCKED` hoặc nâng cấp HTTPS.

### 2.3 Chế độ Duyệt Riêng tư Thực chất (Off-The-Record `BrowserContext`)
- Không chỉ ẩn lịch sử ở giao diện Compose:
  - Phiên thường dùng `VekoBrowserContext(is_off_the_record = false)` ánh xạ thư mục đĩa riêng dưới `app_veko_chromium/Default`.
  - Phiên riêng tư bắt buộc khởi tạo `VekoBrowserContext(is_off_the_record = true)` với `GetPath()` rỗng, buộc `StoragePartition` của Chromium tạo **In-Memory CookieMonster**, **In-Memory HttpCache**, **In-Memory DOMStorage / IndexedDB**, tách biệt hoàn toàn socket pool và cache key (`NetworkIsolationKey`).
  - Khi người dùng đóng tab riêng tư cuối cùng (`CloseAllPrivateTabs`), C++ hủy toàn bộ thực thể `OffTheRecordBrowserContext`, giải phóng sạch cookie, bộ nhớ đệm, session ticket TLS và quyền tạm thời trên RAM.

---

## 3. Đánh giá Khả thi Biên dịch Chromium Thượng nguồn, Môi trường Build & Dự toán Dung lượng

### 3.1 Yêu cầu Hạ tầng Biên dịch `//content` Thượng nguồn vs Hạ tầng Hiện tại

| Thông số hạ tầng | Yêu cầu thực tế của Chromium Android (`156.0.8078.85` `//content` + Blink + V8) | Sandbox hiện tại | GitHub Actions `ubuntu-latest` tiêu chuẩn | Kết luận Khả thi |
| :--- | :--- | :--- | :--- | :--- |
| **Mạng tải mã nguồn** | Cần truy cập `chromium.googlesource.com`, `chrome-infra-packages.appspot.com` (CIPD), `commondatastorage.googleapis.com` | Bị chặn bởi firewall sandbox (chỉ mở `github.com`, `pypi.org`, `npmjs.org`) | Truy cập được | Không thể `gclient sync` trong sandbox |
| **Dung lượng ổ cứng trống** | **97 – 115 GB** tối thiểu (`~52 GB` mã nguồn `--no-history` + Android sysroot/NDK/LLVM + `~45–60 GB` thư mục `out/android_arm64` với `symbol_level=0`) | **20 GB** trống (`/dev/root 21G`) | **~14 – 30 GB** mặc định (~60 GB tối đa sau khi dọn image) | **Không đủ đĩa** trên cả sandbox lẫn GitHub Actions miễn phí |
| **CPU & RAM** | Khuyến nghị **16–64 vCPU**, **32–64 GB RAM** (`~18–26 CPU-giờ` cho ~42.000 mục tiêu Ninja của Blink + V8 + Skia + Content) | 2 vCPU, 3.8 GiB RAM, 0 Swap | 4 vCPU, 16 GB RAM, giới hạn cứng **6 giờ/job** | 4 vCPU mất ~6.5–9 giờ (vượt trần 6h của GitHub Actions và OOM khi link `libveko_content_embedder.so` với LLD) |

### 3.2 Chiến lược Kỹ thuật Trung thực & Thực thi Ngay trên Dự án
Do Chromium **không phát hành gói AAR độc lập** cho `//content` trên Maven và hạ tầng CI tiêu chuẩn không đủ 110 GB đĩa / 32 GB RAM để biên dịch toàn bộ Blink + V8 từ mã nguồn, Veko triển khai **kiến trúc hai lớp trung thực 100%**:

1. **Biên dịch & Chạy thật ngay trên CI và Android Emulator hôm nay**:
   - Toàn bộ **9 module Gradle Kotlin DSL** (`app`, `browser-core`, `chromium-embedder`, `chromium-native`, `privacy`, `downloads`, `storage`, `security`, `testing`).
   - Toàn bộ **Rust Filter Engine (`brave/adblock-rust` `0.13.4`)** biên dịch bằng `cargo` ra thư viện tĩnh `libveko_adblock_ffi.a` cho cả `x86_64-unknown-linux-gnu` (host) và 3 kiến trúc Android (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
   - Toàn bộ **C++20 Native Bridge & Request Pipeline (`libveko_chromium_native.so`)** biên dịch thật bằng **GN + Ninja** (`gn gen` + `ninja`) với trình biên dịch `clang++` của Android NDK (căn chỉnh trang ELF `16 KB`), liên kết tĩnh với `libveko_adblock_ffi.a`, đóng gói vào APK và kiểm thử JNI trực tiếp trên **Android API 34 Emulator**.
2. **Mã nguồn C++20 & kịch bản GN/Ninja cho Thượng nguồn Chromium (`chromium-native/chromium_src/` & `scripts/build-chromium-android.sh`)**:
   - Cung cấp đầy đủ mã nguồn `VekoMainDelegate`, `VekoContentBrowserClient`, `VekoBrowserContext`, `VekoURLLoaderThrottle`, `VekoContentRendererClient` và cấu hình GN sẵn sàng đặt vào `$CHROMIUM_SRC/veko_embedder` trên máy chủ build chuyên dụng (≥ 16 vCPU, ≥ 32 GB RAM, ≥ 150 GB NVMe) để xuất gói `veko-chromium-content-sdk` (`libveko_content_embedder.so` + `content_java.jar` + `.pak` ICU/V8 snapshot).
3. **Cổng chuyển giao an toàn (Phase 6 Gate)**:
   - Tuân thủ nghiêm ngặt quy tắc *"Không xóa GeckoView trước khi Chromium prototype chạy thành công trên Android"*: khi chưa nạp gói nhị phân `libveko_content_embedder.so` của thượng nguồn, `VekoChromiumNative.isUpstreamContentLinked()` trả về `false` một cách minh bạch (tuyệt đối không giả mạo và không fallback về `WebView`), đồng thời trình duyệt vẫn duy trì hiển thị trang web thực qua `GeckoView` kết hợp trực tiếp với **MVI `:browser-core`** và **lá chắn C++/Rust `:privacy`**.

### 3.3 Ma trận ABI & Dự toán Dung lượng APK (ABI-Split)

| Kiến trúc ABI | Trạng thái hỗ trợ | Dung lượng hiện tại (GeckoView + Rust Adblock + C++ Native, R8 Preview) | Dự toán khi đóng gói Chromium Content (`Blink + V8 + Content + Rust Adblock`, R8, ABI-split) |
| :--- | :---: | :---: | :--- |
| **`arm64-v8a`** | Bắt buộc (Chính) | ~88 – 94 MB (`libxul.so` + `libveko_chromium_native.so`) | **~68 – 78 MB** (`libveko_content_embedder.so` nén Brotli/APK v2 + `icudtl.dat` `~10.4 MB` + `v8_context_snapshot_64.bin` + `.pak`) |
| **`armeabi-v7a`** | Bắt buộc (Thiết bị 32-bit) | ~79 – 85 MB | **~58 – 66 MB** |
| **`x86_64`** | Bắt buộc (Emulator / Chromebook) | ~93 – 99 MB | **~74 – 84 MB** |
| **Universal APK** | Tắt (`universalApk = false`) | Không phát hành (tránh phình > 240 MB) | Không phát hành (dùng ABI split / Play App Bundle `.aab`) |

---

## 4. Phân tích Rủi ro Hiệu năng, Quyền riêng tư, Bảo mật & Giấy phép Nguồn mở

### 4.1 Rủi ro Hiệu năng & Quản trị Đa tiến trình
- **Quản trị tiến trình Renderer & OOM Killer của Android**: Chromium tách tiến trình Browser, GPU (`PrivilegedProcessService`) và nhiều tiến trình Renderer bị cô lập (`SandboxedProcessService0..N` với `android:isolatedProcess="true"`). Nếu giữ quá nhiều kết nối `BIND_IMPORTANT` tới các tab nền, Android `lmkd` sẽ giết cả tiến trình chính. Giải pháp: Hạ mức liên kết của `WebContents` nền về `BIND_WAIVE_PRIORITY` và giữ trần phiên hoạt động tương tự `MAX_LIVE_SESSIONS = 6`.
- **Chi phí JNI trên luồng mạng**: Nếu mỗi subresource gọi ngược từ C++ lên Kotlin qua JNI để kiểm tra bộ lọc quảng cáo, một trang báo có 250 request sẽ tốn hàng trăm lần cấp phát `jstring`. Giải pháp: Đặt `adblock-rust` ngay dưới C++ qua C ABI (`libveko_adblock_ffi.a`), xử lý trọn vẹn trong C++/Rust và chỉ đẩy số liệu tổng hợp lên UI.
- **Tương thích Android 15/16 (Trang bộ nhớ 16 KB)**: Mọi thư viện `.so` biên dịch mới bắt buộc gắn cờ liên kết `-Wl,-z,max-page-size=16384`.

### 4.2 Rủi ro Quyền riêng tư & Bảo mật
- **Chứng chỉ TLS không hợp lệ**: Tuyệt đối không cung cấp nút "Tiếp tục truy cập không an toàn" cho các lỗi chứng chỉ nghiêm trọng (`ERR_CERT_AUTHORITY_INVALID`, `ERR_CERT_REVOKED`, vi phạm HSTS); `VekoContentBrowserClient::AllowCertificateError` và `TlsCertificatePolicy` luôn trả về **từ chối cứng (`REJECT`)**.
- **Rò rỉ IP qua WebRTC ở Chế độ Riêng tư**: Cần ép chính sách đường truyền WebRTC `disable_non_proxied_udp` hoặc giới hạn ICE candidate khi ở `OffTheRecordBrowserContext`.
- **Rò rỉ truy vấn mạng từ thư viện tải ảnh UI**: Cấu hình `Coil` ở chế độ không tự tải mạng ngoài (`networkCachePolicy(CachePolicy.DISABLED)`) để không bao giờ rò rỉ tên miền dấu trang ra ngoài đường ống bảo mật của trình duyệt.
- **Lộ dữ liệu qua Android Auto Backup**: Tiếp tục khóa cứng `backup_rules.xml` và `data_extraction_rules.xml` chỉ cho phép duy nhất `veko_user_settings.preferences_pb`, loại trừ triệt để Room DB, phiên tab và thư mục `app_veko_chromium`.

### 4.3 Tuân thủ Giấy phép Nguồn mở (Licensing)

| Thành phần | Giấy phép | Nghĩa vụ tuân thủ khi phân phối Veko Browser |
| :--- | :---: | :--- |
| **Chromium (`//content`, Blink, V8, Skia, Net)** | `BSD-3-Clause` (+ các thư viện phụ MIT/Apache-2.0/ICU/LGPL trong `third_party`) | Giữ nguyên bản quyền và hiển thị trang `chrome://credits` / mục Giấy phép nguồn mở trong ứng dụng |
| **`brave/adblock-rust` (`adblock` crate)** | `MPL-2.0` (Weak Copyleft theo tệp) | Cho phép liên kết tĩnh/động với mã nguồn Veko; nếu sửa đổi trực tiếp tệp `.rs` của `adblock-rust` thì công bố mã nguồn phần tệp sửa đổi đó |
| **Mozilla GeckoView (giai đoạn chuyển tiếp trước Phase 6)** | `MPL-2.0` | Tương tự trên — giữ thông báo bản quyền MPL-2.0 cho tới khi hoàn tất gỡ bỏ ở Phase 6 |
| **AndroidX, Jetpack Compose, Room, Hilt, Coroutines, Coil** | `Apache-2.0` | Giữ thông báo bản quyền `NOTICE` / Apache-2.0 tiêu chuẩn |
