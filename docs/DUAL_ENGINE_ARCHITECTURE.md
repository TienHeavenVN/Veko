# Veko Browser — Dual-Engine Architecture (`GeckoView` Primary + `Chromium Content` Secondary)

## 1. Repository Audit & Architecture Assessment

### 1.1 Pre-Migration Audit Summary
Before introducing the Dual-Engine abstraction, Veko Browser (`io.veko.browser`) consisted of **9 Gradle Kotlin DSL modules** (`:app`, `:browser-core`, `:chromium-embedder`, `:chromium-native`, `:privacy`, `:downloads`, `:storage`, `:security`, `:testing`) with:
- **Official Mozilla GeckoView (`150.0.20260511200624`)** pulled from `https://maven.mozilla.org/maven2/`, managed by `BrowserRuntimeManager` and `GeckoEngineSessionBridge`.
- **Chromium Content `156.0.8078.85` (`0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5`)** embedder infrastructure (`chromium/CHROMIUM_VERSION.lock`, `chromium/veko_embedder/BUILD.gn`, `scripts/{bootstrap-depot-tools.sh,build-chromium-android.sh,import-chromium-sdk.sh,verify-elf-packaging.py}`, `:chromium-native` C++20/JNI + `brave/adblock-rust 0.13.4`, `:chromium-embedder` `ChromiumEmbedderRuntime` + `ChromiumSurfaceHostView`, and `ChromiumEngineSessionBridge`).
- **Identified Single-Engine Limitations Fixed in This Migration**:
  1. `EngineModule` previously bound a single process-wide `EngineSessionBridge` at startup rather than routing sessions per tab through a unified `BrowserEngineManager`.
  2. `Tab` (`Tab.kt`) and `SessionPersistenceCodec` had no per-tab `EngineType`, making it impossible to keep existing tabs on their active engine when changing Settings or choosing an engine per new tab.
  3. `TabManager.createTab` previously touched `chromiumEmbedderRuntime.acquireOffTheRecordContext()` on private tab creation even when running on GeckoView.
  4. `MainActivity.kt` imported `org.mozilla.geckoview.GeckoView` directly, and Settings / Tab Switcher lacked Dual-Engine diagnostics and selection controls.

---

## 2. Dual-Engine Architecture Overview

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                    Jetpack Compose UI (:app/ui/*)                         │
│  BrowserScreen • TabSwitcherSheet • SettingsSheet • SiteSecuritySheet     │
│  (Zero direct imports of org.mozilla.geckoview.* or org.chromium.*)       │
└────────────────────────────────────┬──────────────────────────────────────┘
                                     ▼
┌───────────────────────────────────────────────────────────────────────────┐
│               UDF / MVI & Tab Coordinator (:browser-core)                 │
│  BrowserMviStore • BrowserReducer • TabManager • SessionPersistenceCodec  │
│  Per-tab Tab.engineType (GECKO | CHROMIUM) • JavaScriptEvaluationPolicy   │
└────────────────────────────────────┬──────────────────────────────────────┘
                                     ▼
┌───────────────────────────────────────────────────────────────────────────┐
│          BrowserEngineManager (MultiEngineSessionBridge + TrimMemory)     │
│  Lazy single-engine initialization • Strict SDK gate • Profile isolation  │
└──────────────────┬─────────────────────────────────────┬──────────────────┘
                   ▼                                     ▼
┌─────────────────────────────────────┐ ┌───────────────────────────────────┐
│ GeckoBrowserEngine (PRIMARY/DEFAULT)│ │ ChromiumBrowserEngine (SECONDARY) │
│ • Official Mozilla Maven SDK        │ │ • Pinned 156.0.8078.85            │
│   150.0.20260511200624              │ │   (0c5c70590a804c285f0cafe8e9...) │
│ • Rendering: Gecko (WebRender)      │ │ • Rendering: Blink                │
│ • JavaScript: SpiderMonkey          │ │ • JavaScript: V8                  │
│ • GeckoSettings • GeckoSessionMgr   │ │ • ChromiumAvailabilityChecker     │
│ • Isolated Mozilla profile & dFPI   │ │ • ChromiumSessionManager          │
└─────────────────────────────────────┘ └───────────────────────────────────┘
```

### Package Layout (`io.veko.browser.engine.*`)
- **`:storage` (`storage/src/main/java/io/veko/browser/engine/`)**:
  - `EngineType.kt` (`GECKO` default, `CHROMIUM` secondary)
  - `EngineCapabilities.kt`
  - `EngineAvailability.kt` (`EngineAvailabilityStatus`, `SdkValidationStatus`, `EngineSelectionResult`)
- **`:browser-core` (`browser-core/src/main/java/io/veko/browser/engine/`)**:
  - `BrowserEngine.kt` (`BrowserEngine`, `JavaScriptEvalResult`, `JavaScriptEvaluationPolicy`)
  - `MultiEngineSessionBridge` in `tab/EngineContracts.kt`
- **`:app` (`app/src/main/java/io/veko/browser/engine/`)**:
  - `BrowserEngineManager.kt`
  - `BrowserRuntimeManager.kt`
  - `gecko/GeckoBrowserEngine.kt`
  - `gecko/GeckoSessionManager.kt`
  - `gecko/GeckoSettings.kt`
  - `chromium/ChromiumBrowserEngine.kt`
  - `chromium/ChromiumSessionManager.kt`
  - `chromium/ChromiumAvailabilityChecker.kt`

---

## 3. GeckoView Setup (Primary / Default Engine)

- **Official Artifact Source**: Resolved directly from Mozilla's official Maven repository (`https://maven.mozilla.org/maven2/` in `settings.gradle.kts`) via `org.mozilla.geckoview:geckoview:150.0.20260511200624` in `app/build.gradle.kts` — no manual binary copying or local Gecko source build required.
- **Lazy Single-Runtime Lifecycle**: `GeckoBrowserEngine` defers `GeckoRuntime.create(...)` until the first `GECKO` tab or platform surface is opened, reuses a single `GeckoView` across `GECKO` tabs, enforces a 2-tier LRU cap (`MAX_LIVE_SESSIONS = 6`), and handles child `:tab` process crashes via `CrashedTabOverlay` without crashing the browser UI.
- **Hardened Defaults (`GeckoSettings.kt`)**:
  - `enterpriseRootsEnabled(false)` (strict TLS certificate verification; zero cert bypass)
  - `globalPrivacyControlEnabled(true)` (`Sec-GPC: 1`)
  - `aboutConfigEnabled(false)`, `remoteDebuggingEnabled(false)`, `consoleOutput(false)`, `debugLogging(false)`
  - Dynamic First-Party Isolation (`ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS`) in `BLOCK_THIRD_PARTY` and Private Browsing modes.

---

## 4. Chromium Content `156.0.8078.85` Integration (Secondary Engine)

- **Pinned Upstream Revision (`chromium/CHROMIUM_VERSION.lock`)**:
  - Version: `156.0.8078.85`
  - Commit SHA: `0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5` (`refs/branch-heads/8078@{#1853}`)
  - Primary ABI target: `arm64-v8a` (with `armeabi-v7a` and `x86_64` supported when corresponding native artifacts are imported)
- **Strict SDK Validation (`ChromiumAvailabilityChecker.kt`)**:
  Validates artifacts derived from the real GN target graph (`chromium/veko_embedder/BUILD.gn`: `veko_chromium_sdk`) **without** eagerly loading native libraries:
  1. Device ABI compatibility (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
  2. Monolithic Content shared library `libveko_content_embedder.so` (> 1 MB, 16 KB ELF page-aligned).
  3. Uncompressed resource bundles: `veko_content.pak`, `icudtl.dat`, and V8 context snapshot (`v8_context_snapshot_{64,32}.bin` / `snapshot_blob.bin`).
  4. Upstream Java runtime classes (`BrowserStartupController` & `ContentChildProcessServiceDelegate`).
  5. Authentic C++ JNI Blink/V8 linkage flag (`VekoChromiumNative.isUpstreamContentLinked() == true`), defeating fake or 0-byte placeholder files.
- **Building & Importing the Real Chromium SDK on a Dedicated Linux Host (>= 32 GB RAM, >= 120 GB NVMe)**:
  ```bash
  ./scripts/bootstrap-depot-tools.sh --install-dir "$HOME/depot_tools"
  ./scripts/build-chromium-android.sh --chromium-src /mnt/nvme/chromium/src --abi=arm64-v8a
  ./scripts/import-chromium-sdk.sh --out-dir /mnt/nvme/chromium/src/out/Default --abi=arm64-v8a
  ```
  Once imported, `ChromiumAvailabilityChecker` transitions from `BLOCKED (MISSING_UPSTREAM_SDK)` to `AVAILABLE (VERIFIED_NATIVE_SDK)` and unlocks Chromium selection in Settings and New Tab creation.

---

## 5. Engine Selection, Switching & Isolation Rules

1. **Settings -> Browser Engine (`Nhân trình duyệt`)**:
   - Displays **Engine name**, **Engine version** (+ pinned commit), **Availability status**, **Rendering engine**, **JavaScript engine**, **ABI compatibility**, **SDK validation status**, and **exact unavailability reason / missing artifacts** when Chromium is `BLOCKED` or `UNAVAILABLE`.
2. **Non-Destructive Switching**:
   - Changing the preferred engine in Settings updates `VekoSettings.preferredEngine` for **newly created tabs only**.
   - Existing open tabs **never** have their engine silently replaced.
   - Users can explicitly tap **"Mở URL hiện tại trong tab mới"** (`openCurrentUrlInNewTabWithEngine`) to open the active page's sanitized URL in a new tab under the newly selected engine.
   - Users can also pick the engine per new tab directly in the **Tab Switcher** sheet, and every tab card displays its active engine badge (`GeckoView` vs `Chromium`).
3. **Strict Profile, Cookie & Private-Browsing Isolation**:
   - GeckoView uses Mozilla's internal profile & `StorageController`; Chromium uses isolated `files/chromium_profile` (regular) and an in-memory Off-The-Record `BrowserContext` (`GetPath() == ""`, destroyed immediately when the last Chromium private tab closes).
   - If a persisted `SessionPersistenceCodec` v3 tab has `engineType = CHROMIUM` on an APK where Chromium is unavailable, `TabManager` converts the restored tab honestly to `EngineType.GECKO` and clears `serializedEngineState` to prevent cross-engine state corruption.

---

## 6. Build, Verification & CI Instructions

```bash
# Run all JUnit unit tests (across :app and :testing) + Android Lint
./gradlew :app:testDebugUnitTest :app:lintDebug

# Build ABI-split Debug & R8 Preview APKs
./gradlew :app:assembleDebug :app:assemblePreview

# Run instrumented Dual-Engine + real GeckoView (SpiderMonkey JS) tests on Android Emulator
./gradlew :app:connectedDebugAndroidTest

# Verify Chromium 156.0.8078.85 lockfile & upstream tag provenance (no 110 GB checkout required)
./scripts/build-chromium-android.sh --preflight-only --abi=arm64-v8a
```

- **Standard CI (`.github/workflows/build-apk.yml`)**: Runs Rust FFI + GN/Ninja C++20 tests, JUnit tests, Android Lint, ABI-split Debug/Preview APKs, 16 KB ELF verification, and KVM API 34 Emulator instrumented tests on every push/PR.
- **Dedicated Chromium SDK Build CI (`.github/workflows/chromium-upstream-build.yml`)**: Kept strictly separate via manual `workflow_dispatch` for self-hosted high-RAM Linux builders.

---

## 7. Known Limitations

- **Full Upstream Chromium Binary Size & Build Hardware**: Compiling `libveko_content_embedder.so` (Blink + V8 + `//content` at `156.0.8078.85`) requires a Linux x86_64 machine with >= 32 GB RAM and >= 110–130 GB free NVMe storage, which exceeds standard GitHub-hosted `ubuntu-latest` runners (16 GB RAM, ~14–60 GB disk). Until imported via `scripts/import-chromium-sdk.sh`, `ChromiumBrowserEngine` remains honestly `BLOCKED` in Settings while `GeckoBrowserEngine` serves all live web traffic by default.
- **Host-Injected JavaScript Evaluation**: Standard web page JavaScript executes natively in SpiderMonkey (GeckoView) and V8 (when Chromium SDK is linked). Direct arbitrary host-to-page JS injection is intentionally restricted by `JavaScriptEvaluationPolicy` and GeckoView's WebExtension security boundary (preventing `WebView.evaluateJavascript` / `javascript:` URI injection vulnerabilities).
