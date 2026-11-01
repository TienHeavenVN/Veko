# F-Droid Metadata & Packaging Preparation — Veko Browser

Thư mục `metadata/fdroid/` lưu trữ hồ sơ kiểm toán kỹ thuật, bảng phụ thuộc, công thức biên dịch từ mã nguồn và bản nháp metadata chuẩn bị cho việc đóng gói **Veko Browser** lên hệ sinh thái **F-Droid** (`https://gitlab.com/fdroid/fdroiddata`).

> **Lưu ý quan trọng về vị trí tệp YAML**:
> - Tệp `metadata/fdroid/io.veko.browser.yml` trong kho mã nguồn này chỉ là **bản chuẩn bị nội bộ (staging template)**.
> - Máy chủ `fdroidserver` chính thức **không tự đọc** tệp YAML này từ repo ứng dụng. Khi mọi điều kiện kỹ thuật đã hoàn tất, tệp YAML phải được đặt tại đường dẫn chính xác **`metadata/io.veko.browser.yml`** bên trong fork của kho **`https://gitlab.com/fdroid/fdroiddata`**.

---

## 1. Thông tin định danh ứng dụng đã xác minh từ mã nguồn

| Thuộc tính | Giá trị xác minh | Nguồn đối chiếu trong mã nguồn |
| --- | --- | --- |
| **Application ID** | `io.veko.browser` | `app/build.gradle.kts` (`defaultConfig.applicationId = "io.veko.browser"`) |
| **App Name** | `Veko` (tên đầy đủ: `Veko Browser`) | `app/build.gradle.kts` (`manifestPlaceholders["appLabel"] = "Veko"`), `app/src/main/AndroidManifest.xml` |
| **Repository URL** | `https://github.com/TienHeavenVN/Veko` (alias chuyển hướng: `https://github.com/TienHeavenVN/Zeko`) | `git remote -v` & GitHub API (`full_name: TienHeavenVN/Veko`) |
| **Website chính thức** | `https://tiendayy.kdns.fr` | `CNAME`, `website/astro.config.mjs` |
| **License** | `MPL-2.0` (Mozilla Public License Version 2.0) | Tệp `LICENSE` tại thư mục gốc (commit `7d601482fb020a0a9abfe600828e6da2643b7075`) & `privacy/rust/veko-adblock/Cargo.toml` |
| **Git Tag hiện có** | `v0.2.0` (`a3ac73718e9f690b7f334ff89897f4d97f4a96ea`) | `git ls-remote --tags origin` (`versionName = "0.2.0-chromium-arch"`, `versionCode = 1`) |
| **Phiên bản nhánh nguồn hiện tại** | `versionName = "0.3.0-dual-engine"`, `versionCode = 1` (chưa gắn tag `v0.3.0`) | `app/build.gradle.kts` (cần tăng `versionCode = 2` trước khi gắn tag `v0.3.0`) |
| **SDK Levels** | `minSdk = 26` • `targetSdk = 36` • `compileSdk = 37` | `app/build.gradle.kts` |
| **Gradle Module tạo APK** | `:app` (trong tổng số 9 module Gradle Kotlin DSL) | `settings.gradle.kts`, `app/build.gradle.kts` |
| **Release Build Variant** | `release` (không sử dụng `productFlavors`; bật ABI split) | `app/build.gradle.kts` (`buildTypes.getByName("release")`) |
| **Lệnh biên dịch Release** | `./gradlew :app:assembleRelease` (xuất 3 APK theo ABI) | `app/build.gradle.kts`, `.github/workflows/build-apk.yml` |
| **Toolchain yêu cầu** | JDK `17`, Gradle `9.4.0`, AGP `9.1.1`, Kotlin `2.3.20`, Android NDK `27.2.12479018`, Rust `>= 1.85`, `gn`, `ninja`, `python3` | `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `scripts/setup-native-toolchains.sh` |
| **Supported Architectures** | `arm64-v8a`, `armeabi-v7a`, `x86_64` (`isUniversalApk = false`) | `app/build.gradle.kts` (`splits.abi`), `scripts/build-native-gn.sh` |
| **Vị trí YAML đích trên `fdroiddata`** | `metadata/io.veko.browser.yml` trong `https://gitlab.com/fdroid/fdroiddata` | Chuẩn cấu trúc của `fdroidserver` |

---

## 2. Tổng quan phụ thuộc & Thư viện Native

1. **Mozilla GeckoView (`org.mozilla.geckoview:geckoview:150.0.20260511200624`)**:
   - Cấp phép theo `MPL-2.0`, tải từ kho `https://maven.mozilla.org/maven2/`.
   - Chứa các thư viện chia sẻ đã biên dịch sẵn của Mozilla (`libxul.so`, `libmozglue.so`, `libnss3.so`, `liblgpllibs.so`, `libfreebl3.so`, `libsoftokn3.so`, `libipcclientcerts.so`).
   - **Tác động F-Droid**: Đây là rào cản chính sách lớn nhất vì kho chính của F-Droid (`fdroiddata`) yêu cầu biên dịch thư viện native từ mã nguồn (tương tự cách Fennec F-Droid biên dịch GeckoView từ nguồn Firefox) thay vì kéo AAR biên dịch sẵn từ `maven.mozilla.org`.
2. **Rust `veko-adblock-ffi` (`brave/adblock-rust =0.13.4`) + Cầu nối C++20 JNI (`:chromium-native`)**:
   - Biên dịch trực tiếp từ mã nguồn trong repo (`privacy/rust/veko-adblock` + `chromium-native/src/main/cpp` + `BUILD.gn`) thành `libveko_chromium_native.so` căn lề trang 16 KB cho cả 3 ABI.
   - Không có tệp `.so` biên dịch sẵn nào được lưu trong Git. Tuy nhiên, cần commit `Cargo.lock` và cấu hình vendoring phụ thuộc Rust để biên dịch ngoại tuyến trong VM của F-Droid.
3. **Nhân phụ Chromium Content (`156.0.8078.85`)**:
   - Đang trong giai đoạn phát triển (`In Development`); không có tệp `.so`, `.jar` hay `.pak` nào của Chromium được đóng gói sẵn trong kho mã nguồn tiêu chuẩn.
4. **AndroidX, Jetpack Compose, Room, Hilt & Coil 3**:
   - Toàn bộ là thư viện mã nguồn mở (`Apache-2.0`) từ Google Maven và Maven Central; **0 SDK độc quyền** (không dùng Google Play Services, Firebase, Crashlytics hay Analytics).

Chi tiết đầy đủ xem tại [`DEPENDENCIES.md`](./DEPENDENCIES.md) và [`BUILD.md`](./BUILD.md).

---

## 3. Trạng thái chuẩn bị F-Droid hiện tại (Phân biệt rõ từng giai đoạn)

| Giai đoạn | Trạng thái hiện tại | Ghi chú xác thực |
| --- | --- | --- |
| **`METADATA PREPARED`** | **YES (ĐÃ CHUẨN BỊ)** | Đã xác minh toàn bộ thông số từ mã nguồn và tạo `metadata/fdroid/io.veko.browser.yml` cùng bộ tài liệu kiểm toán. |
| **`FDROID LINT PASSED`** | **BLOCKED** | Môi trường sandbox/CI hiện tại chưa cài đặt `fdroidserver`; lệnh kiểm tra chuẩn đã được cung cấp đầy đủ trong `PUBLISHING.md`. |
| **`BUILD VERIFIED` (trong F-Droid VM)** | **BLOCKED (`BUILD BLOCKED`)** | Build trên GitHub Actions CI đạt 100%, nhưng build trong VM cô lập của F-Droid đang vướng 4 điểm nghẽn kỹ thuật cụ thể (GeckoView prebuilt AAR, `verifyReleaseSigningConfig` chặn APK unsigned, thiếu `Cargo.lock` ngoại tuyến và tag mới sau khi thêm `LICENSE`). |
| **`MR OPENED` (`fdroiddata`)** | **NO (CHƯA MỞ)** | Tuân thủ quy tắc an toàn: không mở Merge Request lên `gitlab.com/fdroid/fdroiddata` khi `BUILD VERIFIED` còn `BLOCKED`. |
| **`ACCEPTED BY F-DROID`** | **NO (CHƯA ĐƯỢC CHẤP NHẬN)** | Ứng dụng chưa được phát hành trên F-Droid chính thức. |

---

## 4. Cấu trúc thư mục `metadata/fdroid/`

```text
metadata/
└── fdroid/
    ├── io.veko.browser.yml   # Bản chuẩn bị YAML (sẽ sao chép sang metadata/io.veko.browser.yml của fdroiddata)
    ├── README.md             # Tổng quan định danh, kiến trúc & trạng thái chuẩn bị F-Droid
    ├── PUBLISHING.md         # Checklist 10 bước kiểm định, chụp ảnh màn hình thật & quy trình gửi MR
    ├── DEPENDENCIES.md       # Bảng kiểm toán chi tiết toàn bộ dependency, giấy phép & khả năng build từ nguồn
    ├── BUILD.md              # Công thức biên dịch chi tiết, đối chiếu CI build vs. F-Droid build VM
    └── screenshots/          # Thư mục dành riêng cho ảnh chụp màn hình PNG thực tế (hiện để trống qua .gitkeep)
```
