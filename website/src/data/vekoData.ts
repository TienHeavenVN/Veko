export const VEKO_SITE_ORIGIN = 'https://veko.kdns.fr';
export const VEKO_REPO_URL = 'https://github.com/TienHeavenVN/Veko';
export const VEKO_LICENSE_SPDX = 'MPL-2.0';
export const VEKO_LICENSE_URL = 'https://github.com/TienHeavenVN/Veko/blob/main/LICENSE';
export const VEKO_RELEASES_URL = 'https://github.com/TienHeavenVN/Veko/releases';
export const VEKO_LATEST_RELEASE_URL = 'https://github.com/TienHeavenVN/Veko/releases/tag/v0.4.0';
export const VEKO_LATEST_CHECKSUMS_URL =
  'https://github.com/TienHeavenVN/Veko/releases/download/v0.4.0/SHA256SUMS.txt';
export const VEKO_ACTIONS_URL = 'https://github.com/TienHeavenVN/Veko/actions/workflows/build-apk.yml';
export const VEKO_WEBSITE_ACTIONS_URL = 'https://github.com/TienHeavenVN/Veko/actions/workflows/website.yml';
export const VEKO_README_URL = 'https://github.com/TienHeavenVN/Veko/blob/main/README.md';
export const VEKO_ARCHITECTURE_DOC_URL =
  'https://github.com/TienHeavenVN/Veko/blob/main/docs/GECKOVIEW_ARCHITECTURE.md';

/**
 * Thông tin phiên bản và trạng thái phát hành đối chiếu trực tiếp từ app/build.gradle.kts & GitHub Releases:
 * - versionName trên nhánh kiến trúc thuần Mozilla GeckoView: "0.4.0-privacy-shield" (versionCode = 3, minSdk 26 / targetSdk 36 / compileSdk 37).
 * - Bản phát hành chính thức gần nhất đã gắn thẻ & ký khóa riêng trên GitHub Releases: v0.4.0 (ccf7a8b, 3 gói ABI + SHA256SUMS.txt).
 */
export const VEKO_APP_ID = 'io.veko.browser';
export const VEKO_SOURCE_VERSION = '0.4.0-privacy-shield';
export const VEKO_MIN_ANDROID = 'Android 8.0+ (API 26+)';
export const HAS_OFFICIAL_RELEASE = true;
export const OFFICIAL_RELEASE_VERSION: string | null = 'v0.4.0';

export interface OfficialReleaseAsset {
  readonly abi: string;
  readonly filename: string;
  readonly sizeLabel: string;
  readonly recommended?: boolean;
  readonly deviceHint: string;
  readonly downloadUrl: string;
  readonly sha256: string;
}

export const OFFICIAL_RELEASE_ASSETS: readonly OfficialReleaseAsset[] = [
  {
    abi: 'arm64-v8a (64-bit)',
    filename: 'app-arm64-v8a-release.apk',
    sizeLabel: '92.5 MB',
    recommended: true,
    deviceHint: 'Khuyên dùng cho hầu hết điện thoại Android hiện đại (ARM64)',
    downloadUrl:
      'https://github.com/TienHeavenVN/Veko/releases/download/v0.4.0/app-arm64-v8a-release.apk',
    sha256: 'b09e19a9af5ea48a3658cb007596634e8476f971d1b9eda286ee3edc84cd020e',
  },
  {
    abi: 'armeabi-v7a (32-bit)',
    filename: 'app-armeabi-v7a-release.apk',
    sizeLabel: '89.8 MB',
    deviceHint: 'Dành cho thiết bị Android 32-bit đời cũ',
    downloadUrl:
      'https://github.com/TienHeavenVN/Veko/releases/download/v0.4.0/app-armeabi-v7a-release.apk',
    sha256: '02bd6549b6dd8fda32d71e4f4e544c7dc0cd8bf5c4b907c22dd19a89020862c2',
  },
  {
    abi: 'x86_64',
    filename: 'app-x86_64-release.apk',
    sizeLabel: '97.3 MB',
    deviceHint: 'Dành cho Chromebook, PC Android hoặc máy ảo x86_64',
    downloadUrl:
      'https://github.com/TienHeavenVN/Veko/releases/download/v0.4.0/app-x86_64-release.apk',
    sha256: 'f2057f05153ea22f3fa02c8f3281525cf450ded203e0f40c1ad1e70588dac50e',
  },
];

export interface NavItem {
  readonly label: string;
  readonly anchor: string;
}

export const MAIN_NAV_ITEMS: readonly NavItem[] = [
  { label: 'Trải nghiệm', anchor: '#trai-nghiem' },
  { label: 'Tính năng', anchor: '#tinh-nang' },
  { label: 'Quyền riêng tư', anchor: '#quyen-rieng-tu' },
  { label: 'Công nghệ', anchor: '#cong-nghe' },
  { label: 'Giao diện', anchor: '#giao-dien' },
  { label: 'Hỏi đáp', anchor: '#cau-hoi-thuong-gap' },
];

export type FeatureVerificationStatus = 'Verified' | 'In Development' | 'Planned' | 'Unverified';

export interface FeatureAuditRow {
  readonly feature: string;
  readonly status: FeatureVerificationStatus;
  readonly summary: string;
  readonly sourceEvidence: string;
}

/**
 * Bảng kiểm toán đối chiếu trực tiếp các hạng mục kỹ thuật trong kho mã nguồn thuần Mozilla GeckoView:
 * Tuyệt đối không dùng android.webkit.WebView và không đưa bất kỳ tuyên bố bảo mật/chặn tuyệt đối nào chưa kiểm chứng.
 */
export const FEATURE_AUDIT_MATRIX: readonly FeatureAuditRow[] = [
  {
    feature: 'Mozilla GeckoView (Gecko WebRender + SpiderMonkey)',
    status: 'Verified',
    summary:
      'Nhân trình duyệt duy nhất của Veko Browser (150.0.20260511200624 từ kho Mozilla Maven chính thức), dùng chung một GeckoRuntime cho toàn ứng dụng và tách phiên GeckoSession cho từng tab.',
    sourceEvidence: 'app/.../engine/gecko/GeckoBrowserEngine.kt • app/build.gradle.kts',
  },
  {
    feature: 'Lá chắn Quảng cáo & Tracker bằng Rust (adblock-rust 0.13.4)',
    status: 'Verified',
    summary:
      'Crate Rust veko-adblock-ffi (MPL-2.0) đối chiếu danh sách lọc quảng cáo/tracker, tính toán CSS ẩn phần tử quảng cáo (cosmetic filtering) và gọt hơn 40 tham số theo dõi (utm_*, fbclid, gclid).',
    sourceEvidence: 'privacy/rust/veko-adblock/src/lib.rs • privacy/.../PrivacyShieldEngine.kt',
  },
  {
    feature: 'Cầu nối C++20 JNI (libveko_adblock_jni.so • 16 KB ELF)',
    status: 'Verified',
    summary:
      'Biên dịch trực tiếp bằng Android NDK Clang++ liên kết tĩnh thư viện Rust cho arm64-v8a, armeabi-v7a và x86_64 đạt chuẩn căn lề trang nhớ 16 KB của Android 15+.',
    sourceEvidence: 'privacy/src/main/cpp/veko_adblock_jni.cc • scripts/build-adblock-native.sh',
  },
  {
    feature: 'Tiện ích nội bộ GeckoView WebExtension (veko-shield)',
    status: 'Verified',
    summary:
      'Chặn yêu cầu tài nguyên con HTTP/HTTPS (script, XHR/fetch, beacon, sub_frame, ảnh/media quảng cáo) qua webRequest.onBeforeRequest (timeout an toàn 450 ms) và tiêm CSS ẩn khung quảng cáo.',
    sourceEvidence: 'app/src/main/assets/extensions/veko_shield/background.js • content.js',
  },
  {
    feature: 'Chế độ Duyệt Riêng tư & Xóa dữ liệu khi thoát',
    status: 'Verified',
    summary:
      'Mỗi tab riêng tư chạy GeckoSession(usePrivateMode = true) kèm dFPI cô lập cookie và cờ cửa sổ FLAG_SECURE; tùy chọn Clear-on-Exit xóa lịch sử, phiên tab và bộ nhớ đệm StorageController.',
    sourceEvidence: 'app/.../engine/gecko/GeckoSettings.kt • app/.../MainActivity.kt',
  },
  {
    feature: 'Phiên RAM-only (Dành riêng cho Tab Riêng tư)',
    status: 'Verified',
    summary:
      'Tab riêng tư và nháp nhập liệu chỉ giữ trong RAM, lọc bỏ khỏi bộ mã hóa lưu phiên DataStore và không ghi vào Room SQLite (tab thường vẫn lưu metadata để khôi phục).',
    sourceEvidence: 'browser-core/.../SessionPersistenceCodec.kt • TabManager.kt',
  },
  {
    feature: 'Phân quyền theo từng Origin & Chặn Autoplay âm thanh',
    status: 'Verified',
    summary:
      'Chuẩn hóa định danh scheme://host:port cho Camera, Micrô, Vị trí và kiểm soát quyền tự phát media có tiếng/không tiếng của GeckoSession.',
    sourceEvidence: 'security/.../SitePermissionManager.kt • app/.../EngineSessionBridge.kt',
  },
  {
    feature: 'Lịch sử, Dấu trang JSON & Bộ chọn tệp SAF',
    status: 'Verified',
    summary:
      'Lưu cục bộ trong Room SQLite v1, hỗ trợ nhập/xuất Dấu trang JSON và xử lý hộp thoại tải tệp lên trang web (<input type="file">) qua Android Storage Access Framework.',
    sourceEvidence: 'storage/.../VekoDatabase.kt • app/.../BrowserOverlays.kt',
  },
  {
    feature: 'Quản lý Tải xuống & Kiểm tra MIME',
    status: 'Verified',
    summary:
      'Ghi luồng tải xuống qua MediaStore.Downloads (không xin quyền bộ nhớ toàn cục), làm sạch tên tệp RFC 5987 và cảnh báo/đối chiếu phần mở rộng thực thi với MIME.',
    sourceEvidence: 'downloads/.../VekoDownloadManager.kt • DownloadSecurityPolicy.kt',
  },
  {
    feature: 'Đa ngôn ngữ Giao diện (8 Ngôn ngữ)',
    status: 'Verified',
    summary:
      'Hỗ trợ chuyển đổi trực tiếp tiếng Anh, tiếng Việt, Nhật, Hàn, Trung giản thể, Pháp, Đức và Tây Ban Nha qua AppCompatDelegate per-app locales.',
    sourceEvidence: 'app/.../ui/i18n/VekoStrings.kt • app/src/main/res/values-*/strings.xml',
  },
  {
    feature: 'Biên dịch nguồn GeckoView ngoại tuyến cho F-Droid',
    status: 'In Development',
    summary:
      'Hồ sơ metadata F-Droid và vendoring Rust ngoại tuyến đã sẵn sàng, nhưng trạng thái nộp fdroiddata vẫn khóa ở mức BLOCKED cho tới khi hoàn tất công thức biên dịch MozFennec từ nguồn.',
    sourceEvidence: 'metadata/fdroid/io.veko.browser.yml • metadata/fdroid/PUBLISHING.md',
  },
];

export interface ShowcaseTab {
  readonly id: 'address-bar' | 'tab-management' | 'private-mode' | 'privacy-shield' | 'site-settings';
  readonly badge: string;
  readonly shortLabel: string;
  readonly title: string;
  readonly summary: string;
  readonly modulePath: string;
  readonly bullets: readonly string[];
}

export const SHOWCASE_TABS: readonly ShowcaseTab[] = [
  {
    id: 'address-bar',
    badge: '01 • ĐIỀU HƯỚNG CẠNH DƯỚI',
    shortLabel: 'Thanh địa chỉ',
    title: 'Thanh địa chỉ đặt đúng tầm ngón cái, gõ trực tiếp không chuyển cảnh thừa',
    summary:
      'Trên cả Trang chủ và màn hình duyệt web, Veko ưu tiên thao tác một tay ở cạnh dưới màn hình. Bạn có thể gõ URL dài hoặc từ khóa ngay lập tức, đổi công cụ tìm kiếm tại chỗ và dùng cử chỉ vuốt để điều hướng.',
    modulePath: ':app (AddressSearchSection, BrowserBottomBar)',
    bullets: [
      'Tích hợp bộ chọn DuckDuckGo, Brave Search, Startpage và Google ngay dưới ô tìm kiếm.',
      'Tự động thu gọn thanh công cụ dưới đáy khi đang gõ trên Trang chủ để nhường trọn không gian cho bàn phím ảo.',
      'Vuốt ngang trên viên thuốc địa chỉ để chuyển tab trước/sau kèm phản hồi xúc giác (haptic).',
    ],
  },
  {
    id: 'tab-management',
    badge: '02 • QUẢN LÝ TAB & BỘ NHỚ',
    shortLabel: 'Quản lý Tab',
    title: 'Mở nhiều tab trên một GeckoRuntime duy nhất với trần cứng 6 phiên sống LRU',
    summary:
      'Thay vì khởi tạo lại nhân trình duyệt cho từng tab hay giữ hàng chục phiên chạy nền tốn RAM, TabManager dùng chung một GeckoRuntime duy nhất và chỉ duy trì tối đa 6 GeckoSession sống đồng thời.',
    modulePath: ':browser-core (TabManager • MAX_LIVE_SESSIONS = 6)',
    bullets: [
      'Vuốt lên từ thanh địa chỉ đáy để mở ngay lưới Tab Preview Sheet phân tách rõ Tab thường và Tab Riêng tư.',
      'Tự động gọi releaseSession(), setActive(false) và session.close() sạch sẽ khi thu hồi tab cũ để tránh rò rỉ bộ nhớ.',
      'Bảo toàn URL và trạng thái phiên GeckoSession để khôi phục lười tức thì khi bạn chạm mở lại tab.',
    ],
  },
  {
    id: 'private-mode',
    badge: '03 • CHẾ ĐỘ RIÊNG TƯ CÔ LẬP',
    shortLabel: 'Duyệt riêng tư',
    title: 'Phiên riêng tư giữ trên RAM, tách biệt khỏi SQLite, DataStore và hồ sơ tab thường',
    summary:
      'Mỗi tab riêng tư khởi tạo GeckoSession với usePrivateMode(true) kết hợp cô lập cookie dFPI; đồng thời bỏ qua hoàn toàn việc ghi lịch sử xuống Room SQLite hay lưu phiên xuống DataStore.',
    modulePath: ':browser-core (TabManager) + :app (GeckoSettings)',
    bullets: [
      'Gợi ý trên thanh địa chỉ tự động loại bỏ lịch sử duyệt web khi đang ở chế độ Riêng tư.',
      'Nháp nhập liệu và trạng thái tab riêng tư chỉ giữ trên RAM tạm thời, tự xóa sạch khi đóng tab.',
      'Tự động bật cờ cửa sổ FLAG_SECURE để ngăn chụp ảnh màn hình trong khay đa nhiệm hệ thống.',
    ],
  },
  {
    id: 'privacy-shield',
    badge: '04 • LÁ CHẮN 3 LỚP RUST + GECKO',
    shortLabel: 'Lá chắn Tracker',
    title: 'Kết hợp Rust adblock-rust (JNI), GeckoView WebExtension và Strict ETP',
    summary:
      'Veko kết nối thư viện brave/adblock-rust qua cầu nối C++20 JNI (libveko_adblock_jni.so) cùng tiện ích nội bộ veko-shield và cơ chế Enhanced Tracking Protection nghiêm ngặt của GeckoView.',
    modulePath: ':privacy (libveko_adblock_jni.so) + :app (veko_shield)',
    bullets: [
      'Cắt bỏ tự động hơn 40 tham số theo dõi phổ biến như utm_source, utm_medium, fbclid, gclid, ttclid, igshid.',
      'Chặn yêu cầu tài nguyên con HTTP/HTTPS (script, XHR/fetch, beacon, sub_frame) qua WebExtension nội bộ kết hợp Strict ETP và dFPI.',
      'Hiển thị bộ đếm minh bạch số quảng cáo, tracker, tác nhân fingerprinting đã chặn và cho phép bật/tắt lá chắn riêng cho từng trang.',
    ],
  },
  {
    id: 'site-settings',
    badge: '05 • PHÂN QUYỀN & CÀI ĐẶT',
    shortLabel: 'Cài đặt & Quyền',
    title: 'Kiểm soát quyền phần cứng theo từng Origin và thông số GeckoView minh bạch',
    summary:
      'Quyền truy cập Máy ảnh, Micrô và Vị trí được chuẩn hóa chặt chẽ theo từng định danh scheme://host:port thay vì cấp tràn lan, kèm tùy chọn chặn tự phát âm thanh và xóa dữ liệu khi thoát.',
    modulePath: ':security (SitePermissionManager) + :storage',
    bullets: [
      'Quyền cấp tạm trong tab Riêng tư chỉ nằm trên RAM và tự động hết hiệu lực khi đóng phiên.',
      'Kiểm tra nghiêm ngặt liên kết mở ứng dụng bên ngoài (intent://, mailto:, tel:) qua hộp thoại xác nhận.',
      'Hiển thị minh bạch phiên bản Mozilla GeckoView, engine kết xuất Gecko WebRender / SpiderMonkey và tương thích ABI.',
    ],
  },
];

export interface PrivacyExplanation {
  readonly codeTag: string;
  readonly title: string;
  readonly summary: string;
  readonly technicalDetail: string;
}

export const PRIVACY_PILLARS: readonly PrivacyExplanation[] = [
  {
    codeTag: 'LOCAL STORAGE • ROOM v1',
    title: 'Dữ liệu cá nhân chỉ lưu cục bộ trên máy',
    summary:
      'Dấu trang và lịch sử tab thường được lưu trong cơ sở dữ liệu SQLite nội bộ (veko_browser.db); tùy chọn giao diện lưu trong DataStore (veko_user_settings.preferences_pb). Không yêu cầu tạo tài khoản đám mây.',
    technicalDetail:
      'backup_rules.xml (API 26–30) và data_extraction_rules.xml (API 31+) khóa allowlist chỉ cho phép sao lưu tệp cài đặt giao diện, loại trừ lịch sử duyệt web, phiên tab và cookie.',
  },
  {
    codeTag: 'RAM ISOLATION • PRIVATE MODE',
    title: 'Tab Riêng tư không ghi lịch sử xuống bộ nhớ lưu trữ',
    summary:
      'Khi bật chế độ Riêng tư trên GeckoView, Veko kích hoạt usePrivateMode(true) hoàn toàn tách biệt với hồ sơ thường và lọc bỏ toàn bộ tab riêng tư khỏi bộ lưu phiên DataStore cũng như Room SQLite.',
    technicalDetail:
      'Được kiểm chứng tự động trên máy ảo Android 14 (API 34) bằng bài test quét hệ thống tệp xác nhận chuỗi canary riêng tư không được ghi vào cơ sở dữ liệu hay tệp phiên trên đĩa.',
  },
  {
    codeTag: 'NAVIGATION GUARD • STRICT TLS',
    title: 'Kiểm soát điều hướng & Không bỏ qua lỗi chứng chỉ',
    summary:
      'UrlNavigationPolicy chặn điều hướng trái phép sang javascript:/file:/intent:, nâng cấp HTTPS khi bật chế độ bảo vệ và không bao giờ tự động bỏ qua chứng chỉ TLS lỗi.',
    technicalDetail:
      'Trang báo lỗi nội bộ được escape đầy đủ ký tự đặc biệt/bidi và gắn chính sách Content-Security-Policy (CSP) khóa thực thi script.',
  },
  {
    codeTag: 'NO TELEMETRY • COIL RAM',
    title: 'Không nhúng SDK phân tích hay quảng cáo',
    summary:
      'Cả ứng dụng Android lẫn website giới thiệu này đều không chứa SDK thu thập hành vi, không gọi về máy chủ phân tích riêng và không gắn mã theo dõi tiếp thị.',
    technicalDetail:
      'Bộ giải mã ảnh giao diện Coil 3 được cấu hình cứng CachePolicy.DISABLED cho cả mạng và đĩa để tránh lưu dấu vết favicon ngoài ý muốn.',
  },
];

export interface PrivacyMatrixRow {
  readonly aspect: string;
  readonly regularTab: string;
  readonly privateTab: string;
  readonly networkNote: string;
}

export const PRIVACY_MATRIX_ROWS: readonly PrivacyMatrixRow[] = [
  {
    aspect: 'Lịch sử truy cập & Dấu trang',
    regularTab: 'Lưu cục bộ trong Room SQLite trên thiết bị',
    privateTab: 'Không ghi lịch sử xuống SQLite; tự hủy khi đóng tab',
    networkNote: 'Không đồng bộ lên đám mây của Veko',
  },
  {
    aspect: 'Khôi phục phiên Tab & Nháp nhập liệu',
    regularTab: 'Lưu metadata tab thường trong DataStore cục bộ',
    privateTab: 'Chỉ giữ trên RAM, loại trừ khỏi DataStore và xóa ngay khi đóng phiên',
    networkNote: 'Truy vấn chỉ gửi tới công cụ tìm kiếm bạn chọn khi nhấn Đi',
  },
  {
    aspect: 'Quyền Camera / Micrô / Vị trí',
    regularTab: 'Lưu theo từng Origin (scheme://host:port)',
    privateTab: 'Chỉ cấp tạm thời trên RAM trong phiên hiện tại',
    networkNote: 'Có thể thu hồi từng trang bất kỳ lúc nào trong Cài đặt',
  },
  {
    aspect: 'Sao lưu hệ thống Android (Backup)',
    regularTab: 'Loại trừ lịch sử, tab và cookie (chỉ giữ cài đặt UI)',
    privateTab: 'Không ghi tệp phiên riêng tư xuống đĩa',
    networkNote: 'Kiểm soát cứng qua data_extraction_rules.xml',
  },
  {
    aspect: 'Địa chỉ IP & Hiển thị ở cấp độ mạng',
    regularTab: 'Trang web đích & ISP vẫn thấy kết nối mạng chuẩn',
    privateTab: 'Không ẩn IP trước ISP, DNS hoặc trang web bạn mở',
    networkNote: 'Cần kết hợp VPN hoặc Tor nếu muốn ẩn định tuyến mạng',
  },
];

export const PRIVACY_LIMITATIONS: readonly string[] = [
  'Chế độ Riêng tư và lá chắn tracker giúp giảm dấu vết lưu trên điện thoại và chặn nhiều tên miền theo dõi phổ biến, nhưng không ẩn danh hóa kết nối mạng của bạn; nhà mạng (ISP), quản trị Wi-Fi, máy chủ DNS hoặc trang web đích vẫn nhận biết địa chỉ IP nếu không dùng VPN/Tor.',
  'Lá chắn tài nguyên con tích hợp qua tiện ích nội bộ GeckoView WebExtension (webRequest.onBeforeRequest) áp dụng cho các yêu cầu HTTP/HTTPS sau khi phiên tiện ích khởi tạo (với cơ chế fail-open 450 ms để không làm treo trang); các yêu cầu rất sớm lúc khởi động lạnh hoặc giao thức nội bộ ngoài HTTP(S) được bảo vệ bởi cơ chế Strict Enhanced Tracking Protection và CSP gốc của GeckoView thay vì chặn tuyệt đối mọi gói tin cấp thấp.',
  'Khi bạn gửi truy vấn tới DuckDuckGo, Brave Search, Startpage hoặc Google, dữ liệu tìm kiếm tuân theo chính sách quyền riêng tư của nhà cung cấp công cụ tìm kiếm đó.',
];

export interface TechLayerItem {
  readonly name: string;
  readonly versionOrScope: string;
  readonly role: string;
  readonly status: 'active' | 'roadmap';
}

export const TECH_STACK_ACTIVE: readonly TechLayerItem[] = [
  {
    name: 'Mozilla GeckoView Runtime (Nhân trình duyệt duy nhất)',
    versionOrScope: '150.0.20260511200624 • Verified',
    role: 'Nhân kết xuất Gecko WebRender + SpiderMonkey từ kho chính thức Mozilla Maven xử lý toàn bộ trang web trên Android với Strict Enhanced Tracking Protection, cô lập cookie dFPI và WebExtension nội bộ.',
    status: 'active',
  },
  {
    name: 'Kiến trúc MVI Jetpack Compose & Quản lý Phiên GeckoSession',
    versionOrScope: 'AGP 9.1.1 • Kotlin 2.3.20 • JDK 17',
    role: 'Dùng chung một GeckoRuntime duy nhất cho toàn bộ ứng dụng, quản lý vòng đời đóng/mở GeckoSession chặt chẽ theo trần 6 phiên sống LRU để không rò rỉ bộ nhớ khi đóng tab hoặc hủy Activity.',
    status: 'active',
  },
  {
    name: 'Rust brave/adblock-rust + C++20 JNI (16 KB ELF)',
    versionOrScope: 'v0.13.4 • Cargo + NDK Clang++',
    role: 'Thư viện lọc quảng cáo/tracker biên dịch từ nguồn Rust (MPL-2.0) liên kết tĩnh trực tiếp vào libveko_adblock_jni.so cho arm64-v8a, armeabi-v7a và x86_64 đạt chuẩn trang nhớ 16 KB.',
    status: 'active',
  },
  {
    name: 'Room SQLite v1 + Preferences DataStore',
    versionOrScope: ':storage • Scoped Allowlist',
    role: 'Lưu trữ cục bộ cho dấu trang (hỗ trợ nhập/xuất JSON), lịch sử tab thường, cài đặt lá chắn và quyền theo Origin với chính sách loại trừ sao lưu dữ liệu nhạy cảm.',
    status: 'active',
  },
  {
    name: 'Kiểm thử Tự động Đa tầng trên GitHub Actions CI',
    versionOrScope: 'Unit Tests + Android 14 Emulator',
    role: 'Mỗi bản dựng đều kiểm tra Cargo offline SHA-256, C++20 JNI test, toàn bộ bộ JUnit 7 module, Android Lint, căn lề ELF 16 KB và kịch bản thực tế trên máy ảo Android 14 (API 34 x86_64 KVM).',
    status: 'active',
  },
];

export const TECH_STACK_ROADMAP: readonly TechLayerItem[] = [
  {
    name: 'Công thức biên dịch nguồn GeckoView (MozFennec) cho F-Droid',
    versionOrScope: 'metadata/fdroid • BLOCKED (Source Build Pending)',
    role: 'Đã hoàn tất vendoring Rust ngoại tuyến và hồ sơ io.veko.browser.yml, nhưng gói AAR GeckoView hiện vẫn lấy từ maven.mozilla.org; cần xác minh quy trình biên dịch nguồn MozFennec hoàn toàn ngoại tuyến trước khi mở MR tới fdroiddata.',
    status: 'roadmap',
  },
  {
    name: 'Đồng bộ Danh sách lọc Tùy chọn Tôn trọng Quyền riêng tư',
    versionOrScope: 'FilterListManager • Planned',
    role: 'Nghiên cứu cơ chế cho phép người dùng tự nạp tệp quy tắc lọc cục bộ (ABP/uBO syntax) do chính người dùng chọn mà không tự động kết nối ngầm khi khởi động.',
    status: 'roadmap',
  },
];

export interface GradleModuleInfo {
  readonly path: string;
  readonly responsibility: string;
  readonly state: 'Hoạt động' | 'Đang phát triển';
}

export const GRADLE_MODULES: readonly GradleModuleInfo[] = [
  {
    path: ':app',
    responsibility: 'Giao diện Jetpack Compose (8 ngôn ngữ), GeckoBrowserEngine, EngineSessionBridge & tiện ích nội bộ veko_shield',
    state: 'Hoạt động',
  },
  {
    path: ':browser-core',
    responsibility: 'Hợp đồng BrowserEngine, TabManager (LRU 6 phiên sống), UrlNavigationPolicy & MVI Store',
    state: 'Hoạt động',
  },
  {
    path: ':privacy',
    responsibility: 'Crate Rust veko-adblock-ffi, cầu nối C++20 libveko_adblock_jni.so, PrivacyShieldEngine & làm sạch URL',
    state: 'Hoạt động',
  },
  {
    path: ':security',
    responsibility: 'SitePermissionManager theo Origin (scheme://host:port), TlsCertificatePolicy & kiểm duyệt Intent ngoài',
    state: 'Hoạt động',
  },
  {
    path: ':storage',
    responsibility: 'Room Database v1 (lịch sử, dấu trang JSON), SettingsRepository & mô hình thông số GeckoView',
    state: 'Hoạt động',
  },
  {
    path: ':downloads',
    responsibility: 'VekoDownloadManager qua MediaStore Scoped Storage & kiểm tra MIME / tên tệp an toàn',
    state: 'Hoạt động',
  },
  {
    path: ':testing',
    responsibility: 'Bộ kiểm thử tích hợp MVI, cầu nối JNI adblock-rust, quyền riêng tư và xác thực cấu hình CI',
    state: 'Hoạt động',
  },
];

export interface ScreenshotItem {
  readonly src: string;
  readonly tag: string;
  readonly kindBadge: string;
  readonly alt: string;
  readonly captionTitle: string;
  readonly captionBody: string;
}

export const SCREENSHOT_ITEMS: readonly ScreenshotItem[] = [
  {
    src: 'screenshots/veko-home-refactored.svg',
    tag: 'MÀN HÌNH CHÍNH',
    kindBadge: 'UI Mockup • SVG',
    alt: 'Bản dựng minh họa SVG giao diện Trang chủ Veko Browser với header tinh gọn, ô tìm kiếm trung tâm, 4 nút tác vụ nhanh và thanh điều hướng cạnh dưới',
    captionTitle: 'Trang chủ tinh gọn & thao tác một tay',
    captionBody:
      'Bản dựng vector mô phỏng bố cục Jetpack Compose thực tế: đặt ô nhập địa chỉ, bộ chọn tìm kiếm, 4 nút tác vụ nhanh và thanh điều hướng đáy vào đúng tầm ngón cái.',
  },
  {
    src: 'screenshots/veko-tab-switcher.svg',
    tag: 'QUẢN LÝ TAB LRU',
    kindBadge: 'UI Mockup • SVG',
    alt: 'Bản dựng minh họa SVG khay lưới quản lý Tab của Veko Browser hiển thị thẻ xem trước tab, bộ chuyển Tab thường và Riêng tư cùng trần 6 phiên sống LRU',
    captionTitle: 'Khay lưới Tab & Trần 6 phiên sống',
    captionBody:
      'Mô phỏng khay mở nhanh bằng cử chỉ vuốt lên từ thanh địa chỉ đáy, phân tách rõ Tab thường và Riêng tư kèm cơ chế đóng băng khôi phục lười tiết kiệm RAM.',
  },
  {
    src: 'screenshots/veko-private-mode.svg',
    tag: 'CHẾ ĐỘ RIÊNG TƯ',
    kindBadge: 'UI Mockup • SVG',
    alt: 'Bản dựng minh họa SVG giao diện Chế độ Riêng tư của Veko Browser giải thích cơ chế giữ phiên trên RAM và những giới hạn mạng thực tế',
    captionTitle: 'Không gian Riêng tư trung thực',
    captionBody:
      'Ẩn lối tắt và lịch sử tab thường, đồng thời nêu rõ phạm vi dữ liệu giữ trên RAM thiết bị so với những thông tin kết nối mà nhà mạng hoặc trang đích vẫn thấy.',
  },
  {
    src: 'screenshots/veko-privacy-matrix.svg',
    tag: 'LÁ CHẮN & COOKIE',
    kindBadge: 'UI Mockup • SVG',
    alt: 'Bản dựng minh họa SVG khay Ma trận Quyền riêng tư Veko hiển thị bộ đếm chặn quảng cáo, tracker, làm sạch URL và 3 mức kiểm soát Cookie',
    captionTitle: 'Ma trận Lá chắn & Kiểm soát Cookie',
    captionBody:
      'Trình bày trực quan số lượng tracker đã chặn, tham số bám đuôi đã cắt bỏ bởi nhân Rust và bộ chuyển đổi nhanh giữa 3 chế độ cô lập Cookie.',
  },
];

export interface ApkVariantSpec {
  readonly name: string;
  readonly packageId: string;
  readonly signingAndOptimization: string;
  readonly availability: string;
}

export const APK_VARIANTS: readonly ApkVariantSpec[] = [
  {
    name: 'Veko Debug',
    packageId: 'io.veko.browser.debug',
    signingAndOptimization: 'Khóa ký phát triển cục bộ, giữ biểu tượng gỡ lỗi phục vụ kiểm thử đơn vị & máy ảo',
    availability: 'Tự biên dịch từ mã nguồn hoặc tải gói kiểm thử từ GitHub Actions CI',
  },
  {
    name: 'Veko Preview (Thử nghiệm R8)',
    packageId: 'io.veko.browser.preview',
    signingAndOptimization: 'Bật R8 minification + shrinkResources, tách 3 kiến trúc ABI (~90–98 MB/gói)',
    availability: 'Xuất tự động trong CI Artifacts (veko-preview-arm64-v8a / armeabi-v7a / x86_64)',
  },
  {
    name: 'Veko Release (Chính thức)',
    packageId: 'io.veko.browser',
    signingAndOptimization: 'Bắt buộc khóa ký phát hành riêng qua verifyReleaseSigningConfig + xác minh apksigner & SHA-256',
    availability: 'Đã phát hành v0.4.0 trên GitHub Releases (3 gói ABI đã ký số kèm SHA256SUMS.txt)',
  },
];

export interface FaqItem {
  readonly question: string;
  readonly answer: string;
}

export const FAQ_ITEMS: readonly FaqItem[] = [
  {
    question: 'Veko Browser là gì và hướng tới người dùng nào?',
    answer:
      'Veko Browser là trình duyệt web mã nguồn mở (MPL-2.0) dành cho Android 8.0 trở lên, tập trung vào 3 giá trị cốt lõi: bố cục điều hướng một tay ở cạnh dưới không nhồi nhét bảng tin rác, lưu trữ dữ liệu cá nhân cục bộ trên thiết bị, và kiểm chứng tự động các cơ chế bảo vệ quyền riêng tư.',
  },
  {
    question: 'Veko Browser sử dụng nhân trình duyệt (browser engine) nào?',
    answer:
      'Veko sử dụng duy nhất nhân trình duyệt độc lập Mozilla GeckoView 150.0 (kết xuất Gecko WebRender + JavaScript SpiderMonkey) từ kho chính thức Mozilla Maven — hoàn toàn không dùng android.webkit.WebView hệ thống và không đóng gói thêm bất kỳ nhân trình duyệt thứ hai nào.',
  },
  {
    question: 'Veko Browser được cấp phép theo giấy phép mã nguồn mở nào?',
    answer:
      'Kho mã nguồn Veko Browser được phát hành theo giấy phép Mozilla Public License Version 2.0 (MPL-2.0, xem tệp LICENSE tại thư mục gốc của dự án trên GitHub), đồng bộ hoàn toàn với giấy phép MPL-2.0 của Mozilla GeckoView và crate lọc nội dung brave/adblock-rust.',
  },
  {
    question: 'Veko xử lý dữ liệu giữa Tab thường và Tab Riêng tư như thế nào?',
    answer:
      'Veko không yêu cầu tài khoản và không nhúng SDK phân tích hành vi. Ở tab thường, dấu trang, lịch sử và metadata khôi phục tab được lưu cục bộ trên điện thoại (Room SQLite + DataStore) và bị loại trừ khỏi sao lưu đám mây Android Backup; người dùng cũng có thể bật tùy chọn Tự xóa dữ liệu khi thoát. Ở tab Riêng tư, phiên duyệt và nháp nhập liệu chỉ giữ trong bộ nhớ RAM và tự xóa sạch khi đóng các tab riêng tư.',
  },
  {
    question: 'Làm thế nào để cài đặt Veko Browser an toàn và trạng thái F-Droid hiện ra sao?',
    answer:
      'Bạn có thể tải gói APK chính thức v0.4.0 đã ký số (khuyên dùng bản arm64-v8a cho điện thoại Android 64-bit) trực tiếp từ GitHub Releases và đối chiếu mã băm trong tệp SHA256SUMS.txt. Hồ sơ metadata chuẩn bị cho F-Droid đã được thiết lập trong thư mục metadata/fdroid/, tuy nhiên trạng thái phát hành lên F-Droid hiện vẫn ở mức BLOCKED cho tới khi xác minh xong công thức biên dịch nguồn Mozilla GeckoView (MozFennec) ngoại tuyến theo tiêu chuẩn của F-Droid.',
  },
  {
    question: 'Chế độ Riêng tư của Veko có giúp ẩn địa chỉ IP trước nhà mạng không?',
    answer:
      'Không. Chế độ Riêng tư và lá chắn tracker giúp không lưu lịch sử duyệt web trên thiết bị này và chặn nhiều tên miền theo dõi phổ biến, nhưng không thay thế mạng riêng ảo (VPN) hay mạng Tor. Nhà cung cấp dịch vụ Internet (ISP), quản trị mạng nội bộ hoặc trang web đích vẫn nhận biết kết nối mạng của thiết bị.',
  },
];
