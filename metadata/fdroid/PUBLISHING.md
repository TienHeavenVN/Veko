# Checklist Kiểm Định & Xuất Bản Lên F-Droid (`fdroiddata`)

Tài liệu này quy định quy trình kiểm tra bắt buộc trước và trong khi gửi Merge Request đưa **Veko Browser** (`io.veko.browser`) vào kho chính thức **`https://gitlab.com/fdroid/fdroiddata`**.

---

## 1. Bảng phân biệt 5 trạng thái xuất bản F-Droid

| Mã trạng thái | Ý nghĩa kỹ thuật | Trạng thái hiện tại của Veko Browser |
| --- | --- | --- |
| **`METADATA PREPARED`** | Đã xác minh thông tin từ mã nguồn và chuẩn bị `metadata/fdroid/io.veko.browser.yml` kèm tài liệu phụ thuộc/build. | **ĐẠT (`PREPARED`)** |
| **`FDROID LINT PASSED`** | Đã chạy `fdroid readmeta` và `fdroid lint io.veko.browser` trên `fdroiddata` không báo lỗi schema/format. | **`BLOCKED`** (cần máy trạm cài `fdroidserver`) |
| **`BUILD VERIFIED`** | Đã chạy thành công `fdroid build --verbose --on-server io.veko.browser:<versionCode>` trong container/VM cô lập của F-Droid. | **`BLOCKED`** (xem 4 nguyên nhân trong `BUILD.md`) |
| **`MR OPENED`** | Đã mở Merge Request hợp lệ trên `https://gitlab.com/fdroid/fdroiddata` với log build xanh từ GitLab CI. | **CHƯA THỰC HIỆN** |
| **`ACCEPTED BY F-DROID`** | Đội ngũ bảo trì F-Droid đã duyệt, merge MR và ký xuất bản APK lên `f-droid.org`. | **CHƯA ĐƯỢC CHẤP NHẬN** |

---

## 2. Checklist 10 bước trước khi gửi Merge Request lên `fdroiddata`

- [x] **Bước 1 — Kiểm tra source code & Application ID**:
  - Xác nhận `applicationId = "io.veko.browser"` trong `app/build.gradle.kts`, không nhúng SDK theo dõi/quảng cáo và không commit tệp `.so`/`.apk` biên dịch sẵn trong Git.
- [x] **Bước 2 — Kiểm tra License**:
  - Xác nhận tệp `LICENSE` tại thư mục gốc là `Mozilla Public License Version 2.0` (`MPL-2.0`), khớp với khai báo `License: MPL-2.0` trong `io.veko.browser.yml` và `privacy/rust/veko-adblock/Cargo.toml`.
- [ ] **Bước 3 — Kiểm tra & xử lý Dependency theo chuẩn F-Droid**:
  - Commit tệp khóa `privacy/rust/veko-adblock/Cargo.lock` để khóa cứng phiên bản crate Rust và cho phép vendoring ngoại tuyến.
  - Quyết định phương án biên dịch `org.mozilla.geckoview:geckoview` từ nguồn Firefox qua `srclibs:` (để đáp ứng chính sách không dùng thư viện native prebuilt từ `maven.mozilla.org`).
  - Thêm `distributionSha256Sum` vào `gradle/wrapper/gradle-wrapper.properties`.
- [ ] **Bước 4 — Hỗ trợ biên dịch Unsigned Release cho F-Droid & Reproducible Build**:
  - Bổ sung chế độ cho phép `./gradlew :app:assembleRelease -PfdroidUnsignedRelease=true` tạo `app-<abi>-release-unsigned.apk` khi không có biến môi trường `VEKO_KEYSTORE_*` (vẫn giữ nguyên cổng chặn mặc định cho luồng phát hành GitHub Releases).
  - Cấu hình `-ffile-prefix-map` trong `BUILD.gn` để chuẩn hóa đường dẫn nhị phân phục vụ Reproducible Builds.
- [ ] **Bước 5 — Kiểm tra `versionCode` tăng dần**:
  - Tăng `versionCode` trong `app/build.gradle.kts` từ `1` lên `2` (vì `versionCode = 1` đã được dùng cho tag `v0.2.0`) và đồng bộ `CurrentVersion` / `CurrentVersionCode` trong `io.veko.browser.yml`.
- [ ] **Bước 6 — Tạo Git Tag phát hành mới chứa đầy đủ `LICENSE`**:
  - Tạo signed/annotated Git tag mới (ví dụ `v0.3.0`) từ nhánh `main` sau khi đã gộp `LICENSE`, `Cargo.lock` và cờ unsigned release.
- [ ] **Bước 7 — Bổ sung Screenshots thực tế từ Android (Fastlane structure)**:
  - Chụp ảnh PNG thực tế theo đúng hướng dẫn ở Mục 3 bên dưới (tuyệt đối không dùng ảnh mockup SVG của website).
- [ ] **Bước 8 — Chạy `fdroid readmeta` & `fdroid lint` cục bộ**:
  - Sao chép `metadata/fdroid/io.veko.browser.yml` sang `fdroiddata/metadata/io.veko.browser.yml` (và xóa dòng `disable:` sau khi hoàn tất Bước 3–6), sau đó thực thi bộ lệnh kiểm tra ở Mục 4.
- [ ] **Bước 9 — Chạy build thực tế trong môi trường F-Droid (`fdroid build`)**:
  - Xác minh `fdroid scanner` và `fdroid build --on-server` tạo ra APK sạch cho `arm64-v8a`, `armeabi-v7a`, `x86_64` khi ngắt mạng hoàn toàn.
- [ ] **Bước 10 — Mở Merge Request lên `gitlab.com/fdroid/fdroiddata` & Theo dõi phản hồi**:
  - Fork `https://gitlab.com/fdroid/fdroiddata`, tạo nhánh `io.veko.browser`, commit duy nhất tệp `metadata/io.veko.browser.yml`, đảm bảo pipeline CI của `fdroiddata` xanh và phản hồi đầy đủ nhận xét của reviewer F-Droid.

---

## 3. Hướng dẫn chuẩn bị Screenshots thật cho F-Droid

Hiện tại thư mục [`metadata/fdroid/screenshots/`](./screenshots/) được **giữ trống có chủ đích** (chỉ có tệp giữ chỗ `.gitkeep` 0 byte) vì các hình minh họa trong `website/public/screenshots/*.svg` là bản dựng vector UI Mockup chứ không phải ảnh chụp từ ứng dụng đang chạy.

### Quy tắc bắt buộc của F-Droid đối với ảnh chụp màn hình:
1. Chỉ dùng ảnh chụp **PNG hoặc JPEG thực tế** từ ứng dụng Veko Browser đang chạy trên thiết bị Android thật hoặc Android Emulator (ví dụ lấy từ gói artifact ảnh chụp máy ảo API 34 của workflow `build-apk.yml` hoặc chụp qua `adb`).
2. Không lồng khung điện thoại giả (device frame), không chèn chữ quảng cáo đè lên ảnh.
3. Độ phân giải khuyến nghị: chiều dọc điện thoại chuẩn (ví dụ `1080×2400` hoặc `1080×1920`), từ **2 đến 8 ảnh**, kích thước mỗi ảnh gọn nhẹ (nén không mất dữ liệu bằng `optipng` / `oxipng`, xóa metadata EXIF).

### Lệnh chụp ảnh thật từ máy ảo / thiết bị thật:
```bash
# Khởi chạy bản cài đặt Veko trên thiết bị hoặc máy ảo Android đã kết nối ADB
adb shell cmd statusbar expand-notifications && adb shell cmd statusbar collapse
adb exec-out screencap -p > metadata/fdroid/screenshots/1-home.png
# Mở khay Tab / Chế độ Riêng tư / Cài đặt Nhân trình duyệt rồi chụp tiếp:
adb exec-out screencap -p > metadata/fdroid/screenshots/2-tabs-lru.png
adb exec-out screencap -p > metadata/fdroid/screenshots/3-private-mode.png
adb exec-out screencap -p > metadata/fdroid/screenshots/4-engine-settings.png
```

Để F-Droid tự động nhận diện ảnh chụp và mô tả đa ngôn ngữ từ kho ứng dụng (Fastlane layout), sau khi có ảnh thật hãy sao chép vào cấu trúc chuẩn ở gốc repo:
```text
fastlane/metadata/android/
├── en-US/
│   ├── title.txt
│   ├── short_description.txt
│   ├── full_description.txt
│   └── images/phoneScreenshots/
│       ├── 1.png
│       ├── 2.png
│       ├── 3.png
│       └── 4.png
└── vi/
    └── images/phoneScreenshots/
```

---

## 4. Các lệnh xác thực bằng `fdroidserver` cần chạy

Khi làm việc trên máy trạm Linux đã cài đặt `fdroidserver`, Docker/Podman hoặc Vagrant buildserver:

```bash
# 1. Sao chép kho fdroiddata chính thức
git clone --depth=1 https://gitlab.com/fdroid/fdroiddata.git
cd fdroiddata

# 2. Sao chép tệp YAML từ repo Veko sang đúng vị trí metadata/<APPLICATION_ID>.yml
cp /path/to/Veko/metadata/fdroid/io.veko.browser.yml metadata/io.veko.browser.yml

# 3. Kiểm tra cú pháp đọc metadata & kiểm tra quy chuẩn định dạng (fdroid lint)
fdroid readmeta
fdroid lint io.veko.browser
fdroid rewritemeta io.veko.browser
git diff metadata/io.veko.browser.yml

# 4. Quét mã nguồn tìm binary/SDK cấm
fdroid scanner --verbose io.veko.browser

# 5. Kiểm tra biên dịch thực tế trong môi trường máy chủ cô lập của F-Droid
fdroid build --verbose --on-server io.veko.browser
```
