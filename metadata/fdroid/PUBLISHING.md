# Checklist Kiểm Định & Xuất Bản Lên F-Droid (`fdroiddata`)

Tài liệu này quy định quy trình kiểm định bắt buộc trước khi gửi Merge Request đưa **Veko Browser** (`io.veko.browser`) vào kho chính thức **`https://gitlab.com/fdroid/fdroiddata`**.

---

## 1. Bảng Phân Biệt 5 Trạng Thái Xuất Bản F-Droid

| Mã trạng thái | Ý nghĩa kỹ thuật | Trạng thái hiện tại của Veko Browser |
| --- | --- | --- |
| **`METADATA PREPARED`** | Đã đối chiếu toàn bộ thông tin từ mã nguồn thuần GeckoView (7 module) và chuẩn bị `metadata/fdroid/io.veko.browser.yml` kèm tài liệu kiểm toán phụ thuộc/build. | **`PASS` (`PREPARED`)** |
| **`FDROID LINT PASSED`** | Đã chạy thực tế `fdroid readmeta` và `fdroid lint --format --force-yamllint io.veko.browser` bằng `fdroidserver` với mã thoát `0` (`0` cảnh báo, `0` lỗi). | **`PASS` (`VERIFIED` tự động trên GitHub Actions CI)** |
| **`BUILD VERIFIED` (trong F-Droid VM)** | Đã chạy thành công `fdroid build --verbose --on-server io.veko.browser:<versionCode>` trong máy ảo cô lập không mạng của F-Droid mà không còn phụ thuộc AAR prebuilt. | **`BLOCKED`** (Còn vướng Blocker 1 — `org.mozilla.geckoview` mặc định vẫn là prebuilt AAR chứa 7 tệp `.so` từ `maven.mozilla.org`, chưa biên dịch ngoại tuyến từ `srclibs: MozFennec`) |
| **`MR OPENED`** | Đã mở Merge Request lên `https://gitlab.com/fdroid/fdroiddata` sau khi tất cả điều kiện Mục 2 đều đạt. | **`NO` (CHƯA MỞ — tuân thủ nghiêm ngặt cổng chặn an toàn)** |
| **`ACCEPTED BY F-DROID`** | Đội ngũ bảo trì F-Droid đã duyệt, merge MR và ký phát hành APK lên `f-droid.org`. | **`NO` (CHƯA ĐƯỢC CHẤP NHẬN)** |

---

## 2. Cổng Điều Kiện Bắt Buộc Trước Khi Mở MR Lên `gitlab.com/fdroid/fdroiddata`

> **Quy tắc cứng**: Chỉ được phép mở Merge Request trên `https://gitlab.com/fdroid/fdroiddata` khi **tất cả 10 mục dưới đây đều đạt `[x]`**. Việc đơn giản hóa dự án sang kiến trúc thuần GeckoView **không tự động mở khóa F-Droid**; hiện tại Mục 7–9 chưa đạt nên **tuyệt đối không mở MR**.

- [x] **1. Source code & Application ID sạch**: `applicationId = "io.veko.browser"`, `0` SDK độc quyền/quảng cáo/theo dõi, `0` tệp `.so`/`.a`/`.apk` biên dịch sẵn lưu trong Git, đã tắt khối `dependenciesInfo` (`0x504B4453`).
- [x] **2. Giấy phép nguồn mở hợp lệ (`MPL-2.0`)**: Tệp `LICENSE` tại thư mục gốc là `Mozilla Public License Version 2.0`, đồng nhất với `io.veko.browser.yml` và `privacy/rust/veko-adblock/Cargo.toml`.
- [x] **3. Khóa phụ thuộc Rust & Build JNI ngoại tuyến (`Cargo.lock`)**: Đã commit `privacy/rust/veko-adblock/Cargo.lock` (58 gói khóa cứng SHA-256) và kiểm chứng `scripts/vendor-rust-deps.sh` + `scripts/build-adblock-native.sh` với `CARGO_NET_OFFLINE=true` (`--locked --offline` -> `libveko_adblock_jni.so`).
- [x] **4. Tách biệt Unsigned Release APK cho F-Droid**: `./gradlew :app:assembleRelease -PfdroidUnsignedRelease=true` tạo `app-{arm64-v8a,armeabi-v7a,x86_64}-release-unsigned.apk` sạch, trong khi `./gradlew :app:assembleRelease` mặc định vẫn giữ nguyên chốt chặn `verifyReleaseSigningConfig`.
- [x] **5. Quản lý `versionCode` nhất quán**: Lịch sử tag `v0.2.0` (`1`), `v0.3.0` (`2`), `v0.4.0` (`3`) được giữ nguyên; bản phát hành mới kế tiếp sẽ tiếp tục tăng `versionCode` đơn điệu.
- [x] **6. Kiểm tra `fdroid readmeta` & `fdroid lint --format --force-yamllint`**: Tự động xác thực trong mỗi lần chạy CI `build-apk.yml` với `0` lỗi, `0` cảnh báo.
- [ ] **7. Biên dịch `org.mozilla.geckoview` từ nguồn Firefox (`MozFennec` `srclibs`) [`BLOCKED`]**: Cần hoàn tất và kiểm chứng thực tế việc build `MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE` (`a077abc2b0f43ed7cc59a8bfcd873e683500d23a`) sang `mavenLocal()` kết hợp `-Pveko.geckoview.useMavenLocal=true` để thay thế hoàn toàn gói AAR chứa 7 tệp `.so` dựng sẵn từ `https://maven.mozilla.org/maven2/`.
- [ ] **8. Gắn Git Tag phát hành mới sau khi tích hợp công thức `MozFennec`**: Tăng `versionCode`, cắt tag mới trên `main` và cập nhật `Builds:` / `CurrentVersion` / `CurrentVersionCode` trong `io.veko.browser.yml` (chỉ gỡ `disable:` sau khi build nguồn thành công).
- [ ] **9. Chạy thành công `fdroid scanner` & `fdroid build --on-server` trong F-Droid VM**: Xác nhận máy ảo cô lập không mạng của F-Droid tạo ra đầy đủ APK cho các ABI mục tiêu với `0` lỗi quét nhị phân.
- [x] **10. Bổ sung Fastlane Metadata đa ngôn ngữ (8 ngôn ngữ) & 5 ảnh chụp PNG thực tế**: Đã tích hợp `fastlane/metadata/android/{en-US,vi,ja-JP,ko-KR,zh-CN,fr-FR,de-DE,es-ES}/` và 5 ảnh chụp PNG 1:1 `1080×2400` 24-bit TrueColor không EXIF tại `fastlane/metadata/android/{en-US,vi}/images/phoneScreenshots/1..5.png` cùng `metadata/fdroid/screenshots/{1-home,2-tabs-lru,3-private-mode,4-engine-settings,5-privacy-shield}.png`.

---

## 3. Lệnh Kiểm Tra Chuẩn Bằng `fdroidserver`

```bash
# 1. Sao chép kho fdroiddata chính thức
git clone --depth=1 https://gitlab.com/fdroid/fdroiddata.git
cd fdroiddata

# 2. Sao chép bản YAML từ repo Veko sang đúng vị trí metadata/io.veko.browser.yml
cp /path/to/Veko/metadata/fdroid/io.veko.browser.yml metadata/io.veko.browser.yml

# 3. Kiểm tra định dạng, schema và yamllint
fdroid readmeta
fdroid lint --format --force-yamllint io.veko.browser

# 4. (Chỉ sau khi hoàn tất Mục 7–8 ở trên và gỡ trường disable:) Quét nguồn & build trong F-Droid VM
fdroid scanner --verbose io.veko.browser
fdroid build --verbose --on-server io.veko.browser:3
```
