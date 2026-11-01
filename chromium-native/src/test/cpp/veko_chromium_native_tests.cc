#include <cassert>
#include <iostream>
#include <string>
#include <thread>

#include "veko_browser_session.h"
#include "veko_native_context.h"
#include "veko_request_pipeline.h"

namespace {

void TestOffTheRecordBrowserContextIsolation() {
  auto& reg = veko::chromium::NativeBrowserContextRegistry::Instance();

  const int64_t regular_ctx = reg.CreateContext(false, "/data/user/0/io.veko.browser/files/profile");
  const int64_t otr_ctx = reg.CreateContext(true, "/data/user/0/io.veko.browser/files/should_be_ignored");

  assert(!reg.IsOffTheRecord(regular_ctx));
  assert(reg.IsOffTheRecord(otr_ctx));
  assert(!reg.GetStoragePath(regular_ctx).empty());
  // Bất biến riêng tư: Off-The-Record BrowserContext tuyệt đối không có đường dẫn lưu trữ đĩa
  assert(reg.GetStoragePath(otr_ctx).empty());

  assert(reg.SetCookie(regular_ctx,
                       "https://accounts.example.com/login",
                       "https://accounts.example.com/",
                       "session_id=regular_token; Secure; HttpOnly",
                       veko::chromium::NativeCookieMode::kBlockThirdParty));

  // Phiên Off-The-Record không nhìn thấy cookie của phiên Regular
  assert(reg.GetCookies(otr_ctx,
                        "https://accounts.example.com/profile",
                        "https://accounts.example.com/",
                        veko::chromium::NativeCookieMode::kBlockThirdParty)
             .empty());

  // Cookie bên thứ ba bị chặn trong cả hai phiên khi đặt kBlockThirdParty
  assert(!reg.SetCookie(regular_ctx,
                        "https://tracker.adnetwork.example/pixel.gif",
                        "https://news.example.com/story",
                        "uid=track_999",
                        veko::chromium::NativeCookieMode::kBlockThirdParty));

  // Cookie bên thứ nhất trong Off-The-Record tồn tại trong RAM của phiên nhưng bị hủy sạch khi đóng phiên
  assert(reg.SetCookie(otr_ctx,
                       "https://mail.example.org/inbox",
                       "https://mail.example.org/",
                       "ephemeral=abc123",
                       veko::chromium::NativeCookieMode::kBlockThirdParty));
  assert(reg.GetCookies(otr_ctx,
                        "https://mail.example.org/inbox",
                        "https://mail.example.org/",
                        veko::chromium::NativeCookieMode::kBlockThirdParty) == "ephemeral=abc123");

  assert(reg.DestroyContext(otr_ctx));
  assert(!reg.Exists(otr_ctx));
  assert(reg.GetCookies(otr_ctx,
                        "https://mail.example.org/inbox",
                        "https://mail.example.org/",
                        veko::chromium::NativeCookieMode::kBlockThirdParty)
             .empty());

  assert(reg.DestroyContext(regular_ctx));
}

void TestRequestPipelineWithRustAdblockAndSecurityPolicies() {
  auto& pipeline = veko::chromium::VekoUrlRequestPipeline::Instance();
  pipeline.ResetStats();

  veko::chromium::RequestEvaluationOptions opts;

  // 1. Chặn quảng cáo từ Rust adblock-rust (ERR_BLOCKED_BY_CLIENT = -20)
  const auto ad = pipeline.EvaluateRequest(
      "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
      "https://article.example.com/",
      "script",
      "GET",
      opts);
  assert(ad.action == veko::chromium::PipelineAction::kBlockAd);
  assert(ad.chromium_net_error == veko::chromium::kErrBlockedByClient);

  // 2. Chặn trình theo dõi bên thứ ba từ Rust adblock-rust
  const auto tracker = pipeline.EvaluateRequest(
      "https://www.google-analytics.com/g/collect?v=2",
      "https://article.example.com/",
      "xmlhttprequest",
      "POST",
      opts);
  assert(tracker.action == veko::chromium::PipelineAction::kBlockTracker);
  assert(tracker.chromium_net_error == veko::chromium::kErrBlockedByClient);

  // 3. Gỡ tham số theo dõi ($removeparam) nhưng giữ nguyên tham số nghiệp vụ
  const auto clean = pipeline.EvaluateRequest(
      "https://shop.example.com/item?sku=981&utm_source=ads&fbclid=xyz987",
      "https://search.example.com/",
      "document",
      "GET",
      opts);
  assert(clean.action == veko::chromium::PipelineAction::kRewriteCleanUrl);
  assert(clean.effective_url == "https://shop.example.com/item?sku=981");

  // 4. Chặn cứng Active Mixed Content (trang HTTPS tải script HTTP)
  const auto mixed = pipeline.EvaluateRequest(
      "http://cdn.insecure.example/app.js",
      "https://bank.example.com/dashboard",
      "script",
      "GET",
      opts);
  assert(mixed.action == veko::chromium::PipelineAction::kBlockMixedContent);
  assert(mixed.chromium_net_error == veko::chromium::kErrMixedContentBlocked);

  // 5. Tự động nâng cấp điều hướng HTTP lên HTTPS nhưng giữ nguyên loopback test server 127.0.0.1
  const auto upgraded = pipeline.EvaluateRequest(
      "http://wikipedia.org/wiki/Privacy",
      "",
      "document",
      "GET",
      opts);
  assert(upgraded.action == veko::chromium::PipelineAction::kUpgradeToHttps);
  assert(upgraded.effective_url == "https://wikipedia.org/wiki/Privacy");

  const auto loopback = pipeline.EvaluateRequest(
      "http://127.0.0.1:8080/index.html",
      "",
      "document",
      "GET",
      opts);
  assert(loopback.action == veko::chromium::PipelineAction::kAllow);
  assert(loopback.effective_url == "http://127.0.0.1:8080/index.html");

  // 6. Chặn scheme nguy hiểm javascript: và chứng chỉ TLS lỗi
  const auto js_scheme = pipeline.EvaluateRequest(
      "javascript:alert(document.cookie)",
      "https://bank.example.com/",
      "document",
      "GET",
      opts);
  assert(js_scheme.action == veko::chromium::PipelineAction::kBlockUnsafeScheme);
  assert(!veko::chromium::VekoUrlRequestPipeline::AllowInvalidTlsCertificate(
      veko::chromium::kErrCertAuthorityInvalid, "expired.badssl.com"));

  const auto stats = pipeline.GetStats();
  assert(stats.ads_blocked == 1);
  assert(stats.trackers_blocked == 1);
  assert(stats.params_stripped == 1);
  assert(stats.mixed_content_blocked == 1);
  assert(stats.https_upgrades == 1);
  assert(stats.active_filter_rules >= 40);
}

void TestRuntimeLifecycleSessionOwnershipAndNavigation() {
  using veko::chromium::NativeRuntimeError;
  using veko::chromium::PipelineAction;
  auto& runtime = veko::chromium::VekoNativeBrowserRuntime::Instance();
  auto& ctx_reg = veko::chromium::NativeBrowserContextRegistry::Instance();
  runtime.ResetForTesting();

  // Gọi CreateBrowser trước Initialize phải trả lỗi kErrNotInitialized (-1)
  const int64_t reg_ctx = ctx_reg.CreateContext(false, "/tmp/veko_test_profile");
  const int64_t otr_ctx = ctx_reg.CreateContext(true, "");
  assert(runtime.CreateBrowser(reg_ctx, false) ==
         static_cast<int64_t>(NativeRuntimeError::kErrNotInitialized));

  // Initialize với đường dẫn rỗng phải từ chối bằng kErrInvalidArgument (-6)
  assert(runtime.Initialize("", true).error == NativeRuntimeError::kErrInvalidArgument);

  const auto init_snap = runtime.Initialize("/tmp/veko_runtime_root", /*enforce_thread_affinity=*/true);
  assert(init_snap.error == NativeRuntimeError::kOk);
  assert(init_snap.initialized);
  assert(!init_snap.upstream_content_linked);

  // BrowserContext không tồn tại phải trả kErrInvalidContext (-4)
  assert(runtime.CreateBrowser(999999, false) ==
         static_cast<int64_t>(NativeRuntimeError::kErrInvalidContext));

  const int64_t reg_browser = runtime.CreateBrowser(reg_ctx, false);
  const int64_t otr_browser = runtime.CreateBrowser(otr_ctx, true);
  assert(reg_browser > 0);
  assert(otr_browser > reg_browser);
  assert(!runtime.IsDesktopMode(reg_browser));
  assert(runtime.IsDesktopMode(otr_browser));

  // Điều hướng nâng cấp HTTP -> HTTPS và tháo tham số theo dõi
  const auto nav1 = runtime.LoadUrl(reg_browser, "http://example.org/docs?page=1&utm_source=test");
  assert(nav1.error == NativeRuntimeError::kOk);
  assert(nav1.final_url == "https://example.org/docs?page=1");
  assert(!nav1.upstream_content_rendered);
  assert(!runtime.CanGoBack(reg_browser));

  const auto nav2 = runtime.LoadUrl(reg_browser, "https://developer.android.com/jetpack");
  assert(nav2.error == NativeRuntimeError::kOk);
  assert(runtime.CanGoBack(reg_browser));
  assert(!runtime.CanGoForward(reg_browser));
  assert(runtime.GoBack(reg_browser));
  assert(runtime.GetCurrentUrl(reg_browser) == "https://example.org/docs?page=1");
  assert(runtime.CanGoForward(reg_browser));
  assert(runtime.GoForward(reg_browser));
  assert(runtime.GetCurrentUrl(reg_browser) == "https://developer.android.com/jetpack");

  // Chặn điều hướng quảng cáo/scheme nguy hiểm bằng kErrBlockedByPolicy (-7)
  const auto blocked = runtime.LoadUrl(reg_browser, "javascript:window.open()");
  assert(blocked.error == NativeRuntimeError::kErrBlockedByPolicy);
  assert(blocked.pipeline_action == PipelineAction::kBlockUnsafeScheme);
  assert(runtime.GetCurrentUrl(reg_browser) == "https://developer.android.com/jetpack");

  // Hủy phiên theo Context đảm bảo không còn BrowserInstance mồ côi
  assert(runtime.DestroyBrowsersForContext(otr_ctx) == 1);
  assert(ctx_reg.DestroyContext(otr_ctx));
  assert(runtime.LoadUrl(otr_browser, "https://mozilla.org").error ==
         NativeRuntimeError::kErrInvalidBrowserHandle);

  assert(runtime.DestroyBrowser(reg_browser) == NativeRuntimeError::kOk);
  assert(runtime.DestroyBrowser(reg_browser) == NativeRuntimeError::kErrInvalidBrowserHandle);
  assert(ctx_reg.DestroyContext(reg_ctx));
  assert(runtime.Shutdown() == NativeRuntimeError::kOk);
  assert(runtime.Shutdown() == NativeRuntimeError::kErrAlreadyShutdown);
}

void TestStrictThreadAffinityAndSurfaceLifecycle() {
  using veko::chromium::NativeRuntimeError;
  auto& runtime = veko::chromium::VekoNativeBrowserRuntime::Instance();
  auto& ctx_reg = veko::chromium::NativeBrowserContextRegistry::Instance();
  runtime.ResetForTesting();

  const auto init = runtime.Initialize("/tmp/veko_thread_guard", /*enforce_thread_affinity=*/true);
  assert(init.error == NativeRuntimeError::kOk);
  const int64_t ctx = ctx_reg.CreateContext(false, "/tmp/veko_thread_ctx");
  const int64_t browser = runtime.CreateBrowser(ctx, false);
  assert(browser > 0);

  // Kiểm thử gắn/đổi kích thước/tháo Surface trên luồng chủ sở hữu
  assert(runtime.AttachSurface(nullptr, browser, nullptr, 1080, 1920, 420) ==
         NativeRuntimeError::kOk);
  const auto attached = runtime.GetSurfaceState(browser);
  assert(attached.attached);
  assert(attached.width_px == 1080 && attached.height_px == 1920);
  assert(attached.attach_generation == 1);

  assert(runtime.ResizeSurface(browser, 1920, 1080) == NativeRuntimeError::kOk);
  assert(runtime.GetSurfaceState(browser).width_px == 1920);
  assert(runtime.DetachSurface(browser) == NativeRuntimeError::kOk);
  assert(!runtime.GetSurfaceState(browser).attached);

  // Mọi thao tác thay đổi trạng thái gọi từ worker thread khác phải trả về kErrWrongThread (-3)
  NativeRuntimeError worker_create_err = NativeRuntimeError::kOk;
  NativeRuntimeError worker_load_err = NativeRuntimeError::kOk;
  NativeRuntimeError worker_attach_err = NativeRuntimeError::kOk;
  NativeRuntimeError worker_shutdown_err = NativeRuntimeError::kOk;

  std::thread worker([&]() {
    worker_create_err = static_cast<NativeRuntimeError>(runtime.CreateBrowser(ctx, false));
    worker_load_err = runtime.LoadUrl(browser, "https://example.com").error;
    worker_attach_err = runtime.AttachSurface(nullptr, browser, nullptr, 800, 600, 320);
    worker_shutdown_err = runtime.Shutdown();
  });
  worker.join();

  assert(worker_create_err == NativeRuntimeError::kErrWrongThread);
  assert(worker_load_err == NativeRuntimeError::kErrWrongThread);
  assert(worker_attach_err == NativeRuntimeError::kErrWrongThread);
  assert(worker_shutdown_err == NativeRuntimeError::kErrWrongThread);

  // Luồng chủ vẫn tắt runtime sạch sẽ và giải phóng toàn bộ tài nguyên
  assert(runtime.Shutdown() == NativeRuntimeError::kOk);
  assert(runtime.ActiveBrowserCount() == 0);
  assert(ctx_reg.DestroyContext(ctx));
}

}  // namespace

int main() {
  TestOffTheRecordBrowserContextIsolation();
  TestRequestPipelineWithRustAdblockAndSecurityPolicies();
  TestRuntimeLifecycleSessionOwnershipAndNavigation();
  TestStrictThreadAffinityAndSurfaceLifecycle();
  std::cout << "[PASS] veko_chromium_native_tests: OTR isolation + Rust/C++ URL pipeline + "
               "runtime lifecycle + thread-affinity + surface RAII verified.\n";
  return 0;
}
