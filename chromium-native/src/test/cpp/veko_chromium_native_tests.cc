#include <cassert>
#include <iostream>
#include <string>

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

}  // namespace

int main() {
  TestOffTheRecordBrowserContextIsolation();
  TestRequestPipelineWithRustAdblockAndSecurityPolicies();
  std::cout << "[PASS] veko_chromium_native_tests: Off-The-Record isolation + C++/Rust URL pipeline + TLS policy verified.\n";
  return 0;
}
