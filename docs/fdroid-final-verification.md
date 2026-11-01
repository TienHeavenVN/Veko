# Báo Cáo Kiểm Định Cuối Cùng F-Droid — Veko Browser (`io.veko.browser`)

- **Ứng dụng**: Veko Browser (`io.veko.browser`)
- **Giấy phép**: `MPL-2.0` (`LICENSE` gốc — Mozilla Public License Version 2.0)
- **Kiến trúc trình duyệt**: **100% Mozilla GeckoView** (`Gecko / WebRender` + `SpiderMonkey` + `brave/adblock-rust 0.13.4` qua C++20 JNI `libveko_adblock_jni.so`; `0` Chromium/Blink/V8, `0` `android.webkit.WebView`)
- **Nguồn `MozFennec` mục tiêu**: `https://github.com/mozilla-firefox/firefox` — tag `FIREFOX-ANDROID_150_0_3_RELEASE` (`a077abc2b0f43ed7cc59a8bfcd873e683500d23a`, milestone `150.0.3`, Android NDK `29.0.14206865`)
- **Ngày kiểm định thực nghiệm**: `2026-11-01`

---

## 1. Môi Trường Thực Tế Đã Kiểm Tra (Phase 0)

Toàn bộ thông số dưới đây được đo trực tiếp bằng lệnh hệ thống bên trong **Arena Sandbox** (tuyệt đối không giả định trước và không đánh đồng với GitHub Actions CI hay máy ảo `buildserver` của F-Droid):

| Thành phần | Phiên bản / Giá trị | Trạng thái |
| --- | --- | --- |
| **OS / Kernel** | Debian GNU/Linux 12 (`bookworm`), Linux `6.1.158+` `x86_64` (`sudo` không mật khẩu) | `PASS` |
| **CPU** | `2` vCPU (`nproc = 2`) | `RESOURCE_LIMIT` cho biên dịch C++/Rust đa ABI của Firefox (`libxul.so`) |
| **RAM & Swap** | `3.8 GiB` (`3939 MiB`) RAM vật lý • `0 B` Swap (`swapon` bị từ chối ở mức kernel container) | `RESOURCE_LIMIT` (liên kết `libxul.so` bằng LLD cần tối thiểu `12–16 GiB` RAM+Swap) |
| **Disk trống** | `19–20 GiB` khả dụng / `21 GiB` tổng (`/dev/root`) | `RESOURCE_LIMIT` (`MozFennec` nguồn + `objdir` 3 kiến trúc cần `>= 35–50 GiB`) |
| **Python & Pip** | `Python 3.11.2`, `pip 23.0.1` | `PASS` |
| **Git** | `git version 2.39.5` | `PASS` |
| **GCC / G++ / Make / Zip** | `gcc/g++ 12.2.0`, `GNU Make 4.3`, `unzip 6.00`, `zip 3.0` | `PASS` |
| **CMake & Ninja (cài qua PyPI)** | `cmake 4.4.4`, `ninja 1.13.2.git.kitware.jobserver-pipe-1` (cài thành công từ `pypi.org`) | `PASS` (đã cài bổ sung) |
| **`fdroidserver` & `yamllint`** | `fdroidserver 2.4.5`, `yamllint` (cài thành công từ `pypi.org` vào venv Python) | `PASS` (đã cài bổ sung) |
| **JDK (`java` / `javac`)** | Không có sẵn; `deb.debian.org` và `api.adoptium.net` bị chặn TLS | `TOOLCHAIN_ERROR` + `NETWORK_ERROR` |
| **Rust (`rustc` / `cargo` / `rustup`)** | Không có sẵn; `sh.rustup.rs` và `static.rust-lang.org` bị chặn TLS (`EOF`) | `TOOLCHAIN_ERROR` + `NETWORK_ERROR` |
| **Clang / LLD / LLVM / `pkg-config`** | Không có sẵn (`clang`, `clang++`, `ld.lld`, `llvm-config`, `pkg-config` vắng mặt); kho `apt` bị chặn | `TOOLCHAIN_ERROR` + `NETWORK_ERROR` |
| **Android SDK & Android NDK (`r29`)** | Không có sẵn (`ANDROID_HOME` trống); `dl.google.com` bị chặn TLS (`EOF`) | `DEPENDENCY_ERROR` + `NETWORK_ERROR` |
| **Gradle `9.4.0` Runtime** | `./gradlew` có trong repo nhưng thiếu `java` và `services.gradle.org` / `maven.google.com` bị chặn | `TOOLCHAIN_ERROR` + `NETWORK_ERROR` |
| **Ảo hóa cho F-Droid VM** | Không có `/dev/kvm`, `vagrant`, `virtualbox`, `qemu-system-x86_64`, `docker`, `podman`, `lxc` | `RESOURCE_LIMIT` + `TOOLCHAIN_ERROR` |
| **Mạng ra ngoài (Allowlist thực tế)** | Chỉ mở HTTPS tới `github.com`, `api.github.com`, `codeload.github.com`, `pypi.org`, `files.pythonhosted.org`, `registry.npmjs.org`; chặn TLS `EOF` với `deb.debian.org`, `dl.google.com`, `maven.google.com`, `repo1.maven.org`, `services.gradle.org`, `maven.mozilla.org`, `gitlab.com`, `f-droid.org`, `raw.githubusercontent.com` | `NETWORK_ERROR` (chính sách tường lửa sandbox) |

---

## 2. Kết Quả Build GeckoView Từ Source (Phase 1)

### 2.1. Đối chiếu mã nguồn Firefox upstream (`MozFennec`)

Đã kiểm tra trực tiếp kho `https://github.com/mozilla-firefox/firefox` và kho `https://github.com/f-droid/fdroiddata`:

- **Định nghĩa `srclibs/MozFennec.yml` trên `fdroiddata`**: `RepoType: git`, `Repo: https://github.com/mozilla-firefox/firefox`.
- **Tag & Commit chính xác**: `FIREFOX-ANDROID_150_0_3_RELEASE` $\rightarrow$ **`a077abc2b0f43ed7cc59a8bfcd873e683500d23a`** (`config/milestone.txt = 150.0.3`).
- **Phiên bản Android NDK bắt buộc của Firefox 150.0.3**: `NDK_VERSION = "r29"` $\rightarrow$ **`29.0.14206865`** (`python/mozboot/mozboot/android.py`).
- **Cơ chế định danh Maven & Lite Mode xác minh từ `mobile/android/moz.configure` & `mobile/android/geckoview/build.gradle`**:
  - Tùy chọn `--enable-geckoview-lite` đặt `MOZ_ANDROID_GECKOVIEW_LITE = True`, loại bỏ phụ thuộc gói nhị phân Glean / Application-Services (`-omni`) và xuất ra đúng tọa độ **`org.mozilla.geckoview:geckoview:150.0.<MOZ_BUILD_DATE>`** (cùng `geckoview-exoplayer2`).
  - Với `MOZILLA_OFFICIAL=1`, `MOZ_BUILD_DATE=20260511200624` và `--enable-update-channel=release`, hàm `getVersionNumber()` tạo ra đúng phiên bản **`150.0.20260511200624`** (không hậu tố `-SNAPSHOT`).
  - Module `:geckoview` cấu hình `singleVariant('debug')` (`mobile/android/geckoview/build.gradle:146`, `mobile/android/gradle.configure:68`), do đó tác vụ xuất bản sang `mavenLocal()` thực tế là `geckoview:publishDebugPublicationToMavenLocal` và `exoplayer2:publishDebugPublicationToMavenLocal` (trong khi tối ưu hóa C++/Rust Release vẫn do `--enable-release --disable-debug` trong `mozconfig` quyết định).
  - Biến môi trường `GRADLE_MAVEN_REPOSITORIES="https://maven.google.com/,https://repo.maven.apache.org/maven2/,https://plugins.gradle.org/m2/"` loại bỏ hoàn toàn `https://maven.mozilla.org/maven2/` ngay cả bên trong tiến trình Gradle nội bộ của `MozFennec`.

### 2.2. Thử nghiệm thực tế `MozFennec` (`a077abc2b0f43ed7cc59a8bfcd873e683500d23a`) trong Arena Sandbox

Đã tải thực tế commit `a077abc2b0f43ed7cc59a8bfcd873e683500d23a` của `mozilla-firefox/firefox` vào `/tmp/MozFennec` và chạy thử trực tiếp `./mach` cùng kịch bản kiểm định tiền điều kiện:

1. **Kết quả chạy `./mach --no-interactive bootstrap --application-choice="GeckoView/Firefox for Android"`**:
   ```text
   E: Unable to locate package watchman
   requests.exceptions.SSLError: HTTPSConnectionPool(host='dl.google.com', port=443):
   Max retries exceeded with url: /android/repository/android-ndk-r29-linux.zip
   (Caused by SSLError(SSLZeroReturnError(6, 'TLS/SSL connection has been closed (EOF) (_ssl.c:992)')))
   ```
2. **Kết quả chạy `bash scripts/build-geckoview-from-source.sh --check-env`**:
   ```text
   === GeckoView 150.0.3 Source Build Preflight (MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE) ===
   [INFO] Host resources: CPU=2 vCPU | RAM=3939 MiB | Swap=0 MiB | Free Disk=19 GiB
   [RESOURCE_LIMIT] Insufficient memory for linking libxul.so: RAM+Swap=3939 MiB (< 12288 MiB minimum).
   [RESOURCE_LIMIT] Insufficient free disk space for MozFennec source + multi-ABI objdirs: 19 GiB (< 35 GiB minimum).
   [TOOLCHAIN_ERROR] Required build tool 'java' is not installed in PATH.
   [TOOLCHAIN_ERROR] Required build tool 'javac' is not installed in PATH.
   [TOOLCHAIN_ERROR] Required build tool 'rustc' is not installed in PATH.
   [TOOLCHAIN_ERROR] Required build tool 'cargo' is not installed in PATH.
   [TOOLCHAIN_ERROR] Required build tool 'clang' is not installed in PATH.
   [TOOLCHAIN_ERROR] Required build tool 'clang++' is not installed in PATH.
   [TOOLCHAIN_ERROR] Required build tool 'ld.lld' is not installed in PATH.
   [TOOLCHAIN_ERROR] Required build tool 'llvm-config' is not installed in PATH.
   [TOOLCHAIN_ERROR] Required build tool 'pkg-config' is not installed in PATH.
   [DEPENDENCY_ERROR] ANDROID_HOME / ANDROID_SDK_ROOT is not set or directory does not exist.
   [DEPENDENCY_ERROR] Android NDK r29 (29.0.14206865) not found.
   [ERROR] Preflight failed with 14 blocker(s). Refusing to fake or bypass GeckoView source compilation.
   ```

### 2.3. Phân loại nguyên nhân chính xác (Phase 1 Classification)

- **`TOOLCHAIN_ERROR`**: Thiếu `java`/`javac` (JDK 17), `rustc`/`cargo`, `clang`/`clang++`/`ld.lld`/`llvm-config`, `pkg-config` trong Arena Sandbox.
- **`DEPENDENCY_ERROR`**: Thiếu Android SDK và Android NDK `r29` (`29.0.14206865`).
- **`NETWORK_ERROR`**: Tường lửa Arena Sandbox đóng kết nối TLS (`SSLZeroReturnError: EOF`) tới `dl.google.com`, `deb.debian.org`, `static.rust-lang.org`, `maven.google.com`, `repo1.maven.org`, `services.gradle.org`, nên không thể tải bổ sung SDK/NDK/JDK/Rust/Clang.
- **`RESOURCE_LIMIT`**: Sandbox chỉ có `2` vCPU, `3.8 GiB` RAM (`0 B` Swap) và `19 GiB` ổ đĩa trống, không đủ cho quá trình biên dịch + liên kết `libxul.so` đa kiến trúc (`arm64-v8a`, `armeabi-v7a`, `x86_64`) của Firefox 150.0.3 (cần máy chủ/VM `>= 16 GiB` RAM+Swap, `>= 50 GiB` ổ cứng trống, `timeout: 36000`).

> **Kết luận trung thực Phase 1**: Kịch bản biên dịch nguồn hoàn chỉnh [`scripts/build-geckoview-from-source.sh`](../scripts/build-geckoview-from-source.sh) đã được xây dựng và đối chiếu từng dòng với mã nguồn `FIREFOX-ANDROID_150_0_3_RELEASE`, nhưng **việc biên dịch thực tế tạo ra tệp AAR GeckoView từ nguồn CHƯA hoàn tất trong Arena Sandbox** do 4 nhóm giới hạn hạ tầng nêu trên. Tuyệt đối không sử dụng AAR dựng sẵn để giả mạo kết quả source build.

---

## 3. Kết Quả Tích Hợp Vào Veko & Kiểm Tra Kiến Trúc Cuối Cùng (Phase 1, 3 & 4)

### 3.1. Cơ chế `mavenLocal()` fail-closed và loại bỏ `maven.mozilla.org` khi build F-Droid

1. **[`scripts/prepare-fdroid-source.sh`](../scripts/prepare-fdroid-source.sh)**:
   - Thực thi trong bước `prebuild:` của F-Droid: ghi lại `settings.gradle.kts` sang chế độ khóa cứng `val useMavenLocalGecko = true`, xóa hoàn toàn khối `https://maven.mozilla.org/maven2/`, và kiểm tra tự động tất cả 9 tệp `*.gradle.kts` đảm bảo không còn bất kỳ URL `maven.mozilla.org` nào.
   - Đồng thời quét `privacy/rust/veko-adblock/vendor/` (sau khi `vendor-rust-deps.sh --prepare` đã đối chiếu SHA-256 toàn bộ 58 crate với `Cargo.lock`) để loại bỏ các tệp mẫu nhị phân trong thư mục `tests/`/`fuzz/`/`benches/` bị `fdroid scanner` bắt lỗi, đồng thời cập nhật bảng `"files"` trong `.cargo-checksum.json` (giữ nguyên khóa `"package"`) để `CARGO_NET_OFFLINE=true cargo build --release --locked --offline` biên dịch thành công 100%.
2. **[`app/build.gradle.kts`](../app/build.gradle.kts) — tác vụ `verifyGeckoViewMavenLocalMode`**:
   - Tự động gắn vào `preBuild` bất cứ khi nào `-Pveko.geckoview.useMavenLocal=true` hoặc sau khi chạy `prepare-fdroid-source.sh`.
   - Kiểm tra trực tiếp sự tồn tại của `geckoview-<version>.aar` và `.pom` trong `~/.m2/repository` (hoặc `-Dmaven.repo.local`), mở ZIP kiểm tra `classes.jar` và `jni/<abi>/libxul.so`, và dừng ngay lập tức với lỗi `[DEPENDENCY_ERROR] Source-built GeckoView artifact missing in mavenLocal` nếu chưa build `MozFennec` từ nguồn (được kiểm chứng tự động trên GitHub Actions `ci-verify`).

### 3.2. Kiểm toán cuối cùng kiến trúc thuần GeckoView & Rust AdBlock (Phase 4)

- **7 module Gradle duy nhất**: `:app`, `:browser-core`, `:privacy`, `:downloads`, `:storage`, `:security`, `:testing`.
- **Không còn bất kỳ dấu vết Chromium / WebView nào**: `0` tham chiếu tới `Chromium Content`, `Blink`, `V8`, `:chromium-native`, `:chromium-embedder`, `BUILD.gn`, `.github/workflows/chromium-upstream-build.yml`, `dual-engine`, `chromium-arch`, hay `android.webkit.WebView` trong toàn bộ `src/main` (được khóa cứng bằng JUnit `ProjectSecurityConfigTest` và `scripts/verify-elf-packaging.py`).
- **Thành phần giữ nguyên và hoạt động ổn định**:
  - Nhân trình duyệt duy nhất: Mozilla GeckoView `150.0.20260511200624` (Gecko WebRender + SpiderMonkey, `minSdk = 26`, `targetSdk = 36`, `compileSdk = 37`).
  - Lá chắn quảng cáo & theo dõi 3 vòng: (1) Native Rust `brave/adblock-rust 0.13.4` biên dịch ngoại tuyến thành `libveko_adblock_jni.so` căn lề 16 KB ELF cho điều hướng cấp trang và truy vấn quy tắc, (2) WebExtension nội bộ `veko-shield` (`webRequestBlocking` + lọc thẩm mỹ CSS ẩn phần tử cho subresource trên GeckoView), và (3) GeckoView Strict ETP + dFPI + GPC. Không tuyên bố sai sự thật rằng riêng tầng JNI điều hướng chặn mọi subresource nếu không đi kèm WebExtension/ETP của GeckoView.
  - Lưu trữ cục bộ Room/SQLite + DataStore, chỉ sao lưu duy nhất tệp cài đặt không nhạy cảm `veko_user_settings.preferences_pb`.

---

## 4. Kết Quả Release Mới (Phase 2 — Tuân Thủ Cổng Chặn Nghiêm Ngặt)

Theo đúng điều kiện bắt buộc của Phase 2 (*"Chỉ thực hiện Phase 2 nếu Phase 1 thành công. KHÔNG tạo release mới nếu GeckoView chưa build từ source thành công. KHÔNG tạo tag giả trỏ tới commit chưa verify"*):

- **Không tạo Git tag mới và không tạo GitHub Release mới** vì bước biên dịch thực tế `MozFennec` từ nguồn chưa được chạy hoàn tất trong F-Droid VM.
- **Bảo toàn nguyên vẹn lịch sử phát hành đã ký số**:
  - `v0.2.0` (`versionCode = 1`) — giữ nguyên.
  - `v0.3.0` (`versionCode = 2`) — giữ nguyên.
  - `v0.4.0` (`versionCode = 3`, commit `ccf7a8bf7a6bcae2d034443ccd666d6b45ef1a38`, `versionName = "0.4.0-privacy-shield"`) — giữ nguyên.
- **Giữ nguyên trường `disable:` trong [`metadata/fdroid/io.veko.browser.yml`](../metadata/fdroid/io.veko.browser.yml)** và **chưa mở Merge Request lên `fdroiddata`**.

---

## 5. Kết Quả Kiểm Định `fdroidserver 2.4.5` Thực Tế (Phase 3)

Đã chạy trực tiếp `fdroidserver 2.4.5` trong Arena Sandbox **và** tích hợp kiểm tra tự động trên GitHub Actions CI (`build-apk.yml`):

1. **`fdroid readmeta`**: **PASSED (`exit_code = 0`, `0` lỗi, `0` cảnh báo)**.
2. **`fdroid lint --format --force-yamllint io.veko.browser`**: **PASSED (`exit_code = 0`, `0` lỗi, `0` cảnh báo)**.
3. **`fdroid scanner --verbose io.veko.browser`**:
   - Trước khi có `scripts/prepare-fdroid-source.sh`: báo `1 problems found` (`ERROR: Found unknown maven repo 'https://maven.mozilla.org/maven2/' at settings.gradle.kts`).
   - Sau khi tích hợp `scripts/prepare-fdroid-source.sh` vào `prebuild:`: **PASSED (`0 problems found`)** cả trong Arena Sandbox và trên GitHub Actions CI (bao gồm quét toàn bộ 58 crate Rust đã vendor trong `privacy/rust/veko-adblock/vendor/` + kiểm tra `CARGO_NET_OFFLINE=true cargo check --release --locked --offline` ngay sau khi quét).
4. **Thử nghiệm thực tế `fdroid build` trong Arena Sandbox (không giả lập kết quả VM)**:
   - Lệnh `fdroid build --verbose --on-server io.veko.browser`: dừng tại `sdkmanager.build_package_list` với `WARNING: Android NDK version '...' could not be found!` và `SSLError(SSLZeroReturnError(6, 'TLS/SSL connection has been closed (EOF)'))` tới `raw.githubusercontent.com` (`TOOLCHAIN_ERROR` + `NETWORK_ERROR`).
   - Lệnh `fdroid build --verbose --server io.veko.browser`: dừng tại `vmtools.get_build_vm` với `could not confirm that either virtualbox or kvm/libvirt are installed` và `RuntimeError: The Vagrant executable cannot be found` (`RESOURCE_LIMIT` + `TOOLCHAIN_ERROR`).

### Quy trình thực thi bước cuối trên máy chủ F-Droid `buildserver` VM đủ tài nguyên

Trên máy chủ Linux có KVM/Vagrant/Docker (`>= 16 GiB` RAM+Swap, `>= 50 GiB` ổ đĩa trống, kết nối mạng để tải `MozFennec` + Android SDK/NDK `29.0.14206865` trong pha chuẩn bị trước khi VM ngắt mạng):

```bash
# 1. Sao chép kho fdroiddata và đặt công thức của Veko
git clone --depth=1 https://gitlab.com/fdroid/fdroiddata.git
cd fdroiddata
cp /path/to/Veko/metadata/fdroid/io.veko.browser.yml metadata/io.veko.browser.yml

# 2. Sau khi gắn tag phát hành mới (versionCode = 4) chứa scripts/prepare-fdroid-source.sh &
#    scripts/build-geckoview-from-source.sh, cập nhật commit/versionCode và gỡ trường disable:, rồi chạy:
fdroid readmeta
fdroid lint --format --force-yamllint io.veko.browser
fdroid scanner --verbose io.veko.browser
fdroid build --verbose --server io.veko.browser:4
```

---

## 6. Trạng Thái Cuối Cùng (Phase 5 Summary)

### Phân tách minh bạch theo từng môi trường thực thi

| Môi trường | Trạng thái xác thực thực tế |
| --- | --- |
| **GitHub Actions CI (`build-apk.yml`)** | **`CI VERIFIED`** — JUnit 7 module, `lintDebug`, offline `adblock-rust 0.13.4` + C++20 JNI 16 KB ELF, `fdroid readmeta` + `fdroid lint` + active `fdroid scanner` (`0 problems found`), fail-closed `verifyReleaseSigningConfig` & `verifyGeckoViewMavenLocalMode`, 3 APK `*-release-unsigned.apk` + Android 14 Emulator tests. |
| **Arena Sandbox (`fdroidserver 2.4.5`)** | **`FDROID LINT & SCANNER PASSED` / `SOURCE BUILD BLOCKED BY SANDBOX`** — `fdroid readmeta`, `fdroid lint --format --force-yamllint`, và `fdroid scanner --verbose` đều đạt `0` lỗi; biên dịch C++/Rust `MozFennec` và khởi tạo F-Droid VM bị chặn bởi `RESOURCE_LIMIT` (`2` vCPU, `3.8 GiB` RAM, `0 B` Swap, `19 GiB` Disk), `TOOLCHAIN_ERROR` và `NETWORK_ERROR`. |
| **F-Droid Isolated Buildserver VM** | **`NOT YET EXECUTED` (`BLOCKED` chờ hạ tầng VM bên ngoài)** — Tuyệt đối **không** ghi nhận `FDROID BUILD VERIFIED` khi chưa chạy hoàn tất `fdroid build` bên trong máy ảo F-Droid thực thụ. |

### Bảng tổng hợp 6 hạng mục bắt buộc

| Hạng mục | Trạng thái | Ghi chú thực tế |
| --- | --- | --- |
| **GeckoView build từ source** | **`BLOCKED` trong Sandbox (`SOURCE RECIPE PREPARED`)** | Đã hoàn thiện `scripts/build-geckoview-from-source.sh` ghim cứng `MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE` (`a077abc2b0f43ed7cc59a8bfcd873e683500d23a`, NDK `29.0.14206865`, `--enable-geckoview-lite`); chưa thể biên dịch ra AAR thực tế trong Arena Sandbox do `RESOURCE_LIMIT` (`3.8 GiB` RAM, `0 B` Swap, `19 GiB` Disk), `TOOLCHAIN_ERROR` (thiếu JDK/Rust/Clang/NDK) và `NETWORK_ERROR` (tường lửa chặn TLS tới `dl.google.com`/`deb.debian.org`/`static.rust-lang.org`). |
| **Loại bỏ prebuilt GeckoView binary** | **`PREPARED & FAIL-CLOSED` (trong chế độ F-Droid)** | `scripts/prepare-fdroid-source.sh` xóa hoàn toàn `https://maven.mozilla.org/maven2/` khỏi `settings.gradle.kts` trong `prebuild:`, và `verifyGeckoViewMavenLocalMode` chặn đứng build nếu thiếu AAR nguồn trong `mavenLocal()`; nhánh CI mặc định vẫn dùng AAR Mozilla Maven cho tới khi hoàn tất build `MozFennec` trên VM. |
| **Veko release mới GeckoView-only** | **`DEFERRED` (Giữ nguyên `v0.4.0` / `versionCode = 3`)** | Tuân thủ nghiêm ngặt quy tắc Phase 2: không tạo tag/release mới khi GeckoView chưa được biên dịch thực tế từ nguồn; mã nguồn hiện tại đã thuần 100% GeckoView (`0.4.0-privacy-shield`, không còn bất kỳ hậu tố hay module `dual-engine`/`chromium` nào). |
| **F-Droid metadata sync** | **`FDROID LINT PASSED`** | `metadata/fdroid/io.veko.browser.yml` đã đồng bộ `srclibs: MozFennec@FIREFOX-ANDROID_150_0_3_RELEASE`, `ndk: 29.0.14206865`, `timeout: 36000`, `prepare-fdroid-source.sh`, `build-geckoview-from-source.sh`, `veko.geckoview.useMavenLocal=true`, vượt qua `fdroid readmeta` & `fdroid lint --format --force-yamllint` (`0` lỗi) và giữ trung thực trường `disable:`. |
| **F-Droid scanner / build** | **`FDROID SCANNER PASSED` / `VM BUILD BLOCKED`** | `fdroid scanner --verbose io.veko.browser` đạt **`0 problems found`** (kể cả trên toàn bộ 58 crate Rust đã vendor); `fdroid build --on-server` / `--server` chưa thể chạy trong Arena Sandbox do thiếu `/dev/kvm`, `vagrant`, Android SDK/NDK và kết nối mạng tải toolchain. |
| **Sẵn sàng mở MR `fdroiddata`** | **`CHƯA SẴN SÀNG` (`DO NOT OPEN MR YET`)** | Đã sẵn sàng toàn bộ công thức nguồn, kiểm định `lint` + `scanner` + `Cargo.lock` ngoại tuyến + unsigned APK; chỉ mở MR lên `gitlab.com/fdroid/fdroiddata` sau khi chạy thành công `fdroid build` trên máy chủ VM đủ tài nguyên (`>= 16 GiB` RAM, `>= 50 GiB` Disk), cắt tag mới (`versionCode = 4`) và gỡ `disable:`. |
