# Bảng Kiểm Toán Toàn Diện Phụ Thuộc & Thư Viện Native (F-Droid Dependency Audit)

Tài liệu này kiểm toán toàn bộ chuỗi công cụ, Gradle plugins, thư viện Maven, nhân trình duyệt **Mozilla GeckoView**, cầu nối C++20 JNI (`libveko_adblock_jni.so`) và **toàn bộ 58 gói Cargo Rust** trong `privacy/rust/veko-adblock/Cargo.lock` của **Veko Browser** (`io.veko.browser`).

> **Nguyên tắc kiểm định F-Droid bắt buộc**:
> **Tương thích giấy phép nguồn mở (`License compatibility`) KHÔNG đồng nghĩa với tương thích biên dịch từ nguồn của F-Droid (`Source-build compatibility`).** Một thư viện mang giấy phép tự do (`MPL-2.0`, `Apache-2.0`) nhưng được tải dưới dạng gói nhị phân `.so`/`.aar` biên dịch sẵn từ kho ngoài (`maven.mozilla.org`) vẫn bị đánh giá là **`BLOCKED`** đối với kho chính thức `https://gitlab.com/fdroid/fdroiddata` cho tới khi được biên dịch trực tiếp từ mã nguồn thượng nguồn (thông qua `srclibs: MozFennec`).

---

## 1. Bảng Kiểm Toán Phụ Thuộc Tổng Hợp (Gradle Plugins, Maven, GeckoView, C++20 JNI & Rust)

| Dependency | Version | Source | License | Prebuilt binary | Offline build | Status |
| --- | --- | --- | --- | --- | --- | --- |
| Gradle Wrapper (`gradle-9.4.0-bin.zip`) | `9.4.0` | `services.gradle.org` (`gradle-wrapper.jar` bị xóa bởi `rm:` trên F-Droid) | `Apache-2.0` | `gradle-wrapper.jar` trong Git (xóa trước khi build trên F-Droid để dùng Gradle hệ thống) | Có (sau khi F-Droid chuẩn bị Gradle cache) | `PASS` |
| `com.android.application` / `com.android.library` (AGP) | `9.1.1` | Google Maven (`dl.google.com`) | `Apache-2.0` | Artifact bytecode/AAPT2 chuẩn của Android SDK/Google Maven | Có (qua Gradle cache của buildserver) | `PASS` (cần image buildserver hỗ trợ `compileSdk 37` / AGP `9.1.1`) |
| `org.jetbrains.kotlin.plugin.compose` (Kotlin) | `2.3.20` | Maven Central | `Apache-2.0` | Bytecode compiler chuẩn từ Maven Central | Có | `PASS` |
| `com.google.devtools.ksp` | `2.3.6` | Google Maven / Maven Central | `Apache-2.0` | Bytecode annotation processor chuẩn | Có | `PASS` |
| `com.google.dagger.hilt.android` (Plugin + `hilt-android` + `hilt-android-compiler`) | `2.59.2` | Google Maven / Maven Central | `Apache-2.0` | Bytecode AAR/JAR chuẩn (không chứa `.so`) | Có | `PASS` |
| **`org.mozilla.geckoview:geckoview`** | `150.0.20260511200624` | Mặc định: `https://maven.mozilla.org/maven2/` (`mozilla-firefox/firefox` tag `FIREFOX-ANDROID_150_0_3_RELEASE`, commit `a077abc2b0f43ed7cc59a8bfcd873e683500d23a`); hỗ trợ ghi đè `mavenLocal()` qua `-Pveko.geckoview.useMavenLocal=true` | `MPL-2.0` (POM upstream kéo theo `com.google.android.gms:play-services-fido` cho WebAuthn; kịch bản `scripts/build-geckoview-from-source.sh` tự động thay bằng `org.microg.gms:play-services-fido` `Apache-2.0` khi build từ nguồn) | **CÓ ở chế độ mặc định** — AAR chứa 7 thư viện `.so` biên dịch sẵn (`libxul.so`, `libmozglue.so`, `libnss3.so`, `liblgpllibs.so`, `libfreebl3.so`, `libsoftokn3.so`, `libipcclientcerts.so`) | Chỉ đạt khi biên dịch trước từ `srclibs: MozFennec` sang `mavenLocal()` và xóa `maven.mozilla.org` trong `prebuild:` | **`BLOCKED`** (Đã hoàn thiện kịch bản nguồn `scripts/build-geckoview-from-source.sh` & dọn `scanner` `0 problems found`, chờ chạy thực tế trên F-Droid `buildserver` VM) |
| Cầu nối C++20 JNI (`:privacy` -> `libveko_adblock_jni.so`) | `0.4.0-privacy-shield` | Mã nguồn nội bộ `privacy/src/main/cpp/{veko_shield_pipeline.cc,veko_adblock_jni.cc}` biên dịch trực tiếp qua `scripts/build-adblock-native.sh` (NDK `27.2.12479018` Clang++, căn lề ELF 16 KB, `-ffile-prefix-map`, không cần công cụ sinh build trung gian) | `MPL-2.0` | **Không** (`0` tệp `.so`/`.a` lưu sẵn trong Git; biên dịch 100% từ nguồn C++20) | **Có** (`VEKO_OFFLINE_BUILD=true bash scripts/build-adblock-native.sh`) | `PASS` |
| Crate nội bộ `veko-adblock-ffi` + `adblock` (`brave/adblock-rust`) | `0.1.0` / `=0.13.4` (+ 56 crate bắc cầu) | `privacy/rust/veko-adblock/` + `Cargo.lock` (58 gói khóa cứng SHA-256 từ `crates.io`, Rust `>= 1.86`) | `MPL-2.0` (+ `MIT`, `Apache-2.0`, `Unicode-3.0`, `Unlicense`) | **Không** (biên dịch tĩnh `libveko_adblock_ffi.a` từ nguồn Rust với `--remap-path-prefix`) | **Có** (`scripts/vendor-rust-deps.sh --prepare` + `CARGO_NET_OFFLINE=true cargo build --release --locked --offline`) | `PASS` (Đã khắc phục Blocker 3) |
| `androidx.appcompat:appcompat` | `1.7.1` | Google Maven (`androidx/androidx`) | `Apache-2.0` | AAR bytecode chuẩn (phục vụ chuyển đổi ngôn ngữ `AppCompatDelegate.setApplicationLocales`) | Có | `PASS` |
| `androidx.compose:compose-bom` (`ui`, `ui-graphics`, `ui-tooling-preview`, `material3`, `runtime`) | `2026.03.01` | Google Maven (`androidx/androidx`) | `Apache-2.0` | AAR/JAR bytecode chuẩn (không chứa binary đóng) | Có | `PASS` |
| `androidx.compose.material:material-icons-extended` | `1.7.8` | Google Maven (`androidx/androidx`) | `Apache-2.0` | AAR/JAR bytecode chuẩn | Có | `PASS` |
| `io.coil-kt.coil3:coil-compose` | `3.4.0` | Maven Central (`coil-kt/coil`) | `Apache-2.0` | AAR/JAR bytecode chuẩn (đã tắt mạng ngầm với `CachePolicy.DISABLED`) | Có | `PASS` |
| `androidx.core:core-ktx` | `1.18.0` | Google Maven (`androidx/androidx`) | `Apache-2.0` | AAR bytecode chuẩn | Có | `PASS` |
| `androidx.activity:activity-compose` | `1.13.0` | Google Maven (`androidx/androidx`) | `Apache-2.0` | AAR bytecode chuẩn | Có | `PASS` |
| `androidx.lifecycle:lifecycle-runtime-ktx` / `lifecycle-runtime-compose` / `lifecycle-viewmodel-compose` | `2.10.0` | Google Maven (`androidx/androidx`) | `Apache-2.0` | AAR bytecode chuẩn | Có | `PASS` |
| `androidx.navigation:navigation-compose` | `2.9.8` | Google Maven (`androidx/androidx`) | `Apache-2.0` | AAR bytecode chuẩn | Có | `PASS` |
| `androidx.hilt:hilt-navigation-compose` | `1.3.0` | Google Maven (`androidx/androidx`) | `Apache-2.0` | AAR bytecode chuẩn | Có | `PASS` |
| `androidx.room:room-runtime` / `room-ktx` / `room-compiler` | `2.8.4` | Google Maven (`androidx/androidx`) | `Apache-2.0` | AAR/JAR bytecode chuẩn | Có | `PASS` |
| `androidx.datastore:datastore-preferences` | `1.2.1` | Google Maven (`androidx/androidx`) | `Apache-2.0` | AAR bytecode chuẩn | Có | `PASS` |
| `androidx.annotation:annotation` | `1.9.1` | Google Maven (`androidx/androidx`) | `Apache-2.0` | JAR metadata chuẩn | Có | `PASS` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` / `kotlinx-coroutines-core` | `1.11.0` / `1.10.2` | Maven Central (`Kotlin/kotlinx.coroutines`) | `Apache-2.0` | JAR bytecode chuẩn | Có | `PASS` |
| Phụ thuộc chỉ dùng cho Kiểm thử (`junit:4.13.2`, `kotlinx-coroutines-test:1.11.0`, `org.json:json:20240303`, `snakeyaml-engine:3.0.1`, `jsoup:1.18.3`, `androidx.test.ext:junit:1.3.0`, `espresso-core:3.7.0`, `ui-test-junit4`, `ui-tooling`) | Đa phiên bản đã ghim cố định | Maven Central / Google Maven | `EPL-1.0`, `Apache-2.0`, `MIT` | Không đóng gói vào APK `release` | Có | `PASS` (`testImplementation` / `androidTestImplementation` / `debugImplementation`) |

---

## 2. Phân Tích Chuyên Sâu: Mozilla GeckoView & Offline Rust

### 2.1. Blocker 1 — Đánh Giá 5 Tiêu Chí Của Mozilla GeckoView (`org.mozilla.geckoview:geckoview:150.0.20260511200624`)
1. **Nguồn phân phối hiện tại (Prebuilt AAR vs Source Build)**:
   - Mặc định Gradle tải gói AAR dựng sẵn từ `https://maven.mozilla.org/maven2/`. Phiên bản `150.0.20260511200624` (`buildid = 20260511200624`) ánh xạ trực tiếp tới kho chính thức **`https://github.com/mozilla-firefox/firefox`** tại tag **`FIREFOX-ANDROID_150_0_3_RELEASE`** (commit SHA-1: **`a077abc2b0f43ed7cc59a8bfcd873e683500d23a`**).
   - Chuỗi công cụ yêu cầu nếu tự biên dịch từ cây nguồn Firefox `150.0.3`: Android NDK **`r29`**, Rust **`>= 1.90.0`**, `cbindgen`, Clang/LLVM, Python 3, Node.js và `wasi-sdk-20` (`935fe1acd2fcd7ea4aed2d5ee4527482862b6344`).
2. **Thư viện nhị phân `.so` dựng sẵn bên trong AAR**:
   - **CÓ.** Mỗi gói AAR theo ABI chứa **7 tệp `.so` biên dịch sẵn**: `libxul.so`, `libmozglue.so`, `libnss3.so`, `liblgpllibs.so`, `libfreebl3.so`, `libsoftokn3.so`, `libipcclientcerts.so`.
3. **Khả năng được `fdroiddata` chấp nhận trực tiếp**:
   - **KHÔNG.** `fdroid scanner` báo lỗi ngay với `https://maven.mozilla.org/maven2/` trong `settings.gradle.kts` và không cho phép lấy thư viện native dựng sẵn từ kho bên ngoài.
4. **Trạng thái bắt buộc hiện tại (`BLOCKED`)**:
   - Mặc dù `settings.gradle.kts` và `app/build.gradle.kts` đã hỗ trợ `-Pveko.geckoview.useMavenLocal=true`, `-Pveko.geckoview.artifact=...` và `-Pveko.geckoview.version=...`, quy trình biên dịch toàn bộ `srclibs: MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE` trong máy ảo cô lập của F-Droid vẫn chưa được tích hợp và kiểm chứng đầu-cuối. Do đó trạng thái giữ nguyên là **`BLOCKED`**.
5. **Tác động của việc chuẩn hóa kiến trúc thuần GeckoView**:
   - Dự án chỉ còn duy nhất nhân trình duyệt Mozilla GeckoView và cầu nối `libveko_adblock_jni.so` biên dịch trực tiếp từ nguồn Rust + C++20; điều này loại bỏ hoàn toàn các module và công cụ build dư thừa trước đây nhưng **không được phép dùng làm căn cứ để tuyên bố ứng dụng đã sẵn sàng cho F-Droid**.

### 2.2. Kiểm toán đầy đủ 58 gói Cargo trong `privacy/rust/veko-adblock/Cargo.lock`
- Tệp khóa `privacy/rust/veko-adblock/Cargo.lock` (`version = 4`) khóa cứng `veko-adblock-ffi 0.1.0` cùng **57 crate từ `crates.io`** với mã băm SHA-256 chính thức từ `crates.io-index`.
- Phiên bản Rust tối thiểu thực tế (MSRV) là **`1.86`** (do `icu_* 2.2.0` và `idna_adapter 1.2.2` yêu cầu `rust-version = "1.86"`), đã được khai báo tường minh trong `privacy/rust/veko-adblock/Cargo.toml` và `scripts/setup-native-toolchains.sh`.
- Quy trình chuẩn bị & biên dịch ngoại tuyến:
  ```bash
  # 1. Chuẩn bị vendor ngoại tuyến & kiểm tra khớp 100% SHA-256 từng tệp với Cargo.lock
  bash scripts/vendor-rust-deps.sh --prepare

  # 2. Kiểm tra giấy phép SPDX & chạy cargo test hoàn toàn ngoại tuyến (CARGO_NET_OFFLINE=true --locked --offline)
  VEKO_OFFLINE_BUILD=true bash scripts/vendor-rust-deps.sh --verify-offline

  # 3. Biên dịch thư viện tĩnh Rust + C++20 JNI (libveko_adblock_jni.so) cho Android hoàn toàn ngoại tuyến
  VEKO_OFFLINE_BUILD=true bash scripts/build-adblock-native.sh --mode=android

  # 4. Dọn thư mục tạm vendor/ sau khi build (vendor/ không commit vào Git)
  bash scripts/vendor-rust-deps.sh --clean
  ```

| Dependency | Version | Source | License | Prebuilt binary | Offline build | Status |
| --- | --- | --- | --- | --- | --- | --- |
| `adblock` | `0.13.4` | `crates.io` (`sha256:31200501a8d3e269…`) | `MPL-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `addr` | `0.15.6` | `crates.io` (`sha256:a93b8a41dbe230ad…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `aho-corasick` | `1.1.4` | `crates.io` (`sha256:ddd31a130427c275…`) | `Unlicense OR MIT` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `arrayvec` | `0.7.6` | `crates.io` (`sha256:7c02d123df017efc…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `base64` | `0.22.1` | `crates.io` (`sha256:72b3254f16251a83…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `bitflags` | `2.11.1` | `crates.io` (`sha256:c4512299f36f043a…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `displaydoc` | `0.2.5` | `crates.io` (`sha256:97369cbbc041bc36…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `either` | `1.15.0` | `crates.io` (`sha256:48c757948c5ede0e…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `flatbuffers` | `25.12.19` | `crates.io` (`sha256:35f6839d7b3b98ad…`) | `Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `icu_collections` | `2.2.0` | `crates.io` (`sha256:2984d1cd16c883d7…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `icu_locale_core` | `2.2.0` | `crates.io` (`sha256:92219b62b3e2b4d8…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `icu_normalizer` | `2.2.0` | `crates.io` (`sha256:c56e5ee99d6e3d33…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `icu_normalizer_data` | `2.2.0` | `crates.io` (`sha256:da3be0ae77ea334f…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `icu_properties` | `2.2.0` | `crates.io` (`sha256:bee3b67d0ea5c2cc…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `icu_properties_data` | `2.2.0` | `crates.io` (`sha256:8e2bbb201e0c04f7…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `icu_provider` | `2.2.0` | `crates.io` (`sha256:139c4cf31c8b5f33…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `idna` | `1.1.0` | `crates.io` (`sha256:3b0875f23caa0389…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `idna_adapter` | `1.2.2` | `crates.io` (`sha256:cb68373c0d6620ef…`) | `Apache-2.0 OR MIT` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `itertools` | `0.14.0` | `crates.io` (`sha256:2b192c782037fadd…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `itoa` | `1.0.18` | `crates.io` (`sha256:8f42a60cbdf9a97f…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `litemap` | `0.8.2` | `crates.io` (`sha256:92daf443525c4cce…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `memchr` | `2.8.0` | `crates.io` (`sha256:f8ca58f447f06ed1…`) | `Unlicense OR MIT` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `percent-encoding` | `2.3.2` | `crates.io` (`sha256:9b4f627cb1b25917…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `potential_utf` | `0.1.5` | `crates.io` (`sha256:0103b1cef7ec0cf7…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `proc-macro2` | `1.0.106` | `crates.io` (`sha256:8fd00f0bb2e90d81…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `psl` | `2.1.205` | `crates.io` (`sha256:194b4aac978e4e46…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `psl-types` | `2.0.11` | `crates.io` (`sha256:33cb294fe86a74cb…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `quote` | `1.0.45` | `crates.io` (`sha256:41f2619966050689…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `regex` | `1.12.3` | `crates.io` (`sha256:e10754a14b9137dd…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `regex-automata` | `0.4.14` | `crates.io` (`sha256:6e1dd4122fc1595e…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `regex-syntax` | `0.8.10` | `crates.io` (`sha256:dc897dd8d9e8bd1e…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `rustc-hash` | `2.1.2` | `crates.io` (`sha256:94300abf3f1ae2e2…`) | `Apache-2.0 OR MIT` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `rustc_version` | `0.4.1` | `crates.io` (`sha256:cfcb3a22ef46e85b…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `seahash` | `4.1.0` | `crates.io` (`sha256:1c107b6f4780854c…`) | `MIT` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `semver` | `1.0.28` | `crates.io` (`sha256:8a7852d02fc84898…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `serde` | `1.0.228` | `crates.io` (`sha256:9a8e94ea7f378bd3…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `serde_core` | `1.0.228` | `crates.io` (`sha256:41d385c7d4ca58e5…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `serde_derive` | `1.0.228` | `crates.io` (`sha256:d540f220d3187173…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `serde_json` | `1.0.149` | `crates.io` (`sha256:83fc039473c5595a…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `smallvec` | `1.15.1` | `crates.io` (`sha256:67b1b7a3b5fe4f13…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `stable_deref_trait` | `1.2.1` | `crates.io` (`sha256:6ce2be8dc25455e1…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `syn` | `2.0.117` | `crates.io` (`sha256:e665b8803e7b1d2a…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `synstructure` | `0.13.2` | `crates.io` (`sha256:728a70f3dbaf5bab…`) | `MIT` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `thiserror` | `1.0.69` | `crates.io` (`sha256:b6aaf5339b578ea8…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `thiserror-impl` | `1.0.69` | `crates.io` (`sha256:4fee6c4efc90059e…`) | `MIT OR Apache-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `tinystr` | `0.8.3` | `crates.io` (`sha256:c8323304221c2a85…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `unicode-ident` | `1.0.24` | `crates.io` (`sha256:e6e4313cd5fcd3da…`) | `(MIT OR Apache-2.0) AND Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `utf8_iter` | `1.0.4` | `crates.io` (`sha256:b6c140620e7ffbb2…`) | `Apache-2.0 OR MIT` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `veko-adblock-ffi` | `0.1.0` | In-repo (`privacy/rust/veko-adblock`) | `MPL-2.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `writeable` | `0.6.3` | `crates.io` (`sha256:1ffae5123b2d3fc0…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `yoke` | `0.8.2` | `crates.io` (`sha256:abe8c5fda708d9ca…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `yoke-derive` | `0.8.2` | `crates.io` (`sha256:de844c262c884881…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `zerofrom` | `0.1.7` | `crates.io` (`sha256:69faa1f2a1ea7566…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `zerofrom-derive` | `0.1.7` | `crates.io` (`sha256:11532158c46691ca…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `zerotrie` | `0.2.4` | `crates.io` (`sha256:0f9152d31db0792f…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `zerovec` | `0.11.6` | `crates.io` (`sha256:90f911cbc359ab6a…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `zerovec-derive` | `0.11.3` | `crates.io` (`sha256:625dc425cab0dca6…`) | `Unicode-3.0` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |
| `zmij` | `1.0.21` | `crates.io` (`sha256:b8848ee67ecc8aed…`) | `MIT` | Không (biên dịch từ nguồn Rust) | Có (`Cargo.lock` + `cargo vendor --locked` + `--offline`) | `PASS` |

---

## 3. Tổng Kết Kiểm Tra SDK Độc Quyền & Khối Siêu Dữ Liệu Nhị Phân

- **SDK độc quyền / Theo dõi / Quảng cáo**: **`0`** (`com.google.android.gms`, `com.google.firebase`, `com.android.billingclient`, Sentry, Crashlytics, Adjust, AppsFlyer đều hoàn toàn vắng mặt).
- **Khối Google Play `dependenciesInfo` (`0x504B4453`)**: Đã tắt tường minh trong `app/build.gradle.kts` (`includeInApk = false`, `includeInBundle = false`) để loại bỏ khối mã hóa bằng khóa Google khỏi APK, đáp ứng yêu cầu của `fdroid scanner` và Reproducible Builds.
