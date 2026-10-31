//! Engine lọc Quảng cáo & Trình theo dõi chuyên dụng của Veko Browser viết bằng Rust (`brave/adblock-rust`).
//! Xuất giao diện C ABI an toàn đa luồng (`Send + Sync`) để liên kết trực tiếp vào đường ống C++ `URLLoaderThrottle`
//! của Chromium Content mà không phát sinh cấp phát JNI trên mỗi subresource request.

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::ptr;
use std::slice;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::RwLock;

pub const VEKO_FILTER_ALLOW: i32 = 0;
pub const VEKO_FILTER_BLOCK_AD: i32 = 1;
pub const VEKO_FILTER_BLOCK_TRACKER: i32 = 2;
pub const VEKO_FILTER_REWRITE_URL: i32 = 3;

static ENGINE_VERSION_CSTR: &[u8] = b"brave/adblock-rust-0.13.4\0";

/// Tập quy tắc cơ sở chuẩn cú pháp Adblock Plus (EasyList + EasyPrivacy + gỡ tham số theo dõi).
pub const BUILTIN_AD_RULES: &str = "\
||doubleclick.net^\n\
||googlesyndication.com^\n\
||googleadservices.com^\n\
||adservice.google.com^\n\
||moatads.com^\n\
||adnxs.com^\n\
||adsrvr.org^\n\
||criteo.com^\n\
||criteo.net^\n\
||taboola.com^\n\
||outbrain.com^\n\
||pubmatic.com^\n\
||rubiconproject.com^\n\
||openx.net^\n\
||smartadserver.com^\n\
||amazon-adsystem.com^\n\
||ads-twitter.com^\n\
||adcolony.com^\n\
||unityads.unity3d.com^\n\
/ads/banner.$image,script,subdocument,third-party\n\
/pagead/js/adsbygoogle.js$script\n\
";

pub const BUILTIN_TRACKER_RULES: &str = "\
||google-analytics.com^\n\
||googletagmanager.com^\n\
||ssl.google-analytics.com^\n\
||connect.facebook.net^$third-party\n\
||pixel.facebook.com^\n\
||analytics.tiktok.com^\n\
||bat.bing.com^\n\
||scorecardresearch.com^\n\
||quantserve.com^\n\
||hotjar.com^$third-party\n\
||mixpanel.com^$third-party\n\
||api.segment.io^\n\
||amplitude.com^$third-party\n\
||fullstory.com^$third-party\n\
||clarity.ms^$third-party\n\
||mouseflow.com^$third-party\n\
||newrelic.com^$script,third-party\n\
||nr-data.net^$third-party\n\
||bugsnag.com^$third-party\n\
||veko-tracker-test.invalid^\n\
";

pub const BUILTIN_REMOVEPARAM_RULES: &str = "\
*$removeparam=utm_source\n\
*$removeparam=utm_medium\n\
*$removeparam=utm_campaign\n\
*$removeparam=utm_term\n\
*$removeparam=utm_content\n\
*$removeparam=fbclid\n\
*$removeparam=gclid\n\
*$removeparam=dclid\n\
*$removeparam=msclkid\n\
*$removeparam=mc_cid\n\
*$removeparam=mc_eid\n\
*$removeparam=_hsenc\n\
*$removeparam=yclid\n\
*$removeparam=twclid\n\
";

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct VekoFilterStats {
    pub total_requests_checked: u64,
    pub ads_blocked: u64,
    pub trackers_blocked: u64,
    pub params_stripped: u64,
    pub active_rule_count: u64,
}

struct CompiledFilterPipelines {
    ad_and_custom_engine: Engine,
    tracker_engine: Engine,
    removeparam_engine: Engine,
    active_rule_count: u64,
}

pub struct VekoAdblockHandle {
    pipelines: RwLock<CompiledFilterPipelines>,
    total_requests_checked: AtomicU64,
    ads_blocked: AtomicU64,
    trackers_blocked: AtomicU64,
    params_stripped: AtomicU64,
}

fn count_effective_rules(text: &str) -> u64 {
    text.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('!') && !line.starts_with('['))
        .count() as u64
}

fn compile_pipelines(custom_rules: &str, include_builtin: bool) -> CompiledFilterPipelines {
    let mut ad_set = FilterSet::new(false);
    let mut tracker_set = FilterSet::new(false);
    let mut param_set = FilterSet::new(false);
    let mut rule_count = 0u64;

    if include_builtin {
        ad_set.add_filter_list(BUILTIN_AD_RULES, ParseOptions::default());
        tracker_set.add_filter_list(BUILTIN_TRACKER_RULES, ParseOptions::default());
        param_set.add_filter_list(BUILTIN_REMOVEPARAM_RULES, ParseOptions::default());
        rule_count += count_effective_rules(BUILTIN_AD_RULES)
            + count_effective_rules(BUILTIN_TRACKER_RULES)
            + count_effective_rules(BUILTIN_REMOVEPARAM_RULES);
    }

    if !custom_rules.trim().is_empty() {
        // Nạp cả quy tắc tùy chỉnh (bao gồm cả quy tắc ngoại lệ @@||) vào cả hai bộ lọc để @@|| có hiệu lực toàn cục
        ad_set.add_filter_list(custom_rules, ParseOptions::default());
        let exceptions_only: String = custom_rules
            .lines()
            .filter(|line| line.trim_start().starts_with("@@"))
            .collect::<Vec<_>>()
            .join("\n");
        if !exceptions_only.is_empty() {
            tracker_set.add_filter_list(&exceptions_only, ParseOptions::default());
            param_set.add_filter_list(&exceptions_only, ParseOptions::default());
        }
        rule_count += count_effective_rules(custom_rules);
    }

    CompiledFilterPipelines {
        ad_and_custom_engine: Engine::new_with_filter_set(ad_set),
        tracker_engine: Engine::new_with_filter_set(tracker_set),
        removeparam_engine: Engine::new_with_filter_set(param_set),
        active_rule_count: rule_count,
    }
}

impl VekoAdblockHandle {
    pub fn new(custom_rules: &str, include_builtin: bool) -> Self {
        Self {
            pipelines: RwLock::new(compile_pipelines(custom_rules, include_builtin)),
            total_requests_checked: AtomicU64::new(0),
            ads_blocked: AtomicU64::new(0),
            trackers_blocked: AtomicU64::new(0),
            params_stripped: AtomicU64::new(0),
        }
    }

    pub fn reload(&self, custom_rules: &str, include_builtin: bool) -> bool {
        let next = compile_pipelines(custom_rules, include_builtin);
        match self.pipelines.write() {
            Ok(mut guard) => {
                *guard = next;
                true
            }
            Err(_) => false,
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn evaluate(
        &self,
        request_url: &str,
        source_url: &str,
        resource_type: &str,
        http_method: &str,
        block_ads: bool,
        block_trackers: bool,
        strip_query_params: bool,
        record_stats: bool,
    ) -> (i32, Option<String>) {
        if record_stats {
            self.total_requests_checked.fetch_add(1, Ordering::Relaxed);
        }

        let effective_source = if source_url.trim().is_empty() {
            request_url
        } else {
            source_url
        };
        let effective_type = if resource_type.trim().is_empty() {
            "other"
        } else {
            resource_type
        };
        let effective_method = if http_method.trim().is_empty() {
            "get"
        } else {
            http_method
        };

        let request = match Request::new(request_url, effective_source, effective_type, effective_method) {
            Ok(req) => req,
            Err(_) => return (VEKO_FILTER_ALLOW, None),
        };

        let guard = match self.pipelines.read() {
            Ok(g) => g,
            Err(_) => return (VEKO_FILTER_ALLOW, None),
        };

        if block_ads {
            let ad_res = guard.ad_and_custom_engine.check_network_request(&request);
            if ad_res.should_block() {
                if record_stats {
                    self.ads_blocked.fetch_add(1, Ordering::Relaxed);
                }
                return (VEKO_FILTER_BLOCK_AD, None);
            }
            if strip_query_params {
                if let Some(rewritten) = ad_res.rewritten_url {
                    if rewritten != request_url {
                        if record_stats {
                            self.params_stripped.fetch_add(1, Ordering::Relaxed);
                        }
                        return (VEKO_FILTER_REWRITE_URL, Some(rewritten));
                    }
                }
            }
        }

        if block_trackers {
            let tr_res = guard.tracker_engine.check_network_request(&request);
            if tr_res.should_block() {
                if record_stats {
                    self.trackers_blocked.fetch_add(1, Ordering::Relaxed);
                }
                return (VEKO_FILTER_BLOCK_TRACKER, None);
            }
        }

        if strip_query_params && request_url.contains('?') {
            let param_res = guard.removeparam_engine.check_network_request(&request);
            if let Some(rewritten) = param_res.rewritten_url {
                if rewritten != request_url {
                    if record_stats {
                        self.params_stripped.fetch_add(1, Ordering::Relaxed);
                    }
                    return (VEKO_FILTER_REWRITE_URL, Some(rewritten));
                }
            }
        }

        (VEKO_FILTER_ALLOW, None)
    }

    pub fn stats(&self) -> VekoFilterStats {
        let active_rules = self
            .pipelines
            .read()
            .map(|g| g.active_rule_count)
            .unwrap_or(0);
        VekoFilterStats {
            total_requests_checked: self.total_requests_checked.load(Ordering::Relaxed),
            ads_blocked: self.ads_blocked.load(Ordering::Relaxed),
            trackers_blocked: self.trackers_blocked.load(Ordering::Relaxed),
            params_stripped: self.params_stripped.load(Ordering::Relaxed),
            active_rule_count: active_rules,
        }
    }

    pub fn reset_stats(&self) {
        self.total_requests_checked.store(0, Ordering::Relaxed);
        self.ads_blocked.store(0, Ordering::Relaxed);
        self.trackers_blocked.store(0, Ordering::Relaxed);
        self.params_stripped.store(0, Ordering::Relaxed);
    }
}

unsafe fn utf8_from_raw<'a>(ptr: *const c_char, len: usize) -> &'a str {
    if ptr.is_null() || len == 0 {
        return "";
    }
    let bytes = unsafe { slice::from_raw_parts(ptr as *const u8, len) };
    std::str::from_utf8(bytes).unwrap_or("")
}

unsafe fn cstr_to_str<'a>(ptr: *const c_char) -> &'a str {
    if ptr.is_null() {
        return "";
    }
    unsafe { CStr::from_ptr(ptr) }.to_str().unwrap_or("")
}

#[no_mangle]
pub extern "C" fn veko_adblock_engine_version() -> *const c_char {
    ENGINE_VERSION_CSTR.as_ptr() as *const c_char
}

#[no_mangle]
pub extern "C" fn veko_adblock_create_default() -> *mut VekoAdblockHandle {
    Box::into_raw(Box::new(VekoAdblockHandle::new("", true)))
}

#[no_mangle]
pub unsafe extern "C" fn veko_adblock_create_with_rules(
    custom_rules_utf8: *const c_char,
    custom_rules_len: usize,
    include_builtin: bool,
) -> *mut VekoAdblockHandle {
    let rules = unsafe { utf8_from_raw(custom_rules_utf8, custom_rules_len) };
    Box::into_raw(Box::new(VekoAdblockHandle::new(rules, include_builtin)))
}

#[no_mangle]
pub unsafe extern "C" fn veko_adblock_reload_rules(
    handle: *mut VekoAdblockHandle,
    custom_rules_utf8: *const c_char,
    custom_rules_len: usize,
    include_builtin: bool,
) -> bool {
    if handle.is_null() {
        return false;
    }
    let rules = unsafe { utf8_from_raw(custom_rules_utf8, custom_rules_len) };
    unsafe { &*handle }.reload(rules, include_builtin)
}

#[no_mangle]
pub unsafe extern "C" fn veko_adblock_evaluate_request(
    handle: *const VekoAdblockHandle,
    request_url: *const c_char,
    source_url: *const c_char,
    resource_type: *const c_char,
    http_method: *const c_char,
    block_ads: bool,
    block_trackers: bool,
    strip_query_params: bool,
    record_stats: bool,
    rewritten_url_out: *mut c_char,
    rewritten_url_capacity: usize,
) -> i32 {
    if handle.is_null() {
        return VEKO_FILTER_ALLOW;
    }
    let req_url = unsafe { cstr_to_str(request_url) };
    let src_url = unsafe { cstr_to_str(source_url) };
    let res_type = unsafe { cstr_to_str(resource_type) };
    let method = unsafe { cstr_to_str(http_method) };

    let (action, rewritten) = unsafe { &*handle }.evaluate(
        req_url,
        src_url,
        res_type,
        method,
        block_ads,
        block_trackers,
        strip_query_params,
        record_stats,
    );

    if let Some(clean_url) = rewritten {
        if !rewritten_url_out.is_null() && rewritten_url_capacity > 1 {
            if let Ok(c_string) = CString::new(clean_url) {
                let bytes = c_string.as_bytes_with_nul();
                if bytes.len() <= rewritten_url_capacity {
                    unsafe {
                        ptr::copy_nonoverlapping(
                            bytes.as_ptr() as *const c_char,
                            rewritten_url_out,
                            bytes.len(),
                        );
                    }
                    return action;
                }
            }
        }
        return VEKO_FILTER_ALLOW;
    }

    action
}

#[no_mangle]
pub unsafe extern "C" fn veko_adblock_get_stats(
    handle: *const VekoAdblockHandle,
    out_stats: *mut VekoFilterStats,
) {
    if handle.is_null() || out_stats.is_null() {
        return;
    }
    let snapshot = unsafe { &*handle }.stats();
    unsafe {
        *out_stats = snapshot;
    }
}

#[no_mangle]
pub unsafe extern "C" fn veko_adblock_reset_stats(handle: *const VekoAdblockHandle) {
    if handle.is_null() {
        return;
    }
    unsafe { &*handle }.reset_stats();
}

#[no_mangle]
pub unsafe extern "C" fn veko_adblock_destroy(handle: *mut VekoAdblockHandle) {
    if !handle.is_null() {
        unsafe {
            drop(Box::from_raw(handle));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    #[test]
    fn blocks_advertising_and_tracking_domains_while_preserving_normal_websites() {
        let engine = VekoAdblockHandle::new("", true);

        let (ad_action, _) = engine.evaluate(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://news.example.com/article",
            "script",
            "get",
            true,
            true,
            true,
            true,
        );
        assert_eq!(ad_action, VEKO_FILTER_BLOCK_AD);

        let (tr_action, _) = engine.evaluate(
            "https://www.google-analytics.com/g/collect?v=2",
            "https://shop.example.com/cart",
            "xmlhttprequest",
            "post",
            true,
            true,
            true,
            true,
        );
        assert_eq!(tr_action, VEKO_FILTER_BLOCK_TRACKER);

        // Không làm gãy tài nguyên hợp lệ của trang web thông thường
        for safe_url in [
            "https://developer.android.com/jetpack/compose",
            "https://www.wikipedia.org/static/images/project-logos/enwiki.png",
            "https://kotlinlang.org/docs/coroutines-overview.html",
        ] {
            let (action, rewritten) = engine.evaluate(
                safe_url,
                "https://developer.android.com/",
                "document",
                "get",
                true,
                true,
                true,
                true,
            );
            assert_eq!(action, VEKO_FILTER_ALLOW);
            assert!(rewritten.is_none());
        }

        let stats = engine.stats();
        assert_eq!(stats.total_requests_checked, 5);
        assert_eq!(stats.ads_blocked, 1);
        assert_eq!(stats.trackers_blocked, 1);
    }

    #[test]
    fn strips_tracking_query_parameters_without_removing_functional_query_args() {
        let engine = VekoAdblockHandle::new("", true);

        let (action, rewritten) = engine.evaluate(
            "https://blog.example.org/post?id=42&utm_source=newsletter&fbclid=IwAR12345&page=2",
            "https://reader.example.net/",
            "document",
            "get",
            true,
            true,
            true,
            true,
        );
        assert_eq!(action, VEKO_FILTER_REWRITE_URL);
        assert_eq!(
            rewritten.as_deref(),
            Some("https://blog.example.org/post?id=42&page=2")
        );
    }

    #[test]
    fn supports_hot_reload_of_custom_rules_and_allowlist_exceptions() {
        let engine = VekoAdblockHandle::new("", true);
        assert!(engine.reload(
            "||custom-adnetwork.example^\n@@||google-analytics.com^$domain=trusted-analytics.example.org",
            true
        ));

        let (custom_blocked, _) = engine.evaluate(
            "https://cdn.custom-adnetwork.example/sdk.js",
            "https://portal.example.org/",
            "script",
            "get",
            true,
            true,
            true,
            false,
        );
        assert_eq!(custom_blocked, VEKO_FILTER_BLOCK_AD);

        let (allowlisted, _) = engine.evaluate(
            "https://www.google-analytics.com/analytics.js",
            "https://trusted-analytics.example.org/dashboard",
            "script",
            "get",
            true,
            true,
            true,
            false,
        );
        assert_eq!(allowlisted, VEKO_FILTER_ALLOW);
    }

    #[test]
    fn evaluates_one_thousand_requests_within_interactive_budget() {
        let engine = VekoAdblockHandle::new("", true);
        let start = Instant::now();
        for i in 0..1_000 {
            let url = if i % 5 == 0 {
                "https://doubleclick.net/pcs/view?xai=AKAOjs"
            } else {
                "https://docs.example.com/assets/chunk.js"
            };
            let _ = engine.evaluate(
                url,
                "https://docs.example.com/index.html",
                "script",
                "get",
                true,
                true,
                true,
                false,
            );
        }
        let elapsed = start.elapsed();
        assert!(
            elapsed.as_millis() < 250,
            "1,000 request evaluations took too long: {:?}",
            elapsed
        );
    }
}
