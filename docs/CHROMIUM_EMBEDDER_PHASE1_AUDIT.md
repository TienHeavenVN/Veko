# VEKO BROWSER — PHASE 1 ARCHITECTURE & DEPENDENCY AUDIT FOR CHROMIUM CONTENT EMBEDDER (`156.0.8078.85`)

- **Target Chromium Version:** `156.0.8078.85`
- **Verified Official Upstream Commit SHA:** `0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5` (`refs/tags/156.0.8078.85`, `Cr-Commit-Position: refs/branch-heads/8078@{#1853}`, branched from `main@{#1706539}` `c50edc4214d81e4f944c3415d55104a49e46d214`, committed `2026-10-31T00:06:25Z`)
- **Working Branch:** `arena/d43b7af7-zeko` (branched from `main` `1186038`)
- **Audit Scope:** `:app`, `:browser-core`, `:chromium-embedder`, `:chromium-native`, `:privacy`, `:downloads`, `:storage`, `:security`, `:testing`, GN/Ninja build files, Gradle Kotlin DSL, ProGuard/R8, APK packaging, and GitHub Actions CI/CD.

---

## 1. Module Dependency Graph (Before Changes)

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ :app (com.android.application • compileSdk 37 • minSdk 26 • targetSdk 36)  │
│  ├─ Jetpack Compose + Material 3 + Hilt + Coil 3 (offline-only cache)      │
│  ├─ Active Web Renderer: org.mozilla.geckoview:geckoview:150.0.*           │
│  └─ Binds EngineSessionBridge -> GeckoEngineSessionBridge                  │
└───────┬──────────────────┬──────────────────┬──────────────────┬───────────┘
        │                  │                  │                  │
        ▼                  ▼                  ▼                  ▼
┌───────────────┐  ┌──────────────────┐ ┌───────────┐    ┌───────────────┐
│ :browser-core │  │:chromium-embedder│ │:downloads │    │   :storage    │
│ TabManager    │──▶ ChromiumEmbedder │ │ MediaStore│    │ Room SQLite v1│
│ MVI Reducer   │  │ Runtime + Context│ │ MIME Guard│    │ DataStore     │
│ EngineContract│  └────────┬─────────┘ └─────┬─────┘    └───────┬───────┘
└───────┬───────┘           │                 │                  │
        │          ┌────────┼─────────────────┴─────────┐        │
        │          ▼        ▼                           ▼        │
        │  ┌──────────────────┐ ┌──────────────┐ ┌───────────────┴─┐
        │  │ :chromium-native │ │   :privacy   │ │    :security    │
        │  │ GN/Ninja C++20   │◀┤ adblock-rust │ │ Origin Perms    │
        │  │ libveko_chromium │ │ 0.13.4 (FFI) │ │ Strict TLS      │
        │  │ _native.so (JNI) │ └──────────────┘ │ UrlNavPolicy    │
        │  └──────────────────┘                  └─────────────────┘
        └───────────────────────────────────────────────▲
                                                        │
                                                ┌───────┴───────┐
                                                │   :testing    │
                                                └───────────────┘
```

---

## 2. Classification Matrix: Real Implementation vs. Interface vs. Broken/Missing

| Subsystem / File | Status at Audit Start | Technical Evidence & Audit Finding |
| :--- | :---: | :--- |
| **Rust Ad/Tracker Filter (`privacy/rust/veko-adblock`)** | **REAL (Compiled & Tested)** | Compiles `adblock = 0.13.4` (`embedded-domain-resolver`, `full-regex-handling`) into `libveko_adblock_ffi.a` for Linux `x86_64` and Android `arm64-v8a`, `armeabi-v7a`, `x86_64`. Exports C ABI (`veko_adblock_evaluate_request`, `$removeparam` URL rewriting, atomic stats). |
| **Standalone C++20 Pipeline (`veko_request_pipeline.*`, `veko_native_context.*`)** | **REAL (Compiled & Tested)** | Compiled by GN + Ninja into `libveko_chromium_native.so` (`-std=c++20`, `-Wl,-z,max-page-size=16384`). Evaluates URLs via Rust FFI, enforces strict TLS denial (`AllowInvalidTlsCertificate == false`), and maintains RAM-isolated OTR vs. disk-backed Regular context cookie maps. |
| **Existing JNI Bridge (`VekoChromiumNative.kt` + `veko_chromium_jni.cc`)** | **PARTIAL (13 Metadata/Filter/Cookie Methods Only)** | Uses `RegisterNatives` in `JNI_OnLoad` for metadata, `BrowserContext` cookie isolation, and URL filter evaluation. **Missing all browser runtime & session control JNI entry points**: `initialize`, `createBrowser`, `loadUrl`, `reload`, `stopLoading`, `goBack`, `goForward`, `canGoBack`, `canGoForward`, `getCurrentUrl`, `attachSurface`, `resizeSurface`, `detachSurface`, `destroyBrowser`, `shutdown`. Also lacks `try / catch (...)` C++ exception guards and UI thread-affinity checks. |
| **Upstream C++20 Chromium Classes (`chromium-native/chromium_src/*`)** | **BROKEN SCAFFOLD (Behind `#ifdef VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT`)** | Verified directly against `chromium/chromium@0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5` (`156.0.8078.85`):<br>1. `VekoBrowserContext::IsOffTheRecord() const override` fails compilation because `content::BrowserContext::IsOffTheRecord()` at line 398 of `content/public/browser/browser_context.h` is **non-const** (`virtual bool IsOffTheRecord() = 0;`).<br>2. `VekoBrowserContext` omits **14 pure virtual methods (`= 0`)** of `content::BrowserContext` (`CreateZoomLevelDelegate`, `GetDownloadManagerDelegate`, `GetGuestManager`, `GetSpecialStoragePolicy`, `GetPlatformNotificationService`, `GetPushMessagingService`, `GetStorageNotificationService`, `GetSSLHostStateDelegate`, `GetPermissionControllerDelegate`, `GetReduceAcceptLanguageControllerDelegate`, `GetClientHintsControllerDelegate`, `GetBackgroundFetchDelegate`, `GetBackgroundSyncController`, `GetBrowsingDataRemoverDelegate`) — cannot be instantiated.<br>3. Missing `VekoBrowserMainParts`, `VekoMainDelegate::InitializeResourceBundle()`, `JNI_OnLoad` (`base::android::InitVM` + `content::android::OnJNIOnLoadInit`), `content::Compositor` bridge, `WebContents` / `NavigationController` / `WebContentsDelegate` / `WebContentsObserver` bridge. |
| **Upstream Build Script (`scripts/build-chromium-android.sh`)** | **BROKEN SCRIPT** | Line 22 & 48 reference `${CHROMIUM_GIT_COMMIT}` under `set -euo pipefail`, whereas `chromium/CHROMIUM_VERSION.lock` defines `CHROMIUM_COMMIT_SHA` — immediate crash. Also builds `content_shell_apk` (`testonly = true`) instead of a clean `//veko_embedder:libveko_content_embedder` target with provenance checksums. |
| **Child Process Services (`ChromiumProcessServices.kt` & `AndroidManifest.xml`)** | **PLACEHOLDER STUB** | `BaseChromiumChildProcessService` returns an empty `android.os.Binder()`, whereas real Chromium `ChildProcessLauncher` binds `org.chromium.content.app.SandboxedProcessService0..N` (`IChildProcessService` AIDL) and requires `org.chromium.content.browser.NUM_SANDBOXED_SERVICES` / `NUM_PRIVILEGED_SERVICES` metadata. |
| **Android Surface & Compose Rendering (`:chromium-embedder`)** | **NOT IMPLEMENTED** | Zero `SurfaceView` / `ANativeWindow` / `Compositor` host or Jetpack Compose viewport exists for Chromium in `:chromium-embedder`. |
| **Browser Core Bridge (`EngineSessionBridge`)** | **GECKOVIEW ONLY** | `EngineContracts.kt` cleanly abstracts `EngineSessionBridge`, `EngineSessionHandle`, and `EngineViewHandle`, but only `GeckoEngineSessionBridge` exists; no `ChromiumEngineSessionBridge` implementation exists yet. |
| **Build Config & SDK/NDK Alignment** | **INCONSISTENT** | `:app` and `:chromium-embedder` use `compileSdk = 37`, while `:chromium-native` uses `compileSdk = 36`. No `CMakeLists.txt` is used (the repository intentionally standardized on **GN + Ninja** via `.gn`, `BUILD.gn`, and `gn/toolchain/BUILD.gn`). |

---

## 3. Verified Upstream Chromium `156.0.8078.85` Facts (`0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5`)

1. **Source & Toolchain Matrix (`DEPS` & `chrome/VERSION`)**:
   - `MAJOR=156`, `MINOR=0`, `BUILD=8078`, `PATCH=85`
   - `android_ndk_version`: `2@30.0.16248370` (`//third_party/android_toolchain/ndk`)
   - `android_sdk_build-tools_version`: `version_37.0.0`
   - `android_sdk_platforms_version`: `android-37.0`
   - `gn_version`: `git_revision:510ec7992c30f172205792f190e96111a896ba8f`
   - `ninja_version`: `version:3@1.12.1.chromium.4`
   - `default_min_sdk_version` in `build/config/android/config.gni`: **`29` (Android 10)** (`min_supported_sdk_version = 27`). When linking the upstream Chromium `156.0.8078.85` binary SDK, the Chromium flavor/packaging requires `minSdk >= 29`.

2. **Why `libcontent.so` Is Not a Standalone Target in Production Android Builds**:
   - Inspection of `content/shell/android/BUILD.gn` at `0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5` confirms that in release builds (`is_component_build = false`), Chromium links `//content/public/app`, `//content/public/browser`, `//content/public/renderer`, `//content/public/gpu`, `//content/public/utility`, Blink, V8, Skia, Viz, and NetworkService into **a single monolithic JNI shared library** via `shared_library_with_jni` (`libveko_content_embedder.so`), accompanied by:
     - `libchrome_crashpad_handler.so` (`//components/crash/core/app:chrome_crashpad_handler_named_as_so`, packaged as a loadable native binary)
     - Uncompressed APK assets (`disable_compression = true`): `veko_content.pak`, `icudtl.dat`, and `v8_context_snapshot_64.bin` (or `v8_context_snapshot_32.bin` on 32-bit)
     - Chromium Android Java runtime classes (`//base:base_java`, `//base:process_launcher_java`, `//content/public/android:content_java`, `//components/embedder_support/android:content_view_java`, `//components/embedder_support/android:view_java`, `//components/viz/service:service_java`, `//mojo/public/java:system_java`, `//net/android:net_java`, `//ui/android:ui_java`, `//url:gurl_java`, `//third_party/jni_zero:jni_zero_java`).
   - Only developer `is_component_build = true` builds produce split `libcontent.cr.so`, `libblink_core.cr.so`, `libv8.cr.so`, etc., which are unsuitable for production APK distribution.

3. **Build Infrastructure Resource Blocker (Measured)**:
   - Current sandbox: `2 vCPU`, `3.8 GiB RAM` (`0 Swap`), `20 GB` free disk, outbound firewall restricted to `github.com`, `npmjs.org`, `pypi.org` (`chromium.googlesource.com` and Google CIPD storage `chrome-infra-packages.appspot.com` are unreachable from inside the sandbox).
   - Standard GitHub-hosted `ubuntu-latest` runner: `4 vCPU`, `16 GB RAM`, `~30–60 GB` free disk, `6h` hard timeout — insufficient for the `~105–120 GB` disk footprint and `~22–28 CPU-hours` / `>20 GB LLD RSS` required to compile Chromium `156.0.8078.85` `//content` + Blink + V8 for `arm64-v8a`.
   - **Valid Engineering Path**: Provide reproducible `depot_tools` bootstrap + `//veko_embedder/BUILD.gn` + `build-chromium-android.sh` + self-hosted Linux runner workflow (`.github/workflows/chromium-upstream-build.yml`) + cryptographic provenance importer (`import-chromium-sdk.sh`), while compiling and testing the complete C++20/JNI session/surface/navigation/security layer (`libveko_chromium_native.so` + `ANativeWindow` + `:chromium-embedder` + `:browser-core`) today without ever faking Blink/V8 rendering.
