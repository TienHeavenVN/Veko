# Tài Liệu Môi Trường & Công Thức Biên Dịch Nguồn (F-Droid Build & Remediation Guide)

Tài liệu này mô tả chính xác môi trường biên dịch của kiến trúc **thuần Mozilla GeckoView**, cách tạo APK `release` chưa ký (`unsigned`) cho F-Droid, quy trình biên dịch Rust + C++20 JNI hoàn toàn ngoại tuyến (`--locked --offline` -> `libveko_adblock_jni.so`), và **phân biệt rạch ròi 3 môi trường build**:
1. **GitHub Actions CI build**
2. **Local reproducible / offline-Rust unsigned build**
3. **F-Droid isolated VM build (`fdroid build --on-server`)**

---

## 1. Thông Số Kỹ Thuật Build Đã Xác Minh Từ Mã Nguồn

| Hạng mục | Giá trị thực tế trong kho mã nguồn | Tệp cấu hình gốc |
| --- | --- | --- |
| **Gradle Module xuất APK** | `:app` (cùng **6 module thư viện**: `:browser-core`, `:privacy`, `:downloads`, `:storage`, `:security`, `:testing` — tổng **7 module**) | `settings.gradle.kts`, `app/build.gradle.kts` |
| **Release Build Variant** | `release` (không sử dụng `productFlavors`, bật ABI splits) | `app/build.gradle.kts` |
| **Application ID (`release`)** | `io.veko.browser` (`preview`: `io.veko.browser.preview`, `debug`: `io.veko.browser.debug`) | `app/build.gradle.kts` |
| **Version trên nhánh nguồn hiện tại** | `versionCode = 3`, `versionName = "0.4.0-privacy-shield"` | `app/build.gradle.kts` |
| **Lệnh Release chính thức (do Dev ký)** | `./gradlew :app:assembleRelease` (bắt buộc đủ 4 biến `VEKO_KEYSTORE_*`, thiếu sẽ dừng ngay tại `verifyReleaseSigningConfig`) | `app/build.gradle.kts` |
| **Lệnh Release Unsigned cho F-Droid** | `./gradlew :app:assembleRelease -PfdroidUnsignedRelease=true` (cấm truyền `VEKO_KEYSTORE_*`, tắt `dependenciesInfo` `0x504B4453`) | `app/build.gradle.kts` |
| **JDK / Gradle / AGP / Kotlin** | JDK `17`, Gradle `9.4.0`, AGP `9.1.1`, Kotlin `2.3.20`, KSP `2.3.6`, Hilt `2.59.2` | `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties` |
| **Android SDK & NDK** | `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`, Android NDK `27.2.12479018` | `app/build.gradle.kts`, `scripts/setup-native-toolchains.sh` |
| **Công cụ Native & Rust MSRV** | Rust `>= 1.86` (khớp MSRV của `icu_* 2.2.0` & `idna_adapter 1.2.2`), targets `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, Android NDK `clang++`, `python3` (không còn phụ thuộc công cụ sinh build ngoài) | `privacy/rust/veko-adblock/Cargo.toml`, `scripts/setup-native-toolchains.sh`, `scripts/build-adblock-native.sh` |
| **Kiến trúc ABI & Căn lề ELF** | `arm64-v8a`, `armeabi-v7a`, `x86_64` (`isUniversalApk = false`, ELF `PT_LOAD` 16 KB `max-page-size=16384`) | `app/build.gradle.kts`, `scripts/build-adblock-native.sh`, `scripts/verify-elf-packaging.py` |
| **Đầu ra APK Unsigned (`-PfdroidUnsignedRelease=true`)** | `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`<br>`app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk`<br>`app/build/outputs/apk/release/app-x86_64-release-unsigned.apk` | `app/build.gradle.kts`, `.github/workflows/build-apk.yml` |

---

## 2. Phân Biệt Rạch Ròi 3 Môi Trường Build (Không Đánh Đồng)

| Môi trường Build | Trạng thái | Những gì thực sự được kiểm chứng | Giới hạn còn lại |
| --- | --- | --- | --- |
| **1. GitHub Actions CI Build (`build-apk.yml`)** | **`PASS`** | Kiểm tra `fdroidserver` (`readmeta` + `lint --format --force-yamllint`), vendor ngoại tuyến 57 crate Rust từ `Cargo.lock` + chạy `CARGO_NET_OFFLINE=true`, biên dịch C++20 JNI `libveko_adblock_jni.so` căn lề 16 KB ELF, chạy JUnit 7 module + `lintDebug`, xác minh cổng chặn `verifyReleaseSigningConfig`, xuất & kiểm định cả 3 APK `*-release-unsigned.apk`, chạy máy ảo Android 14 API 34. | CI vẫn tải `org.mozilla.geckoview:geckoview:150.0.20260511200624` AAR từ `maven.mozilla.org` trong pha phân giải Gradle (không biên dịch toàn bộ cây nguồn Firefox 150). |
| **2. Local Reproducible / Offline-Rust Unsigned Build** | **`PASS` (mức nguồn ứng dụng)** | Hỗ trợ chuẩn hóa đường dẫn nhị phân (`--remap-path-prefix` cho Rust và `-ffile-prefix-map` cho C++20 NDK Clang++), tắt `dependenciesInfo`, biên dịch Rust/C++20 với `VEKO_OFFLINE_BUILD=true` (`CARGO_NET_OFFLINE=true`), và hỗ trợ `-Pveko.geckoview.useMavenLocal=true` nếu người dùng đã tự build `MozFennec` vào `~/.m2/repository`. | Nếu máy trạm chưa tự biên dịch Firefox `FIREFOX-ANDROID_150_0_3_RELEASE` sang `mavenLocal()`, Gradle mặc định vẫn lấy AAR GeckoView từ `maven.mozilla.org`. |
| **3. F-Droid Isolated VM Build (`fdroid build --on-server`)** | **`BLOCKED`** | Công thức staging `metadata/fdroid/io.veko.browser.yml` vượt qua `fdroid readmeta` và `fdroid lint --format --force-yamllint` (`0` cảnh báo, `0` lỗi), đồng thời toàn bộ phần native nội bộ (`veko-adblock-ffi` + `libveko_adblock_jni.so`) biên dịch 100% ngoại tuyến từ nguồn. | **Chưa thể hoàn tất `fdroid build`** vì chưa tích hợp & kiểm chứng thực tế `srclibs: MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE` trong VM của F-Droid để thay thế gói AAR chứa 7 tệp `.so` dựng sẵn từ `maven.mozilla.org`. |

---

## 3. Công Thức `srclibs: MozFennec` Dự Kiến Để Mở Khóa Blocker 1

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

---

## 4. Hướng Dẫn Lệnh Tự Biên Dịch Từ Mã Nguồn Trên Máy Trạm Linux

```bash
# 1. Cài đặt chuỗi công cụ native (Rust >= 1.86, Android targets & NDK 27.2.12479018)
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
