# F-Droid Metadata, Audit & Build Remediation — Veko Browser

Thư mục `metadata/fdroid/` lưu trữ hồ sơ kiểm toán phụ thuộc, quy trình biên dịch ngoại tuyến, hướng dẫn xuất bản và tệp công thức chuẩn bị cho việc đưa **Veko Browser** (`io.veko.browser`) lên hệ sinh thái **F-Droid** (`https://gitlab.com/fdroid/fdroiddata`).

> **Lưu ý quan trọng về vị trí tệp YAML**:
> - Tệp `metadata/fdroid/io.veko.browser.yml` trong kho mã nguồn Veko là **bản chuẩn bị nội bộ (staging template)** đã được chuẩn hóa bằng `fdroid rewritemeta` và xác thực qua `fdroid lint --format --force-yamllint` (`fdroidserver 2.4.5`).
> - Máy chủ `fdroidserver` chính thức **không tự đọc** tệp YAML từ repo ứng dụng; khi mọi cổng điều kiện kỹ thuật hoàn tất, tệp này phải được đặt tại **`metadata/io.veko.browser.yml`** trong fork của **`https://gitlab.com/fdroid/fdroiddata`**.

---

## 1. Thông Tin Định Danh & Cấu Hình Đã Xác Minh Từ Mã Nguồn

| Thuộc tính | Giá trị xác minh | Nguồn đối chiếu trong mã nguồn |
| --- | --- | --- |
| **Application ID** | `io.veko.browser` | `app/build.gradle.kts` (`defaultConfig.applicationId = "io.veko.browser"`) |
| **App Name** | `Veko` (`AutoName: Veko Browser`) | `app/build.gradle.kts` (`manifestPlaceholders["appLabel"] = "Veko"`), `app/src/main/AndroidManifest.xml` |
| **Repository URL** | `https://github.com/TienHeavenVN/Veko` (alias chuyển hướng: `https://github.com/TienHeavenVN/Zeko`) | `git remote -v` & GitHub API (`full_name: TienHeavenVN/Veko`) |
| **Website chính thức** | `https://tiendayy.kdns.fr` | `CNAME`, `website/astro.config.mjs` |
| **License** | `MPL-2.0` (Mozilla Public License Version 2.0) | Tệp `LICENSE` tại thư mục gốc (`7d601482fb020a0a9abfe600828e6da2643b7075`) & `privacy/rust/veko-adblock/Cargo.toml` |
| **Git Tag đã phát hành** | `v0.2.0` (`a3ac73718e9f690b7f334ff89897f4d97f4a96ea`) | `versionName = "0.2.0-chromium-arch"`, `versionCode = 1` (trước khi có tệp `LICENSE`; giữ nguyên lịch sử không sửa đổi) |
| **Phiên bản nhánh nguồn hiện tại** | `versionName = "0.3.0-dual-engine"`, `versionCode = 2` (chưa gắn tag `v0.3.0`) | `app/build.gradle.kts` (đã tăng `versionCode` lên `2`; chưa cắt tag `v0.3.0` khi còn Blocker 1) |
| **SDK Levels** | `minSdk = 26` • `targetSdk = 36` • `compileSdk = 37` | `app/build.gradle.kts` |
| **Gradle Module tạo APK** | `:app` (trong tổng số 9 module Gradle Kotlin DSL) | `settings.gradle.kts`, `app/build.gradle.kts` |
| **Release Build Variant** | `release` (không dùng `productFlavors`; bật ABI split; đã tắt `dependenciesInfo`) | `app/build.gradle.kts` |
| **Lệnh Release Unsigned (F-Droid)** | `./gradlew :app:assembleRelease -PfdroidUnsignedRelease=true` | `app/build.gradle.kts` (tạo 3 tệp `app-<abi>-release-unsigned.apk`, tách biệt khỏi luồng khóa ký chính thức) |
| **Toolchain yêu cầu** | JDK `17`, Gradle `9.4.0`, AGP `9.1.1`, Kotlin `2.3.20`, Android NDK `27.2.12479018`, Rust `>= 1.86`, `gn`, `ninja`, `python3` | `build.gradle.kts`, `privacy/rust/veko-adblock/Cargo.toml`, `scripts/setup-native-toolchains.sh` |
| **Supported Architectures** | `arm64-v8a`, `armeabi-v7a`, `x86_64` (`isUniversalApk = false`, căn lề trang ELF 16 KB) | `app/build.gradle.kts`, `scripts/build-native-gn.sh`, `scripts/verify-elf-packaging.py` |
| **Vị trí YAML đích trên `fdroiddata`** | `metadata/io.veko.browser.yml` trong `https://gitlab.com/fdroid/fdroiddata` | Chuẩn cấu trúc của `fdroidserver` |

---

## 2. Tóm Tắt Kết Quả Kiểm Toán & Khắc Phục 4 Điểm Nghẽn F-Droid

| Blocker | Hạng mục kiểm toán | Trạng thái hiện tại | Tóm tắt kỹ thuật |
| --- | --- | --- | --- |
| **Blocker 1** | `org.mozilla.geckoview:geckoview:150.0.20260511200624` (AAR từ `maven.mozilla.org`) | **`BLOCKED`** | Giấy phép `MPL-2.0` hợp lệ nhưng gói AAR mặc định chứa 7 tệp `.so` biên dịch sẵn (`libxul.so`, `libmozglue.so`, `libnss3.so`, `liblgpllibs.so`, `libfreebl3.so`, `libsoftokn3.so`, `libipcclientcerts.so`). Đã xác định chính xác commit nguồn Firefox `FIREFOX-ANDROID_150_0_3_RELEASE` (`a077abc2b0f43ed7cc59a8bfcd873e683500d23a`) và bổ sung cờ `-Pveko.geckoview.useMavenLocal=true` để sẵn sàng nối với `srclibs: MozFennec`. |
| **Blocker 2** | Chốt chặn ký số `verifyReleaseSigningConfig` trong `app/build.gradle.kts` | **`PASS` (Đã khắc phục)** | Bổ sung chế độ tường minh `-PfdroidUnsignedRelease=true` (`verifyFdroidUnsignedReleaseMode` + tắt `dependenciesInfo` `0x504B4453`) xuất ra 3 gói `app-<abi>-release-unsigned.apk` sạch, trong khi vẫn giữ nguyên cổng chặn fail-fast cho bản `assembleRelease` mặc định của nhà phát triển. |
| **Blocker 3** | Khóa phụ thuộc Rust `Cargo.lock` & biên dịch ngoại tuyến | **`PASS` (Đã khắc phục)** | Đã tạo & commit `privacy/rust/veko-adblock/Cargo.lock` (58 gói, SHA-256 xác thực từ `crates.io`), khai báo `rust-version = "1.86"`, thêm `scripts/vendor-rust-deps.sh` và ép `CARGO_NET_OFFLINE=true` (`--locked --offline`) trong `scripts/build-native-gn.sh`. |
| **Blocker 4** | Tệp `LICENSE`, tăng `versionCode` & Git Tag phát hành | **`BLOCKED` (Chờ cắt tag `v0.3.0`)** | Tệp gốc `LICENSE` (`MPL-2.0`) đã đầy đủ; `versionCode` đã tăng từ `1` lên **`2`** cho `0.3.0-dual-engine`; tag lịch sử `v0.2.0` được giữ nguyên; chưa tạo tag `v0.3.0` và giữ `CurrentVersion`/`CurrentVersionCode` neo ở `v0.2.0` cho tới khi hoàn tất Blocker 1 trên `main`. |

Chi tiết đầy đủ xem tại [`DEPENDENCIES.md`](./DEPENDENCIES.md), [`BUILD.md`](./BUILD.md) và [`PUBLISHING.md`](./PUBLISHING.md).

---

## 3. Cấu Trúc Thư Mục `metadata/fdroid/`

```text
metadata/
└── fdroid/
    ├── io.veko.browser.yml   # Bản staging YAML đã qua `fdroid rewritemeta` & `fdroid lint --format`
    ├── README.md             # Tổng quan định danh, bảng 4 blockers & trạng thái F-Droid
    ├── PUBLISHING.md         # Cổng 10 điều kiện xuất bản, hướng dẫn chụp PNG thật & lệnh fdroidserver
    ├── DEPENDENCIES.md       # Bảng kiểm toán đầy đủ Gradle/Maven/Native + 58 crate Rust trong Cargo.lock
    ├── BUILD.md              # Công thức biên dịch ngoại tuyến, unsigned APK & phân biệt 3 môi trường build
    └── screenshots/          # 4 ảnh chụp PNG thực tế từ Android 14 Emulator (không EXIF, không SVG mockup)
```
