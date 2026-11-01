# Bảng Kiểm Toán Phụ Thuộc & Thư Viện Native (F-Droid Dependency Audit)

Tài liệu này liệt kê trung thực toàn bộ phụ thuộc bên ngoài, thành phần nhân trình duyệt và thư viện native của **Veko Browser** (`io.veko.browser`) đối chiếu trực tiếp từ `settings.gradle.kts`, `build.gradle.kts`, 9 tệp `<module>/build.gradle.kts`, `privacy/rust/veko-adblock/Cargo.toml` và `chromium/CHROMIUM_VERSION.lock`.

---

## 1. Bảng tổng hợp phụ thuộc (Verified Dependency Matrix)

| Dependency | Version | Source | License | Buildable from source | F-Droid status |
| --- | --- | --- | --- | --- | --- |
| `com.android.application` / `com.android.library` (AGP) | `9.1.1` | Google Maven (`dl.google.com`) | `Apache-2.0` | Có (AOSP `tools/base`) | Cần kiểm tra phiên bản AGP `9.1.1` & `compileSdk 37` trên image `fdroidserver` hiện hành |
| `org.jetbrains.kotlin.plugin.compose` (Kotlin) | `2.3.20` | Maven Central | `Apache-2.0` | Có (`JetBrains/kotlin`) | Hợp lệ (`Apache-2.0`) |
| `com.google.devtools.ksp` | `2.3.6` | Maven Central / Google Maven | `Apache-2.0` | Có (`google/ksp`) | Hợp lệ (`Apache-2.0`) |
| `com.google.dagger:hilt-android` + `hilt-android-compiler` | `2.59.2` | Maven Central / Google Maven | `Apache-2.0` | Có (`google/dagger`) | Hợp lệ (`Apache-2.0`) |
| `org.mozilla.geckoview:geckoview` | `150.0.20260511200624` | Mozilla Maven (`https://maven.mozilla.org/maven2/`) | `MPL-2.0` | Có từ nguồn Firefox, **nhưng hiện dự án đang tải AAR nhị phân biên dịch sẵn** | **`BLOCKED` cho `fdroiddata` chính thức** (chứa `libxul.so` prebuilt từ kho thứ ba; phải biên dịch qua `srclibs` Firefox hoặc dùng F-Droid repo riêng) |
| Chromium Content Embedder (`libveko_content_embedder.so`, `.pak`, V8) | `156.0.8078.85` (`0c5c7059...`) | Mã nguồn thượng nguồn `chromium.googlesource.com` (chưa đóng gói vào APK) | `BSD-3-Clause` (và giấy phép thành phần nguồn mở Chromium) | Có từ nguồn (~130 GB SSD / ≥32 GB RAM), hiện **không nhúng** trong APK tiêu chuẩn | Không chặn APK hiện tại vì không có binary prebuilt nào trong Git; nếu bật trong tương lai phải biên dịch từ nguồn |
| Crate nội bộ `veko-adblock-ffi` + `adblock` (`brave/adblock-rust`) | `0.1.0` / `=0.13.4` | Mã nguồn nội bộ `privacy/rust/veko-adblock` + `crates.io` | `MPL-2.0` | **Có** (biên dịch trực tiếp bằng `cargo` + `gn` + `ninja` trong lúc build) | **Cần bổ sung `Cargo.lock`** vào Git và cấu hình `cargo vendor` để build không cần mạng trong VM F-Droid |
| Cầu nối C++20 JNI (`libveko_chromium_native.so`) | Nội bộ (`0.3.0-dual-engine`) | Mã nguồn `chromium-native/src/main/cpp` + `BUILD.gn` | `MPL-2.0` | **Có** (biên dịch 100% từ nguồn qua NDK Clang `27.2.12479018`) | Hợp lệ về nguồn mở; yêu cầu cài `gn` (`generate-ninja`) và `ninja-build` trong bước `sudo:` |
| `androidx.compose:compose-bom` (`ui`, `ui-graphics`, `material3`, `runtime`) | `2026.03.01` | Google Maven | `Apache-2.0` | Có (`androidx/androidx`) | Hợp lệ (`Apache-2.0`) |
| `androidx.compose.material:material-icons-extended` | `1.7.8` | Google Maven | `Apache-2.0` | Có (`androidx/androidx`) | Hợp lệ (`Apache-2.0`) |
| `io.coil-kt.coil3:coil-compose` | `3.4.0` | Maven Central | `Apache-2.0` | Có (`coil-kt/coil`) | Hợp lệ (`Apache-2.0`, không gọi mạng ngầm khi `CachePolicy.DISABLED`) |
| `androidx.core:core-ktx` | `1.18.0` | Google Maven | `Apache-2.0` | Có (`androidx/androidx`) | Hợp lệ (`Apache-2.0`) |
| `androidx.activity:activity-compose` | `1.13.0` | Google Maven | `Apache-2.0` | Có (`androidx/androidx`) | Hợp lệ (`Apache-2.0`) |
| `androidx.lifecycle:lifecycle-{runtime-ktx,runtime-compose,viewmodel-compose}` | `2.10.0` | Google Maven | `Apache-2.0` | Có (`androidx/androidx`) | Hợp lệ (`Apache-2.0`) |
| `androidx.navigation:navigation-compose` | `2.9.8` | Google Maven | `Apache-2.0` | Có (`androidx/androidx`) | Hợp lệ (`Apache-2.0`) |
| `androidx.hilt:hilt-navigation-compose` | `1.3.0` | Google Maven | `Apache-2.0` | Có (`androidx/androidx`) | Hợp lệ (`Apache-2.0`) |
| `androidx.room:room-{runtime,ktx,compiler}` | `2.8.4` | Google Maven | `Apache-2.0` | Có (`androidx/androidx`) | Hợp lệ (`Apache-2.0`) |
| `androidx.datastore:datastore-preferences` | `1.2.1` | Google Maven | `Apache-2.0` | Có (`androidx/androidx`) | Hợp lệ (`Apache-2.0`) |
| `androidx.annotation:annotation` | `1.9.1` | Google Maven | `Apache-2.0` | Có (`androidx/androidx`) | Hợp lệ (`Apache-2.0`) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-{android,core}` | `1.11.0` / `1.10.2` | Maven Central | `Apache-2.0` | Có (`Kotlin/kotlinx.coroutines`) | Hợp lệ (`Apache-2.0`) |
| Phụ thuộc chỉ dùng cho Test (`junit`, `snakeyaml-engine`, `jsoup`, `org.json`, `espresso-core`) | `4.13.2` / `3.0.1` / `1.18.3` / `20240303` / `3.7.0` | Maven Central / Google Maven | `EPL-1.0`, `Apache-2.0`, `MIT` | Có | Không đóng gói vào APK `release` |

---

## 2. Phân tích chuyên sâu 3 nhóm thành phần trọng yếu

### 2.1. Mozilla GeckoView (`org.mozilla.geckoview:geckoview:150.0.20260511200624`)
- **Artifact & Kho lưu trữ thực tế**:
  - Trong `settings.gradle.kts`, kho `https://maven.mozilla.org/maven2/` được giới hạn phạm vi nghiêm ngặt bằng `content { includeGroup("org.mozilla.geckoview") }`.
  - Trong `app/build.gradle.kts`, dự án khai báo `implementation("org.mozilla.geckoview:geckoview:150.0.20260511200624")` kết hợp `splits.abi { include("arm64-v8a", "armeabi-v7a", "x86_64") }`.
- **Giấy phép**: `MPL-2.0` (tương thích hoàn toàn với tệp `LICENSE` `MPL-2.0` của Veko Browser).
- **Thư viện Native đóng gói bên trong AAR GeckoView**:
  - `libxul.so`, `libmozglue.so`, `libnss3.so`, `liblgpllibs.so`, `libfreebl3.so`, `libsoftokn3.so`, `libipcclientcerts.so`.
- **Đánh giá chính sách F-Droid (`BLOCKED` nếu giữ nguyên Maven prebuilt)**:
  - Theo [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/), mọi thư viện mã máy (`.so`) phức tạp như nhân trình duyệt phải được biên dịch từ mã nguồn trên hạ tầng F-Droid thay vì tải tệp `.aar` biên dịch sẵn từ máy chủ Mozilla.
  - **Hướng khắc phục chuẩn cho `fdroiddata`**:
    1. Cấu hình `srclibs:` trỏ tới nguồn Mozilla Firefox / GeckoView tương ứng bản phát hành để biên dịch `./mach build` tạo AAR GeckoView cục bộ (tương tự công thức của *Fennec F-Droid* trên `fdroiddata`) và trỏ Gradle sang `mavenLocal()`; **hoặc**
    2. Phân phối thông qua kho F-Droid tự vận hành (Custom F-Droid Repo) trong khi hoàn thiện quy trình biên dịch nguồn GeckoView.

### 2.2. Chromium Content / Blink / V8 (`156.0.8078.85`)
- **Trạng thái tích hợp thực tế**: **Đang phát triển (`In Development`)**.
  - Các lớp điều phối Kotlin (`ChromiumBrowserEngine`, `ChromiumAvailabilityChecker`, `ChromiumSurfaceHostView`) và mã nguồn cầu nối C++20 (`chromium-native/chromium_src/*`, `chromium/veko_embedder/BUILD.gn`) đã có mặt trong kho mã nguồn, gắn khóa commit `0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5`.
  - **Không có bất kỳ tệp nhị phân prebuilt nào** (`libveko_content_embedder.so`, `libchrome_crashpad_handler.so`, `.jar`, `.pak`, `icudtl.dat`, `v8_context_snapshot_*.bin`) được commit trong Git (`chromium-embedder/prebuilt/` được loại trừ trong `.gitignore`).
  - Ở thời điểm chạy thực tế, `ChromiumAvailabilityChecker` giữ nhân phụ này ở trạng thái `BLOCKED` (`MISSING_UPSTREAM_SDK`). Do đó thành phần này không đưa mã nhị phân không rõ nguồn gốc vào APK hiện tại.

### 2.3. Rust `veko-adblock-ffi` & Cầu nối C++20 JNI (`:chromium-native`)
- **Nguồn & Quy trình build**:
  - Crate Rust đặt tại `privacy/rust/veko-adblock/` (`license = "MPL-2.0"`), phụ thuộc `adblock = "=0.13.4"` (`brave/adblock-rust`, `MPL-2.0`).
  - Trong quá trình build APK, Gradle chạy task `:chromium-native:buildAndroidChromiumNativeGn` -> gọi `scripts/build-native-gn.sh --mode=android`, biên dịch `libveko_adblock_ffi.a` bằng `cargo` cho `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, sau đó gọi `gn` + `ninja` + NDK Clang++ (`27.2.12479018`) liên kết thành `libveko_chromium_native.so` (căn lề ELF `PT_LOAD` 16 KB) và xác minh bằng `scripts/verify-elf-packaging.py`.
- **Kiểm tra nhị phân đóng gói sẵn**: `git ls-files '*.so' '*.a'` xác nhận **0 tệp thư viện native đóng gói sẵn** trong Git (chỉ duy nhất `gradle/wrapper/gradle-wrapper.jar`, được xóa bằng `rm:` trong công thức F-Droid).
- **Yêu cầu khắc phục cho môi trường ngoại tuyến F-Droid**:
  - Hiện tại `privacy/rust/veko-adblock/Cargo.lock` chưa được commit vào Git. Trước khi gắn tag phát hành cho F-Droid, cần tạo và commit `privacy/rust/veko-adblock/Cargo.lock` đồng thời hỗ trợ `cargo build --release --frozen` (hoặc `cargo vendor` trong bước `init:`/`prebuild:` của F-Droid).

---

## 3. Kiểm tra SDK độc quyền, Khóa phụ thuộc & Tính tái lập (Reproducibility)

- **Phụ thuộc độc quyền (Proprietary SDKs)**: **0 phát hiện** — Không có `com.google.android.gms`, `com.google.firebase`, `com.android.billingclient`, Sentry, Adjust, AppsFlyer hay bất kỳ SDK thu thập dữ liệu đóng nào.
- **Dependency Verification / Lockfiles**:
  - Website có `website/package-lock.json` đầy đủ (không tham gia vào quá trình tạo APK Android).
  - Dự án Android hiện chưa bật `gradle/verification-metadata.xml` và chưa commit `privacy/rust/veko-adblock/Cargo.lock` — khuyến nghị bổ sung cả hai trước khi gắn tag `v0.3.0`.
- **Reproducible Builds**:
  - `app/build.gradle.kts` sử dụng `packaging.jniLibs.useLegacyPackaging = true` và ABI split tách biệt từng kiến trúc (`arm64-v8a`, `armeabi-v7a`, `x86_64`). Để đạt Reproducible Builds hoàn chỉnh với chữ ký upstream (`AllowedAPKSigningKeys`), cần đảm bảo cờ `-ffile-prefix-map` trong `BUILD.gn` và `CARGO_PROFILE_RELEASE_TRIM_PATHS` để loại bỏ đường dẫn thư mục máy chủ build khỏi `libveko_chromium_native.so`.
