# Tài Liệu Môi Trường & Công Thức Biên Dịch Nguồn (F-Droid Build Recipe)

Tài liệu này mô tả chính xác môi trường biên dịch, lệnh tạo APK `release` từ mã nguồn của **Veko Browser** (`io.veko.browser`) và phân biệt rõ giữa **build thành công trên máy trạm / GitHub Actions CI** với **build bên trong máy ảo cô lập của F-Droid (`fdroidserver`)**.

---

## 1. Thông số kỹ thuật Build đã xác minh từ mã nguồn

| Hạng mục | Giá trị thực tế trong kho mã nguồn | Tệp cấu hình gốc |
| --- | --- | --- |
| **Gradle Module xuất APK** | `:app` | `app/build.gradle.kts` (`com.android.application`) |
| **Các Module thư viện nội bộ** | `:browser-core`, `:chromium-embedder`, `:chromium-native`, `:privacy`, `:downloads`, `:storage`, `:security`, `:testing` | `settings.gradle.kts` |
| **Release Build Variant** | `release` (không có `productFlavors`) | `app/build.gradle.kts` (`buildTypes.getByName("release")`) |
| **Application ID (`release`)** | `io.veko.browser` (`preview` dùng `.preview`, `debug` dùng `.debug`) | `app/build.gradle.kts` |
| **Lệnh biên dịch Release chuẩn** | `./gradlew :app:assembleRelease` | `.github/workflows/build-apk.yml` |
| **JDK** | JDK `17` (`JavaVersion.VERSION_17` / `JvmTarget.JVM_17`) | `app/build.gradle.kts` |
| **Gradle Wrapper** | Gradle `9.4.0` (`gradle-9.4.0-bin.zip`) | `gradle/wrapper/gradle-wrapper.properties` |
| **Android Gradle Plugin (AGP)** | `9.1.1` (Kotlin Compose plugin `2.3.20`, KSP `2.3.6`, Hilt `2.59.2`) | `build.gradle.kts` |
| **Android SDK** | `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26` | `app/build.gradle.kts` |
| **Android NDK** | `27.2.12479018` (dùng Clang++ & `llvm-ar` để biên dịch C++20 JNI + liên kết Rust) | `scripts/setup-native-toolchains.sh`, `scripts/build-native-gn.sh` |
| **Công cụ Native bắt buộc** | Rust `>= 1.85` (`cargo` + targets `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`), `gn` (`generate-ninja`), `ninja` (`ninja-build`), `python3` | `scripts/setup-native-toolchains.sh`, `chromium-native/build.gradle.kts` |
| **Kiến trúc ABI đầu ra** | `arm64-v8a`, `armeabi-v7a`, `x86_64` (`splits.abi.isEnable = true`, `isUniversalApk = false`) | `app/build.gradle.kts` |
| **Đường dẫn APK đầu ra (`release`)** | `app/build/outputs/apk/release/app-arm64-v8a-release.apk`<br>`app/build/outputs/apk/release/app-armeabi-v7a-release.apk`<br>`app/build/outputs/apk/release/app-x86_64-release.apk` (hoặc `*-release-unsigned.apk` khi không cấu hình `signingConfig`) | `app/build.gradle.kts`, `.github/workflows/build-apk.yml` |

---

## 2. Phân biệt trạng thái Build thực tế

### 2.1. Build trên GitHub Actions CI & Máy trạm Linux: **`VERIFIED / PASS`**
- Trên GitHub Actions (`build-apk.yml`, Ubuntu Linux x86_64 có kết nối mạng tới Google Maven, Maven Central, Mozilla Maven và `crates.io`):
  - **Bản `v0.2.0` (`Run #41222802614`)**: Đã biên dịch thành công `:app:assembleRelease`, ký số bằng khóa riêng, kiểm tra qua `apksigner` và xuất 3 gói APK chính thức kèm `SHA256SUMS.txt`.
  - **Nhánh `arena/d43b7af7-zeko` (`Run #41268169177`)**: Đã biên dịch thành công Rust `adblock-rust` + C++20 JNI 16 KB ELF, vượt qua 58/58 bài JUnit, `lintDebug` (0 lỗi), xuất 6 gói ABI (`debug` + `preview` R8) và vượt qua 10/10 bài kiểm thử thực tế trên máy ảo Android 14 (API 34).

### 2.2. Build trong môi trường máy ảo cô lập F-Droid (`fdroid build`): **`BUILD BLOCKED`**

Tuân thủ nghiêm ngặt quy định của F-Droid (không dùng `debug`/`preview` APK, không tải APK dựng sẵn từ GitHub Releases, không tự ý tắt cổng bảo mật trong mã nguồn để giả lập kết quả), trạng thái biên dịch trong `fdroidserver` hiện tại là **`BUILD BLOCKED`** vì **4 nguyên nhân kỹ thuật xác thực**:

1. **Chốt chặn `verifyReleaseSigningConfig` trên tác vụ `assembleRelease` (`app/build.gradle.kts:167-211`)**:
   - Mã nguồn hiện gắn `dependsOn("verifyReleaseSigningConfig")` vào mọi tác vụ `*Release` và ném ngoại lệ `GradleException("Official Release signing is not configured...")` khi thiếu các biến môi trường `VEKO_KEYSTORE_FILE`, `VEKO_KEYSTORE_PASSWORD`, `VEKO_KEY_ALIAS`, `VEKO_KEY_PASSWORD`.
   - Máy chủ build của F-Droid luôn chạy trong môi trường không có khóa ký của lập trình viên để tạo ra `*-release-unsigned.apk` rồi ký trên máy chủ cách ly của F-Droid. Do đó `fdroid build` chạy trên tag `v0.2.0` sẽ dừng ngay tại `verifyReleaseSigningConfig`.
   - *Cách giải quyết chuẩn cho bản phát hành kế tiếp*: Giữ nguyên chốt chặn bảo mật mặc định trên CI, nhưng bổ sung cờ tường minh chỉ tạo gói `unsigned` (ví dụ `-PfdroidUnsignedRelease=true` khi không gắn `signingConfig`), đồng thời cập nhật bài kiểm thử bảo mật tương ứng trước khi gắn tag `v0.3.0`.

2. **Phụ thuộc AAR nhị phân biên dịch sẵn `org.mozilla.geckoview:geckoview` từ `maven.mozilla.org`**:
   - Kho chính `fdroiddata` không chấp nhận tải thư viện native `.so` biên dịch sẵn (`libxul.so`) từ kho Maven bên thứ ba mà yêu cầu biên dịch GeckoView từ mã nguồn Firefox thông qua `srclibs:` trong công thức YAML.

3. **Chưa có `Cargo.lock` & Cấu hình Cargo ngoại tuyến cho `privacy/rust/veko-adblock`**:
   - Trong bước `build:` của F-Droid, truy cập Internet bị ngắt hoàn toàn. Tác vụ `scripts/build-native-gn.sh --mode=android` gọi `cargo build --release --target <triple>` sẽ thất bại nếu các crate phụ thuộc của `adblock = "=0.13.4"` chưa được khóa bằng `Cargo.lock` và tải trước/vendor trong bước `fetch`/`prebuild`.

4. **Tag `v0.2.0` được tạo trước khi bổ sung tệp `LICENSE` (`MPL-2.0`) và cần tăng `versionCode`**:
   - Tag `v0.2.0` (`a3ac73718e9f690b7f334ff89897f4d97f4a96ea`) chưa chứa tệp `LICENSE` ở thư mục gốc (`LICENSE` mới được thêm tại commit `7d601482fb020a0a9abfe600828e6da2643b7075`). Cần phát hành tag mới (ví dụ `v0.3.0` với `versionCode = 2`) bao gồm đầy đủ `LICENSE`, `Cargo.lock` và cờ hỗ trợ `unsigned release`.

---

## 3. Hướng dẫn tự biên dịch đầy đủ từ mã nguồn trên máy trạm Linux

```bash
# 1. Sao chép kho mã nguồn
git clone https://github.com/TienHeavenVN/Veko.git
cd Veko

# 2. Cài đặt chuỗi công cụ native (GN, Ninja, Rust >= 1.85, Android targets & NDK 27.2.12479018)
bash scripts/setup-native-toolchains.sh --with-android

# 3. Biên dịch & chạy kiểm thử Rust FFI + C++20 host + JUnit + Android Lint
./gradlew :app:testDebugUnitTest :app:lintDebug

# 4a. Biên dịch gói thử nghiệm tối ưu R8 (không cần khóa ký phát hành riêng)
./gradlew :app:assemblePreview

# 4b. Biên dịch gói Release chính thức (yêu cầu cung cấp keystore phát hành hợp lệ)
export VEKO_KEYSTORE_FILE="/path/to/release.jks"
export VEKO_KEYSTORE_PASSWORD="..."
export VEKO_KEY_ALIAS="..."
export VEKO_KEY_PASSWORD="..."
./gradlew :app:assembleRelease
```
