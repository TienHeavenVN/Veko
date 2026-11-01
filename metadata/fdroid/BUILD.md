# Tài Liệu Môi Trường & Công Thức Biên Dịch Nguồn (F-Droid Build & Remediation Guide)

Tài liệu này mô tả chính xác môi trường biên dịch, cách tạo APK `release` chưa ký (`unsigned`) cho F-Droid, quy trình biên dịch Rust hoàn toàn ngoại tuyến (`--locked --offline`), và **phân biệt rạch ròi 3 môi trường build**:
1. **GitHub Actions CI build**
2. **Local reproducible / offline-Rust unsigned build**
3. **F-Droid isolated VM build (`fdroid build --on-server`)**

---

## 1. Thông Số Kỹ Thuật Build Đã Xác Minh Từ Mã Nguồn

| Hạng mục | Giá trị thực tế trong kho mã nguồn | Tệp cấu hình gốc |
| --- | --- | --- |
| **Gradle Module xuất APK** | `:app` (cùng 8 module thư viện: `:browser-core`, `:chromium-embedder`, `:chromium-native`, `:privacy`, `:downloads`, `:storage`, `:security`, `:testing`) | `settings.gradle.kts`, `app/build.gradle.kts` |
| **Release Build Variant** | `release` (không sử dụng `productFlavors`, bật ABI splits) | `app/build.gradle.kts` |
| **Application ID (`release`)** | `io.veko.browser` (`preview`: `io.veko.browser.preview`, `debug`: `io.veko.browser.debug`) | `app/build.gradle.kts` |
| **Version trên nhánh nguồn hiện tại** | `versionCode = 2`, `versionName = "0.3.0-dual-engine"` (đã tăng `> 1` so với tag `v0.2.0`; **chưa tạo tag `v0.3.0`**) | `app/build.gradle.kts` |
| **Lệnh Release chính thức (do Dev ký)** | `./gradlew :app:assembleRelease` (bắt buộc đủ 4 biến `VEKO_KEYSTORE_*`, thiếu sẽ dừng ngay tại `verifyReleaseSigningConfig`) | `app/build.gradle.kts` |
| **Lệnh Release Unsigned cho F-Droid** | `./gradlew :app:assembleRelease -PfdroidUnsignedRelease=true` (cấm truyền `VEKO_KEYSTORE_*`, tắt `dependenciesInfo` `0x504B4453`) | `app/build.gradle.kts` |
| **JDK / Gradle / AGP / Kotlin** | JDK `17`, Gradle `9.4.0`, AGP `9.1.1`, Kotlin `2.3.20`, KSP `2.3.6`, Hilt `2.59.2` | `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties` |
| **Android SDK & NDK** | `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`, Android NDK `27.2.12479018` | `app/build.gradle.kts`, `scripts/setup-native-toolchains.sh` |
| **Công cụ Native & Rust MSRV** | Rust `>= 1.86` (khớp MSRV của `icu_* 2.2.0` & `idna_adapter 1.2.2`), targets `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, `gn` (`generate-ninja`), `ninja`, `python3` | `privacy/rust/veko-adblock/Cargo.toml`, `scripts/setup-native-toolchains.sh`, `scripts/build-native-gn.sh` |
| **Kiến trúc ABI & Căn lề ELF** | `arm64-v8a`, `armeabi-v7a`, `x86_64` (`isUniversalApk = false`, ELF `PT_LOAD` 16 KB `max-page-size=16384`) | `app/build.gradle.kts`, `BUILD.gn`, `scripts/verify-elf-packaging.py` |
| **Đầu ra APK Unsigned (`-PfdroidUnsignedRelease=true`)** | `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`<br>`app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk`<br>`app/build/outputs/apk/release/app-x86_64-release-unsigned.apk` | `app/build.gradle.kts`, `.github/workflows/build-apk.yml` |

---

## 2. Phân Biệt Rạch Ròi 3 Môi Trường Build (Không Đánh Đồng)

| Môi trường Build | Trạng thái | Những gì thực sự được kiểm chứng | Giới hạn còn lại |
| --- | --- | --- | --- |
| **1. GitHub Actions CI Build (`build-apk.yml`)** | **`PASS`** | Kiểm tra `fdroidserver` (`readmeta` + `lint --format`), vendor ngoại tuyến 57 crate Rust từ `Cargo.lock` + chạy `CARGO_NET_OFFLINE=true`, biên dịch C++20 JNI 16 KB ELF, chạy JUnit + `lintDebug`, xác minh cổng chặn `verifyReleaseSigningConfig`, xuất & kiểm định cả 3 APK `*-release-unsigned.apk` (`versionCode=2`, không chữ ký, không khối `0x504B4453`), chạy máy ảo Android 14 API 34. | CI vẫn tải `org.mozilla.geckoview:geckoview:150.0.20260511200624` AAR từ `maven.mozilla.org` trong pha phân giải Gradle (không biên dịch toàn bộ cây nguồn Firefox 150). |
| **2. Local Reproducible / Offline-Rust Unsigned Build** | **`PASS` (mức nguồn ứng dụng)** | Hỗ trợ chuẩn hóa đường dẫn nhị phân (`--remap-path-prefix` cho Rust và `-ffile-prefix-map` cho C++20 GN/Ninja), tắt `dependenciesInfo`, biên dịch Rust/C++20 với `VEKO_OFFLINE_BUILD=true` (`CARGO_NET_OFFLINE=true`), và hỗ trợ `-Pveko.geckoview.useMavenLocal=true` nếu người dùng đã tự build `MozFennec` vào `~/.m2/repository`. | Nếu máy trạm chưa tự biên dịch Firefox `FIREFOX-ANDROID_150_0_3_RELEASE` sang `mavenLocal()`, Gradle mặc định vẫn lấy AAR GeckoView từ `maven.mozilla.org`. |
| **3. F-Droid Isolated VM Build (`fdroid build --on-server`)** | **`BLOCKED`** | Công thức staging `metadata/fdroid/io.veko.browser.yml` đã vượt qua `fdroid readmeta` và `fdroid lint --format --force-yamllint` (`0` cảnh báo, `0` lỗi), đồng thời `fdroid scanner` không còn lỗi thiếu `Cargo.lock`. | **Chưa thể hoàn tất `fdroid build`** vì: (1) chưa tích hợp & chạy thực tế `srclibs: MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE` trong VM của F-Droid để thay AAR `maven.mozilla.org`, và (2) chưa cắt tag `v0.3.0` (`versionCode=2`) trên `main`. |

---

## 3. Chi Tiết Trạng Thái 4 F-Droid Blockers Sau Khi Khắc Phục Mã Nguồn

1. **Blocker 1 — GeckoView Prebuilt AAR (`BLOCKED` cho `fdroiddata` chính thức, đã mở sẵn cổng `mavenLocal` trong Gradle)**:
   - Mặc định: `org.mozilla.geckoview:geckoview:150.0.20260511200624` từ `https://maven.mozilla.org/maven2/` (chứa 7 tệp `.so` biên dịch sẵn của Mozilla, khớp mã nguồn `mozilla-firefox/firefox` tag `FIREFOX-ANDROID_150_0_3_RELEASE` / commit `a077abc2b0f43ed7cc59a8bfcd873e683500d23a`).
   - Đã bổ sung vào `settings.gradle.kts` và `app/build.gradle.kts`:
     - `-Pveko.geckoview.useMavenLocal=true` (ngắt `maven.mozilla.org`, chỉ dùng `mavenLocal()` cho group `org.mozilla.geckoview`, tương thích `-Dmaven.repo.local=...`).
     - `-Pveko.geckoview.artifact=<artifact>` và `-Pveko.geckoview.version=<version>`.
   - Công thức `srclibs` dự kiến khi chuyển từ staging sang `fdroiddata` thực tế (sau khi gắn tag `v0.3.0`):
     ```yaml
     srclibs:
       - MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE
     prebuild:
       - sed -i '/maven\.mozilla\.org/,+4d' ../settings.gradle.kts
       - pushd $$MozFennec$$
       - ./mach build
       - ./mach gradle geckoview:publishWithGeckoBinariesReleasePublicationToMavenLocal
       - popd
       - bash ../scripts/vendor-rust-deps.sh --prepare
     gradleprops:
       - fdroidUnsignedRelease=true
       - veko.geckoview.useMavenLocal=true
       - veko.geckoview.artifact=geckoview-default
     ```

2. **Blocker 2 — Release Signing Enforcement (`REMEDIATED / PASS` trong mã nguồn & CI)**:
   - Giữ nguyên tuyệt đối cổng bảo vệ mặc định `verifyReleaseSigningConfig` cho `./gradlew :app:assembleRelease` và `./gradlew :app:bundleRelease` (từ chối build nếu thiếu `VEKO_KEYSTORE_*`, không bao giờ rơi về khóa debug).
   - Bổ sung chế độ tường minh `-PfdroidUnsignedRelease=true` gắn với `verifyFdroidUnsignedReleaseMode` (báo lỗi nếu vô tình tồn tại biến `VEKO_KEYSTORE_*`), tắt `signingConfig` và tắt khối `dependenciesInfo` (`includeInApk = false`, `includeInBundle = false`), tạo ra 3 gói `app-{arm64-v8a,armeabi-v7a,x86_64}-release-unsigned.apk` sạch.

3. **Blocker 3 — Rust `Cargo.lock` & Offline Cargo Build (`REMEDIATED / PASS` trong mã nguồn & CI)**:
   - Đã tạo và commit `privacy/rust/veko-adblock/Cargo.lock` (`version = 4`, 58 gói, toàn bộ checksum SHA-256 chính thức từ `crates.io-index`).
   - Đã cập nhật `rust-version = "1.86"` trong `Cargo.toml`, thêm `scripts/vendor-rust-deps.sh` (`--prepare`, `--verify-offline`, `--clean`) và ép `scripts/build-native-gn.sh` luôn dùng `--locked` kèm `CARGO_NET_OFFLINE=true --offline` khi có `vendor/` hoặc `VEKO_OFFLINE_BUILD=true`.

4. **Blocker 4 — Root `LICENSE`, `versionCode` & Git Tag (`PARTIALLY REMEDIATED — BLOCKED chờ cắt tag v0.3.0`)**:
   - Tệp `LICENSE` (`MPL-2.0`) ở gốc repo đã được kiểm tra nguyên vẹn; `versionCode` trong `app/build.gradle.kts` đã tăng từ `1` lên **`2`** cho `0.3.0-dual-engine`.
   - Tag cũ `v0.2.0` (`a3ac73718e9f690b7f334ff89897f4d97f4a96ea`) được bảo toàn nguyên trạng (không ghi đè, không dịch chuyển).
   - Tag `v0.3.0` **chưa được tạo có chủ đích** cho tới khi nhánh này gộp vào `main` và hoàn tất tích hợp build nguồn GeckoView cho F-Droid.

---

## 4. Hướng Dẫn Lệnh Tự Biên Dịch Từ Mã Nguồn Trên Máy Trạm Linux

```bash
# 1. Cài đặt chuỗi công cụ native (GN, Ninja, Rust >= 1.86, Android targets & NDK 27.2.12479018)
bash scripts/setup-native-toolchains.sh --with-android

# 2. Chuẩn bị & kiểm định gói Rust hoàn toàn ngoại tuyến (khớp 100% SHA-256 với Cargo.lock)
bash scripts/vendor-rust-deps.sh --prepare
VEKO_OFFLINE_BUILD=true bash scripts/vendor-rust-deps.sh --verify-offline

# 3. Biên dịch C++20 JNI + Rust ngoại tuyến & tạo 3 gói APK Release Unsigned chuẩn F-Droid
VEKO_OFFLINE_BUILD=true ./gradlew :app:assembleRelease -PfdroidUnsignedRelease=true --no-daemon

# 4. Kiểm tra chữ ký (phải báo chưa ký) & kiểm tra căn lề ELF 16 KB
python3 scripts/verify-elf-packaging.py \
  --check-apk app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk \
  --expected-abi arm64-v8a \
  --require-page-size 16384

# 5. Dọn dẹp thư mục tạm vendor/ sau khi build
bash scripts/vendor-rust-deps.sh --clean
```
