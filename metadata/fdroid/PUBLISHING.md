# Checklist Kiểm Định & Xuất Bản Lên F-Droid (`fdroiddata`)

Tài liệu này quy định quy trình kiểm định bắt buộc trước khi gửi Merge Request đưa **Veko Browser** (`io.veko.browser`) vào kho chính thức **`https://gitlab.com/fdroid/fdroiddata`**.

---

## 1. Bảng Phân Biệt 5 Trạng Thái Xuất Bản F-Droid

| Mã trạng thái | Ý nghĩa kỹ thuật | Trạng thái hiện tại của Veko Browser |
| --- | --- | --- |
| **`METADATA PREPARED`** | Đã đối chiếu toàn bộ thông tin từ mã nguồn và chuẩn bị `metadata/fdroid/io.veko.browser.yml` kèm tài liệu kiểm toán phụ thuộc/build. | **`PASS` (`PREPARED`)** |
| **`FDROID LINT PASSED`** | Đã chạy thực tế `fdroid readmeta`, `fdroid rewritemeta io.veko.browser` và `fdroid lint --format --force-yamllint io.veko.browser` bằng `fdroidserver 2.4.5` với mã thoát `0` (`0` cảnh báo, `0` lỗi). | **`PASS` (`VERIFIED` bằng `fdroidserver 2.4.5` cục bộ & trên GitHub Actions CI)** |
| **`BUILD VERIFIED` (trong F-Droid VM)** | Đã chạy thành công `fdroid build --verbose --on-server io.veko.browser:<versionCode>` trong máy ảo cô lập không mạng của F-Droid mà không còn phụ thuộc AAR prebuilt. | **`BLOCKED`** (Còn vướng Blocker 1 — biên dịch GeckoView từ nguồn `MozFennec`, và chưa cắt tag phát hành `v0.3.0` trên `main`) |
| **`MR OPENED`** | Đã mở Merge Request lên `https://gitlab.com/fdroid/fdroiddata` sau khi tất cả điều kiện Mục 2 đều đạt. | **`NO` (CHƯA MỞ — tuân thủ cổng chặn an toàn)** |
| **`ACCEPTED BY F-DROID`** | Đội ngũ bảo trì F-Droid đã duyệt, merge MR và ký phát hành APK lên `f-droid.org`. | **`NO` (CHƯA ĐƯỢC CHẤP NHẬN)** |

---

## 2. Cổng Điều Kiện Bắt Buộc Trước Khi Mở MR Lên `gitlab.com/fdroid/fdroiddata`

> **Quy tắc cứng**: Chỉ được phép mở Merge Request trên `https://gitlab.com/fdroid/fdroiddata` khi **tất cả 10 mục dưới đây đều đạt `[x]`**. Hiện tại còn mục chưa đạt nên **tuyệt đối không mở MR**.

- [x] **1. Source code & Application ID sạch**: `applicationId = "io.veko.browser"`, `0` SDK độc quyền/quảng cáo/theo dõi, `0` tệp `.so`/`.a`/`.apk` biên dịch sẵn trong Git, đã tắt khối `dependenciesInfo` (`0x504B4453`).
- [x] **2. Giấy phép nguồn mở hợp lệ (`MPL-2.0`)**: Tệp `LICENSE` tại thư mục gốc là `Mozilla Public License Version 2.0`, đồng nhất với `io.veko.browser.yml` và `privacy/rust/veko-adblock/Cargo.toml`.
- [x] **3. Khóa phụ thuộc Rust & Build ngoại tuyến (`Cargo.lock`)**: Đã commit `privacy/rust/veko-adblock/Cargo.lock` (58 gói khóa cứng SHA-256) và kiểm chứng `scripts/vendor-rust-deps.sh` + `CARGO_NET_OFFLINE=true` (`--locked --offline`).
- [x] **4. Tách biệt Unsigned Release APK cho F-Droid**: `./gradlew :app:assembleRelease -PfdroidUnsignedRelease=true` tạo `app-{arm64-v8a,armeabi-v7a,x86_64}-release-unsigned.apk` sạch, trong khi `./gradlew :app:assembleRelease` mặc định vẫn giữ nguyên chốt chặn `verifyReleaseSigningConfig`.
- [x] **5. Tăng `versionCode` nhất quán**: Đã tăng `versionCode = 2` (`> 1` của `v0.2.0`) khớp với `versionName = "0.3.0-dual-engine"`.
- [x] **6. Kiểm tra `fdroid readmeta` & `fdroid lint --format`**: Đã chạy thực tế `fdroidserver 2.4.5` đạt `0` lỗi, `0` cảnh báo (và tự động kiểm tra lại trong mỗi lần chạy CI `build-apk.yml`).
- [ ] **7. Biên dịch `org.mozilla.geckoview` từ nguồn Firefox (`MozFennec` `srclibs`)**: Cần hoàn tất và kiểm chứng thực tế việc build `MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE` (`a077abc2b0f43ed7cc59a8bfcd873e683500d23a`) sang `mavenLocal()` kết hợp `-Pveko.geckoview.useMavenLocal=true` để loại bỏ hoàn toàn `https://maven.mozilla.org/maven2/`.
- [ ] **8. Cắt Git Tag phát hành mới (`v0.3.0`) trên `main`**: Sau khi gộp nhánh và hoàn tất Mục 7, tạo annotated tag `v0.3.0` (`versionCode = 2`) chứa đầy đủ `LICENSE`, `Cargo.lock` và công thức unsigned release, sau đó cập nhật `Builds:` / `CurrentVersion` / `CurrentVersionCode` trong `io.veko.browser.yml` (gỡ trường `disable:`).
- [ ] **9. Chạy thành công `fdroid scanner` & `fdroid build --on-server` trong F-Droid VM**: Xác nhận máy ảo cô lập của F-Droid tạo ra đầy đủ APK cho các ABI mục tiêu mà không cần mạng trong pha `build:`.
- [x] **10. Bổ sung Fastlane Metadata song ngữ (`en-US`, `vi`) & ảnh chụp PNG thực tế**: Đã tạo đầy đủ `fastlane/metadata/android/{en-US,vi}/{title.txt,short_description.txt,full_description.txt,changelogs/1.txt,changelogs/2.txt,images/icon.png,images/phoneScreenshots/1..4.png}` và `metadata/fdroid/screenshots/{1-home.png,2-tabs-lru.png,3-private-mode.png,4-engine-settings.png}` chụp trực tiếp từ ứng dụng chạy trên Android 14 Emulator (API 34, không EXIF, không dùng ảnh SVG mockup).

---

## 3. Bộ Fastlane Metadata & Ảnh Chụp Màn Hình Thật Đã Tích Hợp

Kho mã nguồn hiện đã có đầy đủ ảnh chụp PNG thực tế từ Android 14 Emulator (API 34) không chứa EXIF tại cả hai vị trí:
- `fastlane/metadata/android/{en-US,vi}/images/phoneScreenshots/{1.png,2.png,3.png,4.png}` + `icon.png` (`512×512`)
- [`metadata/fdroid/screenshots/`](./screenshots/): `1-home.png`, `2-tabs-lru.png`, `3-private-mode.png`, `4-engine-settings.png`

```bash
# Chụp ảnh PNG thực tế từ ứng dụng Veko đang chạy trên máy ảo / thiết bị thật qua ADB:
adb shell cmd statusbar expand-notifications && adb shell cmd statusbar collapse
adb exec-out screencap -p > metadata/fdroid/screenshots/1-home.png
adb exec-out screencap -p > metadata/fdroid/screenshots/2-tabs-lru.png
adb exec-out screencap -p > metadata/fdroid/screenshots/3-private-mode.png
adb exec-out screencap -p > metadata/fdroid/screenshots/4-engine-settings.png
```

---

## 4. Lệnh Kiểm Tra Chuẩn Bằng `fdroidserver`

```bash
# 1. Sao chép kho fdroiddata chính thức
git clone --depth=1 https://gitlab.com/fdroid/fdroiddata.git
cd fdroiddata

# 2. Sao chép bản YAML từ repo Veko sang đúng vị trí metadata/io.veko.browser.yml
cp /path/to/Veko/metadata/fdroid/io.veko.browser.yml metadata/io.veko.browser.yml

# 3. Kiểm tra định dạng, schema và yamllint (đã PASS trên fdroidserver 2.4.5)
fdroid readmeta
fdroid lint --format --force-yamllint io.veko.browser

# 4. (Sau khi hoàn tất Mục 7 & 8 ở trên và bỏ trường disable:) Quét nguồn & build trong F-Droid VM
fdroid scanner --verbose io.veko.browser
fdroid build --verbose --on-server io.veko.browser:2
```
