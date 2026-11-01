# Kiến Trúc Trình Duyệt Thuần Mozilla GeckoView — Veko Browser (`io.veko.browser`)

Tài liệu này mô tả kiến trúc kỹ thuật của **Veko Browser** sau khi chuẩn hóa hoàn toàn về **một nhân trình duyệt duy nhất là Mozilla GeckoView** (`org.mozilla.geckoview:geckoview:150.0.20260511200624`, giấy phép `MPL-2.0`), kết hợp lá chắn quyền riêng tư **Rust (`brave/adblock-rust 0.13.4`)** qua cầu nối **C++20 JNI (`libveko_adblock_jni.so`)** và tiện ích nội bộ **GeckoView WebExtension (`veko-shield@veko.browser`)**.

---

## 1. Kiến Trúc 7 Module Gradle Kotlin DSL

Dự án được tổ chức thành **7 module độc lập** (không chứa bất kỳ module phụ thuộc engine thứ hai hay `android.webkit.WebView` nào trong `src/main`):

| Module | Loại | Trách nhiệm kỹ thuật |
| :--- | :--- | :--- |
| **`:app`** | Android Application | Giao diện Jetpack Compose Material 3 (8 ngôn ngữ), `GeckoBrowserEngine`, `BrowserRuntimeManager`, `GeckoEngineSessionBridge`, `GeckoSessionManager`, `GeckoSettings`, và tiện ích nội bộ `assets/extensions/veko_shield/`. |
| **`:browser-core`** | Kotlin JVM Library | Hợp đồng `BrowserEngine`, `TabManager` (`MAX_LIVE_SESSIONS = 6` LRU), `SessionPersistenceCodec`, `UrlNavigationPolicy`, và luồng một chiều `BrowserMviStore` / `BrowserReducer`. |
| **`:privacy`** | Android Library + Rust/C++20 JNI | Crate Rust `privacy/rust/veko-adblock` (`adblock = "=0.13.4"`), cầu nối C++20 JNI `libveko_adblock_jni.so` (căn lề ELF 16 KB), `VekoAdblockNative`, `PrivacyShieldEngine`, và `FilterListManager`. |
| **`:security`** | Android Library | `SitePermissionManager` (chuẩn hóa `scheme://host:port`), `TlsCertificatePolicy`, và kiểm duyệt Intent bên ngoài. |
| **`:storage`** | Android Library | Cơ sở dữ liệu cục bộ Room SQLite v1 (`veko_browser.db`), `SettingsRepository` (DataStore), nhập/xuất Dấu trang HTML/JSON, và mô hình `EngineType.GECKO` / `EngineAvailability`. |
| **`:downloads`** | Android Library | `VekoDownloadManager` ghi qua `MediaStore.Downloads` (Scoped Storage), làm sạch tên tệp RFC 5987 và đối chiếu phần mở rộng thực thi / MIME (`DownloadSecurityPolicy`). |
| **`:testing`** | Kotlin JVM Library | Khung kiểm thử tích hợp `VekoTestHarness` cho MVI, `PrivacyShieldEngine` và cầu nối JNI `VekoAdblockNative`. |

---

## 2. Quản Lý Vòng Đời `GeckoRuntime` & `GeckoSession`

1. **Một `GeckoRuntime` chia sẻ duy nhất trên toàn ứng dụng**:
   - `BrowserRuntimeManager` (`@Singleton`) khởi tạo lười duy nhất một thể hiện `GeckoRuntime.create(applicationContext, runtimeSettings)` trên luồng chính (`@MainThread`), dùng `@ApplicationContext` để không bao giờ giữ tham chiếu ngược tới `MainActivity`.
   - `VekoApplication.onCreate()` chỉ khởi tạo thành phần của tiến trình chính khi `!isChildServiceProcess()`, bảo đảm các tiến trình dịch vụ con của Gecko (`:tab`, `:gpu`, `:socket`) không khởi tạo nhầm `GeckoRuntime` lần hai.
2. **Một bề mặt `GeckoView` tái sử dụng & Trần cứng 6 phiên sống LRU**:
   - Toàn bộ ứng dụng tái sử dụng một `GeckoView` qua `WeakReference<GeckoView>` trong `GeckoEngineSessionBridge`.
   - Khi đổi tab hoặc đưa ứng dụng xuống nền (`ON_STOP`), `TabManager` gọi `geckoView.releaseSession()`, `session.setActive(false)` và `session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)`.
   - `TabManager` áp trần cứng `MAX_LIVE_SESSIONS = 6`: các tab nền cũ hơn được thu hồi bằng `session.close()`, gỡ bỏ sạch toàn bộ `NavigationDelegate`, `ContentDelegate`, `ProgressDelegate`, `PermissionDelegate`, `PromptDelegate`, `HistoryDelegate`, `ContentBlocking.Delegate` và `ScrollDelegate`, trong khi vẫn giữ nguyên `serializedEngineState` (`GeckoSession.SessionState`) để khôi phục lười tức thì khi người dùng chạm mở lại.
3. **Danh mục đầy đủ tính năng Web được nối trực tiếp vào `GeckoSession`**:
   - Điều hướng URL/tìm kiếm, kiểm duyệt scheme (`UrlNavigationPolicy`), `goBack()`, `goForward()`, `reload()`, `stop()`, chế độ trang máy tính (`DESKTOP` / `MOBILE`), tìm trong trang (`SessionFinder`).
   - Hộp thoại JavaScript (`Alert`, `Confirm`, `TextPrompt`, `BeforeUnload`), cảnh báo `SlowScript` và phục hồi khi tiến trình tab con gặp sự cố (`onCrash` / `onKill`).
   - Phân quyền theo từng Origin (`Camera`, `Micrô`, `Vị trí`), kiểm soát tự phát âm thanh/video (`PERMISSION_AUTOPLAY_AUDIBLE`, `PERMISSION_AUTOPLAY_INAUDIBLE`), toàn màn hình (`onFullScreen`) và bộ chọn tệp tải lên (`PromptDelegate.onFilePrompt` kết nối trực tiếp với Android Storage Access Framework cho chế độ đơn tệp, đa tệp và thư mục).
   - Tải xuống qua `ContentDelegate.onExternalResponse` + kiểm tra MIME, Chế độ Riêng tư cô lập trên RAM (`usePrivateMode(true)` + `FLAG_SECURE`), quản lý cookie/dFPI/GPC và xóa sạch dữ liệu qua `StorageController.clearData(...)`.

---

## 3. Tích Hợp Lá Chắn Rust (`adblock-rust`) Với GeckoView & Giới Hạn Kỹ Thuật Trung Thực

### 3.1. Ba lớp bảo vệ phối hợp
1. **Lớp 1 — Động cơ Rust `veko-adblock-ffi` + C++20 JNI (`libveko_adblock_jni.so`)**:
   - Đóng gói `brave/adblock-rust` (`=0.13.4`, `MPL-2.0`) biên dịch từ nguồn qua `scripts/build-adblock-native.sh` (Cargo `--locked [--offline]` + Android NDK Clang++ với `-Wl,-z,max-page-size=16384`).
   - Xử lý đồng bộ tốc độ cao cho điều hướng cấp cao nhất (`NavigationDelegate.onLoadRequest`), cắt bỏ hơn 40 tham số theo dõi trên URL (`utm_*`, `fbclid`, `gclid`, `ttclid`, `igshid`, `_ga`, ...) và tính toán bộ chọn ẩn phần tử quảng cáo (`Engine::url_cosmetic_resources` + generic cosmetic selectors).
2. **Lớp 2 — Tiện ích nội bộ GeckoView WebExtension (`veko-shield@veko.browser`)**:
   - Được cài đặt qua `runtime.webExtensionController.ensureBuiltIn("resource://android/assets/extensions/veko_shield/", "veko-shield@veko.browser")` và kết nối qua `MessageDelegate` (`nativeApp = "vekoShield"`).
   - Lắng nghe `browser.webRequest.onBeforeRequest` (`["blocking"]`) cho các tài nguyên con HTTP/HTTPS (`script`, `xmlhttprequest`, `beacon`, `ping`, `sub_frame`, `image`, `imageset`, `media`, `websocket`, `csp_report`, `other`) với ngưỡng an toàn **fail-open 450 ms** để không bao giờ treo luồng tải trang nếu kênh nhắn tin bận.
   - Yêu cầu gói `pageShield` (ngưỡng an toàn 600 ms) tại `document_start` để tiêm CSS ẩn khung quảng cáo rỗng và bảo vệ giảm nhiễu vân tay trình duyệt khi bật chống fingerprinting.
3. **Lớp 3 — Bảo vệ gốc của Mozilla GeckoView (`ContentBlocking`)**:
   - Cấu hình `ContentBlocking.EtpLevel.STRICT`, `ContentBlocking.AntiTracking.STRICT`, `CookieBannerMode.COOKIE_BANNER_MODE_REJECT`, `globalPrivacyControlEnabled(true)` (`Sec-GPC: 1`) và Dynamic First-Party Isolation (`COOKIE_ACCEPT_NON_TRACKERS`).

### 3.2. Giới hạn kỹ thuật được công bố minh bạch
- `WebExtensionController.ensureBuiltIn(...)` khởi tạo bất đồng bộ khi tạo `GeckoRuntime`; trong vài mili-giây khởi động lạnh đầu tiên trước khi tiện ích sẵn sàng (hoặc nếu một thông điệp IPC vượt quá ngưỡng an toàn 450 ms), yêu cầu tài nguyên con được cho qua theo cơ chế fail-open và vẫn chịu sự kiểm soát của lớp **GeckoView Strict ETP** cùng `NavigationDelegate`.
- API `browser.webRequest` của GeckoView chỉ áp dụng cho lưu lượng `http://` và `https://`, không can thiệp các giao thức nội bộ của trình duyệt (`about:`, `resource:`, `moz-extension:`, `blob:`, `data:`). Veko tuyệt đối không tuyên bố chặn 100% mọi gói tin mạng cấp thấp.

---

## 4. Chuẩn Hóa Phụ Thuộc GeckoView & Trạng Thái F-Droid

- **Phiên bản ghim cố định**: `org.mozilla.geckoview:geckoview:150.0.20260511200624` (`MPL-2.0`, khớp tag nguồn Firefox `FIREFOX-ANDROID_150_0_3_RELEASE`, commit `a077abc2b0f43ed7cc59a8bfcd873e683500d23a`).
- **Thư viện native đóng gói theo từng ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `minSdk = 26`)**:
  - 7 thư viện của Mozilla GeckoView: `libxul.so`, `libmozglue.so`, `libnss3.so`, `liblgpllibs.so`, `libfreebl3.so`, `libsoftokn3.so`, `libipcclientcerts.so`.
  - 1 thư viện JNI nội bộ biên dịch từ nguồn: `libveko_adblock_jni.so` (liên kết tĩnh `libveko_adblock_ffi.a`, căn lề trang ELF 16 KB).
- **Trạng thái F-Droid trung thực (`BLOCKED`)**:
  - Việc loại bỏ hoàn toàn nhân phụ trước đây giúp cây mã nguồn gọn sạch và không còn hệ thống GN/Ninja, nhưng **không tự động giải quyết điểm nghẽn F-Droid của GeckoView**: bản build Gradle mặc định vẫn tải gói AAR dựng sẵn chứa 7 tệp `.so` từ `https://maven.mozilla.org/maven2/`. Do đó trạng thái nộp lên `fdroiddata` vẫn giữ nghiêm ngặt là **`BLOCKED`** cho tới khi hoàn tất và kiểm chứng thực tế công thức biên dịch nguồn `MozFennec` ngoại tuyến sang `mavenLocal()`.
