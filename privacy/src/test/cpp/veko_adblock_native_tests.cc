#include <cassert>
#include <cstring>
#include <iostream>
#include <string>

#include "veko_adblock_ffi.h"
#include "veko_shield_pipeline.h"

namespace {

void TestRustFfiMemorySafetyAndNullGuards() {
  // Kiểm tra an toàn con trỏ null và bộ đệm nhỏ trên giao diện C ABI của Rust
  veko_adblock_destroy(nullptr);
  assert(!veko_adblock_reload_rules(nullptr, nullptr, 0, true));
  assert(veko_adblock_evaluate_request(
             nullptr, "https://example.com", "", "document", "GET",
             true, true, true, false, nullptr, 0) == VEKO_FILTER_ALLOW);
  assert(veko_adblock_cosmetic_css_for_url(nullptr, "https://example.com", nullptr, 0) == 0);

  VekoAdblockHandle* handle = veko_adblock_create_default();
  assert(handle != nullptr);

  char tiny_buf[4] = {};
  const int32_t truncated_rewrite = veko_adblock_evaluate_request(
      handle,
      "https://example.org/article?id=7&utm_source=test",
      "https://example.org/",
      "document",
      "GET",
      true,
      true,
      true,
      false,
      tiny_buf,
      sizeof(tiny_buf));
  // Khi bộ đệm nhận URL viết lại không đủ sức chứa, FFI phải fail-safe về ALLOW và không tràn bộ nhớ
  assert(truncated_rewrite == VEKO_FILTER_ALLOW);

  veko_adblock_destroy(handle);
}

void TestPipelineFilteringHttpsUpgradeMixedContentAndLifecycle() {
  auto& pipeline = veko::privacy::VekoUrlRequestPipeline::Instance();
  pipeline.ResetStats();

  const char* engine_ver = pipeline.AdblockEngineVersion();
  assert(engine_ver != nullptr);
  assert(std::string(engine_ver).find("adblock-rust-0.13.4") != std::string::npos);

  veko::privacy::RequestEvaluationOptions opts{};

  // 1. Chặn mạng quảng cáo & trình theo dõi
  const auto ad = pipeline.EvaluateRequest(
      "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
      "https://news.example.com/",
      "script",
      "GET",
      opts);
  assert(ad.action == veko::privacy::PipelineAction::kBlockAd);
  assert(ad.reason_code == veko::privacy::kReasonBlockedByShield);

  const auto tracker = pipeline.EvaluateRequest(
      "https://www.google-analytics.com/g/collect?v=2",
      "https://shop.example.com/",
      "xmlhttprequest",
      "POST",
      opts);
  assert(tracker.action == veko::privacy::PipelineAction::kBlockTracker);

  // 2. Làm sạch tham số theo dõi ($removeparam)
  const auto cleaned = pipeline.EvaluateRequest(
      "https://blog.example.org/read?id=99&utm_medium=social&fbclid=IwAR_test&lang=vi",
      "https://blog.example.org/",
      "document",
      "GET",
      opts);
  assert(cleaned.action == veko::privacy::PipelineAction::kRewriteCleanUrl);
  assert(cleaned.effective_url == "https://blog.example.org/read?id=99&lang=vi");

  // 3. Tự động nâng cấp điều hướng HTTP -> HTTPS (nhưng giữ nguyên loopback 127.0.0.1)
  const auto upgraded = pipeline.EvaluateRequest(
      "http://example.com/docs",
      "",
      "document",
      "GET",
      opts);
  assert(upgraded.action == veko::privacy::PipelineAction::kUpgradeToHttps);
  assert(upgraded.effective_url == "https://example.com/docs");

  const auto loopback = pipeline.EvaluateRequest(
      "http://127.0.0.1:8080/local-test",
      "",
      "document",
      "GET",
      opts);
  assert(loopback.action == veko::privacy::PipelineAction::kAllow);
  assert(loopback.effective_url == "http://127.0.0.1:8080/local-test");

  // 4. Chặn Mixed Content và lược đồ nguy hiểm
  const auto mixed = pipeline.EvaluateRequest(
      "http://cdn.insecure.example/app.js",
      "https://bank.example.com/",
      "script",
      "GET",
      opts);
  assert(mixed.action == veko::privacy::PipelineAction::kBlockMixedContent);

  const auto js_scheme = pipeline.EvaluateRequest(
      "javascript:alert(1)",
      "https://example.com/",
      "document",
      "GET",
      opts);
  assert(js_scheme.action == veko::privacy::PipelineAction::kBlockUnsafeScheme);

  // 5. Tạo CSS ẩn phần tử quảng cáo & tôn trọng chính sách TLS fail-closed
  const std::string css = pipeline.GetCosmeticCssForUrl("https://news.example.com/article");
  assert(css.find(".adsbygoogle") != std::string::npos);
  assert(!veko::privacy::VekoUrlRequestPipeline::AllowInvalidTlsCertificate(
      -202, "expired.badssl.com"));

  // 6. Kiểm tra giải phóng tài nguyên gốc (ReleaseNativeResources) và tự khôi phục an toàn
  assert(pipeline.ReleaseNativeResources());
  assert(!pipeline.IsHandleAllocated());
  assert(!pipeline.ReleaseNativeResources());

  const auto after_release = pipeline.EvaluateRequest(
      "https://media1.admicro.vn/ads_box.js",
      "https://vnexpress.example/",
      "script",
      "GET",
      opts);
  assert(after_release.action == veko::privacy::PipelineAction::kBlockAd);
  assert(pipeline.IsHandleAllocated());
}

}  // namespace

int main() {
  TestRustFfiMemorySafetyAndNullGuards();
  TestPipelineFilteringHttpsUpgradeMixedContentAndLifecycle();
  std::cout << "[PASS] veko_adblock_native_tests: all Rust FFI & C++20 Shield checks passed.\n";
  return 0;
}
