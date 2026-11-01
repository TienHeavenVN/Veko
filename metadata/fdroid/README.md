# F-Droid Metadata, Audit & Build Remediation — Veko Browser

Thư mục `metadata/fdroid/` lưu trữ hồ sơ kiểm toán phụ thuộc, quy trình biên dịch ngoại tuyến, hướng dẫn xuất bản và tệp công thức chuẩn bị cho việc đưa **Veko Browser** (`io.veko.browser` — kiến trúc **thuần Mozilla GeckoView**) lên hệ sinh thái **F-Droid** (`https://gitlab.com/fdroid/fdroiddata`).

> **Lưu ý quan trọng về vị trí tệp YAML & trạng thái F-Droid**:
> - Tệp `metadata/fdroid/io.veko.browser.yml` trong kho mã nguồn Veko là **bản chuẩn bị nội bộ (staging template)** đã được chuẩn hóa theo cấu trúc của `fdroid rewritemeta` và xác thực tự động qua `fdroid readmeta` + `fdroid lint --format --force-yamllint` trên CI.
> - Máy chủ `fdroidserver` chính thức **không tự đọc** tệp YAML từ repo ứng dụng; chỉ khi mọi cổng điều kiện kỹ thuật hoàn tất, tệp này mới được đặt tại **`metadata/io.veko.browser.yml`** trong fork của **`https://gitlab.com/fdroid/fdroiddata`**.
> - **Đánh giá trung thực:** Dù Veko đã chuẩn hóa về kiến trúc thuần GeckoView 7 module (không còn phụ thuộc bất kỳ hệ thống build phụ nào ngoài Cargo + Android NDK Clang++), trạng thái nộp F-Droid hiện tại **vẫn là `BLOCKED`** vì `org.mozilla.geckoview:geckoview:150.0.20260511200624` mặc định vẫn lấy gói AAR chứa `.so` dựng sẵn từ `maven.mozilla.org`.

---

## 1. Thông Tin Định Danh & Cấu Hình Đã Xác Minh Từ Mã Nguồn

| Thuộc tính | Giá trị xác minh | Nguồn đối chiếu trong mã nguồn |
| --- | --- | --- |
| **Application ID** | `io.veko.browser` | `app/build.gradle.kts` (`defaultConfig.applicationId = "io.veko.browser"`) |
| **App Name** | `Veko` (`AutoName: Veko Browser`) | `app/build.gradle.kts` (`manifestPlaceholders["appLabel"] = "Veko"`), `app/src/main/AndroidManifest.xml` |
| **Repository URL** | `https://github.com/TienHeavenVN/Veko` (alias chuyển hướng: `https://github.com/TienHeavenVN/Zeko`) | `git remote -v` & GitHub API (`full_name: TienHeavenVN/Veko`) |
| **Website chính thức** | `https://veko.kdns.fr` | `CNAME`, `website/public/CNAME`, `website/astro.config.mjs` |
| **License** | `MPL-2.0` (Mozilla Public License Version 2.0) | Tệp `LICENSE` tại thư mục gốc & `privacy/rust/veko-adblock/Cargo.toml` |
| **Git Tag đã phát hành** | `v0.2.0` (`versionCode = 1`), `v0.3.0` (`versionCode = 2`), `v0.4.0` (`versionCode = 3`, `ccf7a8bf7a6bcae2d034443ccd666d6b45ef1a38`) | GitHub Releases chính thức đã ký số (giữ nguyên toàn bộ lịch sử không sửa đổi) |
| **Phiên bản nhánh nguồn hiện tại** | `versionName = "0.4.0-privacy-shield"`, `versionCode = 3` | `app/build.gradle.kts` (thuần Mozilla GeckoView 150.0, 3-ring Privacy Shield, 8 ngôn ngữ Fastlane/UI, 5 ảnh chụp PNG 1:1 `1080×2400`) |
| **SDK Levels** | `minSdk = 26` • `targetSdk = 36` • `compileSdk = 37` | `app/build.gradle.kts` |
| **Gradle Module tạo APK** | `:app` (trong tổng số **7 module** Gradle Kotlin DSL: `:app`, `:browser-core`, `:privacy`, `:downloads`, `:storage`, `:security`, `:testing`) | `settings.gradle.kts`, `app/build.gradle.kts` |
| **Release Build Variant** | `release` (không dùng `productFlavors`; bật ABI split; đã tắt `dependenciesInfo`) | `app/build.gradle.kts` |
| **Lệnh Release Unsigned (F-Droid)** | `./gradlew :app:assembleRelease -PfdroidUnsignedRelease=true` | `app/build.gradle.kts` (tạo 3 tệp `app-<abi>-release-unsigned.apk`, tách biệt khỏi luồng khóa ký chính thức) |
| **Toolchain yêu cầu** | JDK `17`, Gradle `9.4.0`, AGP `9.1.1`, Kotlin `2.3.20`, Android NDK `27.2.12479018`, Rust `>= 1.86`, `clang++`, `python3` | `build.gradle.kts`, `privacy/rust/veko-adblock/Cargo.toml`, `scripts/setup-native-toolchains.sh`, `scripts/build-adblock-native.sh` |
| **Supported Architectures** | `arm64-v8a`, `armeabi-v7a`, `x86_64` (`isUniversalApk = false`, căn lề trang ELF 16 KB) | `app/build.gradle.kts`, `scripts/build-adblock-native.sh`, `scripts/verify-elf-packaging.py` |
| **Vị trí YAML đích trên `fdroiddata`** | `metadata/io.veko.browser.yml` trong `https://gitlab.com/fdroid/fdroiddata` | Chuẩn cấu trúc của `fdroidserver` |

---

## 2. Đánh Giá 5 Câu Hỏi Kiểm Định Bắt Buộc Về GeckoView Cho F-Droid

1. **GeckoView hiện lấy từ `maven.mozilla.org` dạng prebuilt AAR hay build từ nguồn?**
   - **Lấy dạng prebuilt AAR từ `https://maven.mozilla.org/maven2/`** (`org.mozilla.geckoview:geckoview:150.0.20260511200624`) trong cấu hình Gradle mặc định. Dự án đã mở sẵn công tắc `-Pveko.geckoview.useMavenLocal=true` để nạp từ `mavenLocal()`, nhưng bản thân repo Veko không tự biên dịch cây nguồn Firefox.
2. **Gói GeckoView có chứa binary `.so` dựng sẵn không?**
   - **CÓ.** Mỗi AAR của GeckoView chứa **7 thư viện ELF `.so` biên dịch sẵn** cho từng kiến trúc ABI: `libxul.so`, `libmozglue.so`, `libnss3.so`, `liblgpllibs.so`, `libfreebl3.so`, `libsoftokn3.so`, và `libipcclientcerts.so`.
3. **F-Droid `fdroiddata` có chấp nhận dependency này trực tiếp không?**
   - **KHÔNG.** Dù GeckoView mang giấy phép tự do `MPL-2.0`, `fdroid scanner` từ chối kho `maven.mozilla.org` và từ chối mọi thư viện `.so` dựng sẵn chưa được chính máy chủ F-Droid tự biên dịch từ mã nguồn thượng nguồn.
4. **Trạng thái đối với F-Droid khi chưa build GeckoView từ nguồn (`MozFennec`) là gì?**
   - Bắt buộc giữ trạng thái **`BLOCKED`** cho tới khi hoàn tất và kiểm chứng thực tế công thức biên dịch ngoại tuyến `srclibs: MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE` (`a077abc2b0f43ed7cc59a8bfcd873e683500d23a`) tương tự Fennec F-Droid / Firefox Klar.
5. **Việc chuyển sang kiến trúc thuần GeckoView (xóa bỏ hoàn toàn nhánh nhân phụ trước đây) có làm ứng dụng "sẵn sàng F-Droid" ngay không?**
   - **KHÔNG.** Kiến trúc thuần GeckoView giúp đơn giản hóa hệ thống build nội bộ (`Cargo` + `NDK Clang++` -> `libveko_adblock_jni.so`), nhưng **tuyệt đối không tuyên bố "sẵn sàng F-Droid"** chừng nào Blocker 1 (`MozFennec` source build) chưa được chứng minh đầu-cuối trong môi trường VM cô lập của F-Droid.

---

## 3. Tóm Tắt Kết Quả Kiểm Toán 4 Điểm Nghẽn F-Droid

| Blocker | Hạng mục kiểm toán | Trạng thái hiện tại | Tóm tắt kỹ thuật |
| --- | --- | --- | --- |
| **Blocker 1** | `org.mozilla.geckoview:geckoview:150.0.20260511200624` (AAR từ `maven.mozilla.org`) | **`BLOCKED` (VM Execution Pending)** | Đã tích hợp kịch bản biên dịch từ nguồn `scripts/build-geckoview-from-source.sh` (`MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE` = `a077abc2b0f43ed7cc59a8bfcd873e683500d23a`, NDK `29.0.14206865`), kịch bản dọn `prebuild` `scripts/prepare-fdroid-source.sh` (`fdroid scanner` đạt `0 problems found`), và chốt chặn fail-closed `verifyGeckoViewMavenLocalMode`; giữ trạng thái `BLOCKED` cho tới khi chạy thực tế trọn vẹn trong F-Droid `buildserver` VM và gắn tag mới sau `v0.4.0`. |
| **Blocker 2** | Chốt chặn ký số `verifyReleaseSigningConfig` trong `app/build.gradle.kts` | **`PASS` (Đã khắc phục)** | Chế độ tường minh `-PfdroidUnsignedRelease=true` (`verifyFdroidUnsignedReleaseMode` + tắt `dependenciesInfo` `0x504B4453`) xuất ra 3 gói `app-<abi>-release-unsigned.apk` sạch, giữ nguyên cổng fail-fast cho `assembleRelease` mặc định. |
| **Blocker 3** | Khóa phụ thuộc Rust `Cargo.lock` & biên dịch JNI ngoại tuyến | **`PASS` (Đã khắc phục)** | `privacy/rust/veko-adblock/Cargo.lock` khóa cứng 58 gói kèm SHA-256; `scripts/vendor-rust-deps.sh` + `scripts/build-adblock-native.sh` biên dịch `libveko_adblock_jni.so` hoàn toàn ngoại tuyến (`CARGO_NET_OFFLINE=true --locked --offline`). |
| **Blocker 4** | Tệp `LICENSE`, tăng `versionCode` & Git Tag phát hành | **`PASS` (Đã khắc phục mức repo)** | Tệp gốc `LICENSE` (`MPL-2.0`) nguyên vẹn; các tag `v0.2.0` (`1`), `v0.3.0` (`2`), `v0.4.0` (`3`) được bảo toàn; khi phát hành bản kế tiếp từ nhánh hiện tại cần tăng tiếp `versionCode` trước khi cắt tag mới. |

Chi tiết đầy đủ xem tại [`DEPENDENCIES.md`](./DEPENDENCIES.md), [`BUILD.md`](./BUILD.md) và [`PUBLISHING.md`](./PUBLISHING.md).
