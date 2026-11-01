//! Engine lọc Quảng cáo, Trình theo dõi & Tham số URL của Veko Browser viết bằng Rust (`brave/adblock-rust`).
//! Độc lập hoàn toàn với browser engine, xuất giao diện C ABI an toàn đa luồng (`Send + Sync`) để liên kết
//! trực tiếp vào cầu nối JNI `libveko_adblock_jni.so` phục vụ Mozilla GeckoView mà không phát sinh cấp phát dư thừa.

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use std::collections::BTreeSet;
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

/// Tập quy tắc chặn mạng quảng cáo quốc tế, pop-under, video VAST/IMA, SDK quảng cáo di động và mạng quảng cáo khu vực VN.
pub const BUILTIN_AD_RULES: &str = "\
||doubleclick.net^\n\
||googlesyndication.com^\n\
||googleadservices.com^\n\
||adservice.google.com^\n\
||2mdn.net^\n\
||imasdk.googleapis.com/js/sdkloader/ima3.js$script,third-party\n\
||moatads.com^\n\
||adnxs.com^\n\
||adsrvr.org^\n\
||criteo.com^\n\
||criteo.net^\n\
||taboola.com^\n\
||outbrain.com^\n\
||mgid.com^\n\
||revcontent.com^\n\
||zergnet.com^\n\
||pubmatic.com^\n\
||rubiconproject.com^\n\
||openx.net^\n\
||casalemedia.com^\n\
||indexww.com^\n\
||smartadserver.com^\n\
||teads.tv^\n\
||sovrn.com^\n\
||lijit.com^\n\
||33across.com^\n\
||triplelift.com^\n\
||sharethrough.com^\n\
||gumgum.com^\n\
||media.net^\n\
||yieldmo.com^\n\
||kargo.com^\n\
||spotxchange.com^\n\
||springserve.com^\n\
||amazon-adsystem.com^\n\
||ads.yahoo.com^\n\
||advertising.com^\n\
||ads-twitter.com^\n\
||adcolony.com^\n\
||unityads.unity3d.com^\n\
||applovin.com^\n\
||applvn.com^\n\
||ironsrc.com^\n\
||vungle.com^\n\
||chartboost.com^\n\
||inmobi.com^\n\
||fyber.com^\n\
||mintegral.com^\n\
||pangleglobal.com^\n\
||popads.net^\n\
||popcash.net^\n\
||propellerads.com^\n\
||adsterra.com^\n\
||hilltopads.net^\n\
||exoclick.com^\n\
||juicyads.com^\n\
||trafficjunky.net^\n\
||clickadu.com^\n\
||galaksion.com^\n\
||mondiad.com^\n\
||evadav.com^\n\
||richads.com^\n\
||admicro.vn^\n\
||vcmedia.vn/ads/$third-party\n\
||eclick.vn^\n\
||adtima.vn^\n\
||ads.zalo.me^\n\
||yomedia.vn^\n\
||polyad.net^\n\
||ambientplatform.vn^\n\
||ants.vn^\n\
||dable.io^\n\
||microad.jp^\n\
/ads/banner.$image,script,subdocument,third-party\n\
/pagead/js/adsbygoogle.js$script\n\
/pagead/show_ads.js$script\n\
/adserver/$script,subdocument,xmlhttprequest,third-party\n\
/popunder.$script,third-party\n\
";

/// Tập quy tắc bảo vệ người dùng trước trình theo dõi chéo trang, ghi hình phiên (session-replay), fingerprinting & pixel.
pub const BUILTIN_TRACKER_RULES: &str = "\
||google-analytics.com^\n\
||googletagmanager.com^\n\
||ssl.google-analytics.com^\n\
||analytics.google.com^\n\
||stats.g.doubleclick.net^\n\
||connect.facebook.net^$third-party\n\
||pixel.facebook.com^\n\
||facebook.com/tr^$image,script,xmlhttprequest,ping,third-party\n\
||analytics.tiktok.com^\n\
||business-api.tiktok.com^$third-party\n\
||bat.bing.com^\n\
||clarity.ms^$third-party\n\
||scorecardresearch.com^\n\
||quantserve.com^\n\
||comscore.com^$third-party\n\
||omtrdc.net^\n\
||2o7.net^\n\
||demdex.net^\n\
||krxd.net^\n\
||bluekai.com^\n\
||exelator.com^\n\
||rlcdn.com^\n\
||lotame.com^\n\
||crwdcntrl.net^\n\
||tapad.com^\n\
||agkn.com^\n\
||liadm.com^\n\
||id5-sync.com^\n\
||uidapi.com^\n\
||hotjar.com^$third-party\n\
||fullstory.com^$third-party\n\
||mouseflow.com^$third-party\n\
||luckyorange.com^$third-party\n\
||smartlook.com^$third-party\n\
||logrocket.com^$third-party\n\
||logrocket.io^$third-party\n\
||inspectlet.com^$third-party\n\
||crazyegg.com^$third-party\n\
||heapanalytics.com^$third-party\n\
||contentsquare.net^$third-party\n\
||quantummetric.com^$third-party\n\
||glassboxdigital.io^$third-party\n\
||sessioncam.com^$third-party\n\
||fpjs.io^$third-party\n\
||fpnpmcdn.net^$third-party\n\
||openfpcdn.io^$third-party\n\
||fingerprintjs.com^$third-party\n\
||online-metrix.net^$third-party\n\
||iovation.com^$third-party\n\
||siftscience.com^$third-party\n\
||mixpanel.com^$third-party\n\
||api.segment.io^\n\
||cdn.segment.com^$third-party\n\
||amplitude.com^$third-party\n\
||posthog.com^$third-party\n\
||rudderlabs.com^$third-party\n\
||kissmetrics.com^$third-party\n\
||chartbeat.com^$third-party\n\
||parsely.com^$third-party\n\
||snowplowanalytics.com^$third-party\n\
||newrelic.com^$script,third-party\n\
||nr-data.net^$third-party\n\
||browser-intake-datadoghq.com^$third-party\n\
||bugsnag.com^$third-party\n\
||snap.licdn.com^$third-party\n\
||px.ads.linkedin.com^\n\
||ct.pinterest.com^\n\
||trk.pinterest.com^\n\
||tr.snapchat.com^\n\
||sc-static.net/scevent.min.js$script,third-party\n\
||alb.reddit.com^$third-party\n\
||events.redditmedia.com^$third-party\n\
||static.ads-twitter.com^\n\
||analytics.twitter.com^\n\
||appsflyer.com^$third-party\n\
||adjust.com^$third-party\n\
||branch.io^$third-party\n\
||kochava.com^$third-party\n\
||singular.net^$third-party\n\
||mc.yandex.ru^$third-party\n\
||hm.baidu.com^$third-party\n\
||cnzz.com^$third-party\n\
||veko-tracker-test.invalid^\n\
";

/// Tập quy tắc bóc tách tham số định danh tiếp thị và theo dõi liên kết (`$removeparam`).
pub const BUILTIN_REMOVEPARAM_RULES: &str = "\
*$removeparam=utm_source\n\
*$removeparam=utm_medium\n\
*$removeparam=utm_campaign\n\
*$removeparam=utm_term\n\
*$removeparam=utm_content\n\
*$removeparam=utm_id\n\
*$removeparam=utm_name\n\
*$removeparam=utm_reader\n\
*$removeparam=utm_viz_id\n\
*$removeparam=utm_pubreferrer\n\
*$removeparam=utm_swu\n\
*$removeparam=fbclid\n\
*$removeparam=gclid\n\
*$removeparam=dclid\n\
*$removeparam=gbraid\n\
*$removeparam=wbraid\n\
*$removeparam=msclkid\n\
*$removeparam=twclid\n\
*$removeparam=ttclid\n\
*$removeparam=li_fat_id\n\
*$removeparam=igshid\n\
*$removeparam=igs\n\
*$removeparam=mc_cid\n\
*$removeparam=mc_eid\n\
*$removeparam=_hsenc\n\
*$removeparam=_hsmi\n\
*$removeparam=__hssc\n\
*$removeparam=__hstc\n\
*$removeparam=__hsfp\n\
*$removeparam=hsCtaTracking\n\
*$removeparam=yclid\n\
*$removeparam=_openstat\n\
*$removeparam=vero_id\n\
*$removeparam=vero_conv\n\
*$removeparam=mkt_tok\n\
*$removeparam=wickedid\n\
*$removeparam=oly_anon_id\n\
*$removeparam=oly_enc_id\n\
*$removeparam=rb_clickid\n\
*$removeparam=s_kwcid\n\
*$removeparam=_ga\n\
*$removeparam=_gl\n\
*$removeparam=spm\n\
*$removeparam=scm\n\
";

/// Bộ chọn CSS ẩn khung quảng cáo rỗng (Cosmetic Filtering) an toàn, không ảnh hưởng bố cục nội dung chính.
pub const BUILTIN_GENERIC_COSMETIC_SELECTORS: &[&str] = &[
    "ins.adsbygoogle",
    ".adsbygoogle",
    "div[id^=\"div-gpt-ad\"]",
    "iframe[id^=\"google_ads_iframe\"]",
    "div[data-google-query-id]",
    ".taboola-widget",
    "div[id^=\"taboola-\"]",
    ".OUTBRAIN",
    "div[id^=\"mgid_\"]",
    ".admicro-slot",
    "div[id^=\"admzone\"]",
    ".eclick-ad",
    ".qc-sticky-bottom",
    ".banner-quang-cao",
];

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
    include_builtin_cosmetics: bool,
    custom_generic_cosmetics: Vec<String>,
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

fn extract_generic_cosmetic_selectors(rules: &str) -> Vec<String> {
    rules
        .lines()
        .map(str::trim)
        .filter_map(|line| {
            if let Some(sel) = line.strip_prefix("##") {
                let trimmed = sel.trim();
                if !trimmed.is_empty() && !trimmed.contains('{') && !trimmed.contains('}') {
                    return Some(trimmed.to_owned());
                }
            }
            None
        })
        .collect()
}

fn compile_pipelines(custom_rules: &str, include_builtin: bool) -> CompiledFilterPipelines {
    let mut ad_set = FilterSet::new(false);
    let mut tracker_set = FilterSet::new(false);
    let mut param_set = FilterSet::new(false);
    let mut rule_count = 0u64;

    if include_builtin {
        ad_set.add_filter_list(BUILTIN_AD_RULES.to_owned(), ParseOptions::default());
        tracker_set.add_filter_list(BUILTIN_TRACKER_RULES.to_owned(), ParseOptions::default());
        param_set.add_filter_list(BUILTIN_REMOVEPARAM_RULES.to_owned(), ParseOptions::default());
        rule_count += count_effective_rules(BUILTIN_AD_RULES)
            + count_effective_rules(BUILTIN_TRACKER_RULES)
            + count_effective_rules(BUILTIN_REMOVEPARAM_RULES)
            + (BUILTIN_GENERIC_COSMETIC_SELECTORS.len() as u64);
    }

    let custom_generic_cosmetics = extract_generic_cosmetic_selectors(custom_rules);

    if !custom_rules.trim().is_empty() {
        // Nạp cả quy tắc tùy chỉnh (bao gồm cả quy tắc ngoại lệ @@|| và cosmetic ##) vào bộ lọc chính
        ad_set.add_filter_list(custom_rules.to_owned(), ParseOptions::default());
        let exceptions_only: String = custom_rules
            .lines()
            .filter(|line| line.trim_start().starts_with("@@"))
            .collect::<Vec<_>>()
            .join("\n");
        if !exceptions_only.is_empty() {
            tracker_set.add_filter_list(exceptions_only.clone(), ParseOptions::default());
            param_set.add_filter_list(exceptions_only, ParseOptions::default());
        }
        rule_count += count_effective_rules(custom_rules);
    }

    CompiledFilterPipelines {
        ad_and_custom_engine: Engine::new_with_filter_set(ad_set),
        tracker_engine: Engine::new_with_filter_set(tracker_set),
        removeparam_engine: Engine::new_with_filter_set(param_set),
        include_builtin_cosmetics: include_builtin,
        custom_generic_cosmetics,
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

    /// Trả về bảng CSS ẩn phần tử quảng cáo cho URL trang hiện tại, tôn trọng ngoại lệ `$generichide` và `#@#`.
    pub fn cosmetic_css_for_url(&self, page_url: &str) -> String {
        let trimmed = page_url.trim();
        if !(trimmed.starts_with("http://") || trimmed.starts_with("https://")) {
            return String::new();
        }
        if Request::new(trimmed, trimmed, "document", "get").is_err() {
            return String::new();
        }

        let guard = match self.pipelines.read() {
            Ok(g) => g,
            Err(_) => return String::new(),
        };

        let resources = guard.ad_and_custom_engine.url_cosmetic_resources(trimmed);
        let mut selectors: BTreeSet<&str> = BTreeSet::new();

        for sel in &resources.hide_selectors {
            let s = sel.trim();
            if !s.is_empty() && !s.contains('{') && !s.contains('}') && !resources.exceptions.contains(s) {
                selectors.insert(s);
            }
        }

        if !resources.generichide {
            if guard.include_builtin_cosmetics {
                for &sel in BUILTIN_GENERIC_COSMETIC_SELECTORS {
                    if !resources.exceptions.contains(sel) {
                        selectors.insert(sel);
                    }
                }
            }
            for sel in &guard.custom_generic_cosmetics {
                if !resources.exceptions.contains(sel.as_str()) {
                    selectors.insert(sel.as_str());
                }
            }
        }

        if selectors.is_empty() {
            return String::new();
        }

        let joined: Vec<&str> = selectors.into_iter().collect();
        format!(
            "{} {{ display: none !important; visibility: hidden !important; }}",
            joined.join(", ")
        )
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
pub unsafe extern "C" fn veko_adblock_cosmetic_css_for_url(
    handle: *const VekoAdblockHandle,
    page_url: *const c_char,
    css_out: *mut c_char,
    css_capacity: usize,
) -> usize {
    if handle.is_null() || css_out.is_null() || css_capacity <= 1 {
        return 0;
    }
    let url = unsafe { cstr_to_str(page_url) };
    let css = unsafe { &*handle }.cosmetic_css_for_url(url);
    if css.is_empty() {
        unsafe {
            *css_out = 0;
        }
        return 0;
    }
    if let Ok(c_string) = CString::new(css) {
        let bytes = c_string.as_bytes_with_nul();
        if bytes.len() <= css_capacity {
            unsafe {
                ptr::copy_nonoverlapping(
                    bytes.as_ptr() as *const c_char,
                    css_out,
                    bytes.len(),
                );
            }
            return bytes.len() - 1;
        }
    }
    unsafe {
        *css_out = 0;
    }
    0
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
    fn blocks_international_and_regional_ads_session_replay_and_fingerprinting() {
        let engine = VekoAdblockHandle::new("", true);

        for ad_url in [
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://media1.admicro.vn/ads_box_492.js",
            "https://static.eclick.vn/delivery/zone.js",
            "https://cdn.popads.net/pop.js",
        ] {
            let (ad_action, _) = engine.evaluate(
                ad_url,
                "https://news.example.com/article",
                "script",
                "get",
                true,
                true,
                true,
                true,
            );
            assert_eq!(ad_action, VEKO_FILTER_BLOCK_AD, "Expected ad block for {ad_url}");
        }

        for tracker_url in [
            "https://www.google-analytics.com/g/collect?v=2",
            "https://static.hotjar.com/c/hotjar-12345.js",
            "https://openfpcdn.io/fingerprintjs/v4",
            "https://www.clarity.ms/tag/abc123",
            "https://analytics.tiktok.com/i18n/pixel/events.js",
        ] {
            let (tr_action, _) = engine.evaluate(
                tracker_url,
                "https://shop.example.com/cart",
                "script",
                "get",
                true,
                true,
                true,
                true,
            );
            assert_eq!(
                tr_action, VEKO_FILTER_BLOCK_TRACKER,
                "Expected tracker block for {tracker_url}"
            );
        }

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
        assert_eq!(stats.total_requests_checked, 12);
        assert_eq!(stats.ads_blocked, 4);
        assert_eq!(stats.trackers_blocked, 5);
        assert!(stats.active_rule_count >= 180);
    }

    #[test]
    fn strips_modern_social_and_analytics_tracking_parameters() {
        let engine = VekoAdblockHandle::new("", true);

        let (action, rewritten) = engine.evaluate(
            "https://blog.example.org/post?id=42&utm_source=newsletter&fbclid=IwAR123&ttclid=E_C_P&igshid=MzRl&_gl=1*abc&page=2",
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
    fn generates_cosmetic_css_and_honors_domain_exceptions_and_generichide() {
        let engine = VekoAdblockHandle::new(
            "news.example.com##.sponsored-inline-card\nsafe.example.com#@#.adsbygoogle\n@@||allow.example.com^$generichide",
            true,
        );

        let news_css = engine.cosmetic_css_for_url("https://news.example.com/world");
        assert!(news_css.contains(".sponsored-inline-card"));
        assert!(news_css.contains(".adsbygoogle"));
        assert!(news_css.contains("display: none !important"));

        let safe_css = engine.cosmetic_css_for_url("https://safe.example.com/index.html");
        assert!(!safe_css.contains(", .adsbygoogle,"));

        let generic_hidden_disabled = engine.cosmetic_css_for_url("https://allow.example.com/");
        assert!(!generic_hidden_disabled.contains(".adsbygoogle"));
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
