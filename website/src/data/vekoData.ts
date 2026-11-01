export const VEKO_SITE_ORIGIN = 'https://tiendayy.kdns.fr';
export const VEKO_REPO_URL = 'https://github.com/TienHeavenVN/Veko';
export const VEKO_LICENSE_SPDX = 'MPL-2.0';
export const VEKO_LICENSE_URL = 'https://github.com/TienHeavenVN/Veko/blob/main/LICENSE';
export const VEKO_RELEASES_URL = 'https://github.com/TienHeavenVN/Veko/releases';
export const VEKO_LATEST_RELEASE_URL = 'https://github.com/TienHeavenVN/Veko/releases/tag/v0.2.0';
export const VEKO_LATEST_CHECKSUMS_URL =
  'https://github.com/TienHeavenVN/Veko/releases/download/v0.2.0/SHA256SUMS.txt';
export const VEKO_ACTIONS_URL = 'https://github.com/TienHeavenVN/Veko/actions/workflows/build-apk.yml';
export const VEKO_WEBSITE_ACTIONS_URL = 'https://github.com/TienHeavenVN/Veko/actions/workflows/website.yml';
export const VEKO_README_URL = 'https://github.com/TienHeavenVN/Veko/blob/main/README.md';
export const VEKO_AUDIT_DOC_URL = 'https://github.com/TienHeavenVN/Veko/blob/main/docs/DUAL_ENGINE_ARCHITECTURE.md';
export const VEKO_MIGRATION_AUDIT_URL =
  'https://github.com/TienHeavenVN/Veko/blob/main/docs/CHROMIUM_MIGRATION_AUDIT.md';

/**
 * Thông tin phiên bản và trạng thái phát hành đối chiếu trực tiếp từ app/build.gradle.kts & GitHub Releases:
 * - versionName trên nhánh kiến trúc Dual-Engine hiện tại: "0.3.0-dual-engine" (versionCode = 2, minSdk 26 / targetSdk 36 / compileSdk 37).
 * - Bản phát hành chính thức gần nhất đã gắn thẻ & ký khóa riêng trên GitHub Releases: v0.2.0 (a3ac737, 3 gói ABI + SHA256SUMS.txt).
 */
export const VEKO_APP_ID = 'io.veko.browser';
export const VEKO_SOURCE_VERSION = '0.3.0-dual-engine';
export const VEKO_MIN_ANDROID = 'Android 8.0+ (API 26+)';
export const HAS_OFFICIAL_RELEASE = true;
export const OFFICIAL_RELEASE_VERSION: string | null = 'v0.2.0';

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
    sizeLabel: '88.1 MB',
    recommended: true,
    deviceHint: 'Khuyên dùng cho hầu hết điện thoại Android hiện đại (ARM64)',
    downloadUrl:
      'https://github.com/TienHeavenVN/Veko/releases/download/v0.2.0/app-arm64-v8a-release.apk',
    sha256: 'da053ffbb1a6edee7f9bf4f532afb11b3b79575c3e1b358614ba862bf1c17d67',
  },
  {
    abi: 'armeabi-v7a (32-bit)',
    filename: 'app-armeabi-v7a-release.apk',
    sizeLabel: '85.6 MB',
    deviceHint: 'Dành cho thiết bị Android 32-bit đời cũ',
    downloadUrl:
      'https://github.com/TienHeavenVN/Veko/releases/download/v0.2.0/app-armeabi-v7a-release.apk',
    sha256: 'f099291fe4d5114abf58a51527f2b3f56ab52717c27229616cd55fe75d896325',
  },
  {
    abi: 'x86_64',
    filename: 'app-x86_64-release.apk',
    sizeLabel: '92.7 MB',
    deviceHint: 'Dành cho Chromebook, PC Android hoặc máy ảo x86_64',
    downloadUrl:
      'https://github.com/TienHeavenVN/Veko/releases/download/v0.2.0/app-x86_64-release.apk',
    sha256: '042b51ac2c7bcf7829572f7167bce375e202f8a0863ce253464e875d7372f974',
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
 * Bảng kiểm toán đối chiếu trực tiếp 11 hạng mục kỹ thuật trong kho mã nguồn thực tế:
 * Không quảng cáo GeckoView và Chromium/Blink/V8 như đang hoạt động song song khi gói SDK Chromium chưa được nạp.
 */
export const FEATURE_AUDIT_MATRIX: readonly FeatureAuditRow[] = [
  {
    feature: 'Mozilla GeckoView (Gecko + SpiderMonkey)',
    status: 'Verified',
    summary:
      'Nhân hiển thị mặc định và duy nhất đang chạy trang web thực tế trong bản dựng tiêu chuẩn, tải từ kho Mozilla Maven chính thức (150.0.20260511200624).',
    sourceEvidence: 'app/.../engine/gecko/GeckoBrowserEngine.kt • app/build.gradle.kts',
  },
  {
    feature: 'Chromium Content Embedder (156.0.8078.85)',
    status: 'In Development',
    summary:
      'Đã hoàn thiện kiến trúc Dual-Engine, cầu nối C++20/JNI, SurfaceView ANativeWindow và bộ kiểm tra SDK; hiện khóa ở trạng thái BLOCKED (MISSING_UPSTREAM_SDK) cho tới khi biên dịch gói nhị phân Chromium ngoài CI.',
    sourceEvidence: 'app/.../engine/chromium/ChromiumAvailabilityChecker.kt • chromium/CHROMIUM_VERSION.lock',
  },
  {
    feature: 'Blink & V8 (Nhân phụ thuộc Chromium)',
    status: 'In Development',
    summary:
      'Thuộc nhánh nhân phụ Chromium Content 156.0.8078.85; chưa liên kết libveko_content_embedder.so và tài nguyên .pak/V8 snapshot vào APK tiêu chuẩn.',
    sourceEvidence: 'chromium-native/.../VekoChromiumNative.kt (isUpstreamContentLinked = false)',
  },
  {
    feature: 'Chế độ Duyệt Riêng tư (Private Browsing)',
    status: 'Verified',
    summary:
      'Mỗi nhân quản lý phiên riêng tư độc lập: GeckoView bật usePrivateMode(true) kèm dFPI cô lập cookie; cửa sổ bật FLAG_SECURE chống chụp màn hình khi mở tab riêng tư.',
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
    feature: 'Phân quyền theo từng Origin (Camera, Micrô, Vị trí)',
    status: 'Verified',
    summary:
      'Chuẩn hóa định danh scheme://host:port, bắt buộc xác nhận người dùng trước khi cấp quyền hệ điều hành; quyền trong tab riêng tư chỉ tồn tại tạm trên RAM.',
    sourceEvidence: 'security/.../SitePermissionManager.kt • app/.../BrowserOverlays.kt',
  },
  {
    feature: 'Lịch sử duyệt web & Dấu trang cục bộ',
    status: 'Verified',
    summary:
      'Lưu cục bộ trong Room SQLite v1 (veko_browser.db), hỗ trợ tìm kiếm/xóa từng mục và khóa không cho đồng bộ sang Android Auto Backup.',
    sourceEvidence: 'storage/.../VekoDatabase.kt • app/src/main/res/xml/data_extraction_rules.xml',
  },
  {
    feature: 'Quản lý Tải xuống & Kiểm tra MIME',
    status: 'Verified',
    summary:
      'Ghi luồng tải xuống qua MediaStore.Downloads (không xin quyền bộ nhớ toàn cục), làm sạch tên tệp RFC 5987 và cảnh báo/đối chiếu phần mở rộng thực thi với MIME.',
    sourceEvidence: 'downloads/.../VekoDownloadManager.kt • DownloadSecurityPolicy.kt',
  },
  {
    feature: 'Lá chắn Quảng cáo & Tracker bằng Rust (adblock-rust)',
    status: 'Verified',
    summary:
      'Crate Rust veko-adblock-ffi (brave/adblock-rust 0.13.4, MPL-2.0) lọc yêu cầu điều hướng và tự động cắt tham số theo dõi (utm_*, fbclid, gclid) qua JNI.',
    sourceEvidence: 'privacy/rust/veko-adblock/Cargo.toml • privacy/.../PrivacyShieldEngine.kt',
  },
  {
    feature: 'Cầu nối C++20 JNI & Thư viện Native 16 KB ELF',
    status: 'Verified',
    summary:
      'Biên dịch từ nguồn qua GN + Ninja + NDK Clang thành libveko_chromium_native.so cho arm64-v8a, armeabi-v7a, x86_64 đạt chuẩn căn lề trang 16 KB Android 15+.',
    sourceEvidence: 'BUILD.gn • scripts/build-native-gn.sh • scripts/verify-elf-packaging.py',
  },
  {
    feature: 'Chính sách Không Telemetry & Không SDK Theo dõi',
    status: 'Verified',
    summary:
      'Không tích hợp Firebase, Google Analytics, Crashlytics hay SDK quảng cáo trong cả ứng dụng Android lẫn website tĩnh; bật tín hiệu Global Privacy Control (GPC).',
    sourceEvidence: 'app/build.gradle.kts • website/scripts/lint-source.mjs',
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
    title: 'Mở nhiều tab mà không phình bộ nhớ nhờ trần cứng 6 phiên sống LRU',
    summary:
      'Thay vì giữ hàng chục phiên trình duyệt chạy nền gây tốn RAM, TabManager chỉ duy trì tối đa 6 phiên sống đồng thời trên bộ nhớ và bảo toàn URL cùng trạng thái điều hướng để khôi phục lười khi bạn chạm lại.',
    modulePath: ':browser-core (TabManager • MAX_LIVE_SESSIONS = 6)',
    bullets: [
      'Vuốt lên từ thanh địa chỉ đáy để mở ngay lưới Tab Preview Sheet kèm nhãn nhân hiển thị trên từng thẻ tab.',
      'Tự động gọi releaseSession() và setActive(false) khi chuyển tab hoặc đưa ứng dụng xuống nền.',
      'Tách riêng danh sách Tab thường và Tab riêng tư cùng bộ chọn nhân cho tab tạo mới.',
    ],
  },
  {
    id: 'private-mode',
    badge: '03 • CHẾ ĐỘ RIÊNG TƯ CÔ LẬP',
    shortLabel: 'Duyệt riêng tư',
    title: 'Phiên riêng tư giữ trên RAM, tách biệt khỏi SQLite, DataStore và hồ sơ tab thường',
    summary:
      'Trên nhân mặc định GeckoView, mỗi tab riêng tư khởi tạo phiên usePrivateMode(true) kết hợp cô lập cookie dFPI; đồng thời không chạm tới ngữ cảnh của nhân khác và bỏ qua việc ghi lịch sử xuống Room SQLite hay DataStore.',
    modulePath: ':browser-core (TabManager) + :app (GeckoSettings)',
    bullets: [
      'Gợi ý trên thanh địa chỉ tự động loại bỏ lịch sử duyệt web khi đang ở chế độ Riêng tư.',
      'Nháp nhập liệu và trạng thái tab riêng tư chỉ giữ trên RAM tạm thời, tự xóa sạch khi đóng tab.',
      'Tự động bật cờ cửa sổ FLAG_SECURE để ngăn chụp ảnh màn hình trong khay đa nhiệm hệ thống.',
    ],
  },
  {
    id: 'privacy-shield',
    badge: '04 • LÁ CHẮN NỘI DUNG RUST FFI',
    shortLabel: 'Lá chắn Tracker',
    title: 'Lọc tên miền theo dõi và gọt sạch tham số bám đuôi ngay trước khi điều hướng',
    summary:
      'Veko kết nối trực tiếp thư viện brave/adblock-rust thông qua cầu nối C++20 JNI để đối chiếu quy tắc chặn quảng cáo/tracker và tự động loại bỏ các tham số theo dõi trên thanh URL.',
    modulePath: ':privacy + :chromium-native (adblock-rust 0.13.4)',
    bullets: [
      'Cắt bỏ tự động các tham số theo dõi phổ biến như utm_source, utm_medium, fbclid, gclid, mc_cid.',
      'Kết hợp đồng thời cơ chế Enhanced Tracking Protection (ETP) và cô lập cookie bên thứ ba của GeckoView.',
      'Hiển thị bộ đếm minh bạch số lượng yêu cầu đã chặn và URL đã làm sạch theo thời gian thực.',
    ],
  },
  {
    id: 'site-settings',
    badge: '05 • PHÂN QUYỀN & CÀI ĐẶT',
    shortLabel: 'Cài đặt & Quyền',
    title: 'Kiểm soát quyền phần cứng theo từng Origin và chẩn đoán nhân trình duyệt minh bạch',
    summary:
      'Quyền truy cập Máy ảnh, Micrô và Vị trí được chuẩn hóa chặt chẽ theo từng định danh scheme://host:port thay vì cấp tràn lan. Mục Cài đặt cũng hiển thị trung thực trạng thái xác thực SDK của từng nhân trình duyệt.',
    modulePath: ':security (SitePermissionManager) + :storage',
    bullets: [
      'Quyền cấp tạm trong tab Riêng tư chỉ nằm trên RAM và tự động hết hiệu lực khi đóng phiên.',
      'Kiểm tra nghiêm ngặt liên kết mở ứng dụng bên ngoài (intent://, mailto:, tel:) qua hộp thoại xác nhận.',
      'Hiển thị đầy đủ phiên bản nhân, engine kết xuất/JS, ABI hỗ trợ và lý do nếu gói SDK phụ chưa nạp.',
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
    regularTab: 'Lưu metadata tab thường (schema v3) trong DataStore cục bộ',
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
  'Bộ lọc Rust (adblock-rust) hiện kiểm duyệt yêu cầu điều hướng và gọt tham số URL thông qua cầu nối JNI kết hợp cơ chế Enhanced Tracking Protection của GeckoView; tầng hủy từng tài nguyên con qua blink::URLLoaderThrottle của nhân phụ Chromium vẫn đang trong giai đoạn phát triển.',
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
    name: 'Mozilla GeckoView Runtime (Nhân chính mặc định)',
    versionOrScope: '150.0.20260511200624 • Verified',
    role: 'Nhân kết xuất Gecko + SpiderMonkey từ kho chính thức Mozilla Maven đang xử lý toàn bộ trang web thực tế trên Android với Enhanced Tracking Protection và cô lập cookie dFPI.',
    status: 'active',
  },
  {
    name: 'Kiến trúc Dual-Engine Coordinator & MVI Compose',
    versionOrScope: 'AGP 9.1.1 • Kotlin 2.3.20 • JDK 17',
    role: 'Tách biệt hoàn toàn giao diện Jetpack Compose khỏi lớp engine cụ thể thông qua BrowserEngine, BrowserEngineManager và định tuyến phiên độc lập theo từng Tab.',
    status: 'active',
  },
  {
    name: 'Rust brave/adblock-rust + C++20 JNI (16 KB ELF)',
    versionOrScope: 'v0.13.4 • GN / Ninja / NDK',
    role: 'Thư viện lọc quảng cáo/tracker biên dịch từ nguồn Rust (MPL-2.0) liên kết tĩnh vào libveko_chromium_native.so cho arm64-v8a, armeabi-v7a và x86_64 đạt chuẩn trang nhớ 16 KB.',
    status: 'active',
  },
  {
    name: 'Room SQLite v1 + Preferences DataStore',
    versionOrScope: ':storage • Scoped Allowlist',
    role: 'Lưu trữ cục bộ cho dấu trang, lịch sử tab thường, cấu hình nhân ưu tiên và quyền theo Origin với chính sách loại trừ sao lưu dữ liệu nhạy cảm.',
    status: 'active',
  },
  {
    name: 'Kiểm thử Tự động Đa tầng trên GitHub Actions CI',
    versionOrScope: '58 Unit Tests + 10 Emulator Tests',
    role: 'Mỗi bản dựng đều kiểm tra Rust/C++20, 58 bài JUnit, Android Lint, căn lề ELF 16 KB và 10 kịch bản thực tế trên máy ảo Android 14 (API 34 x86_64 KVM).',
    status: 'active',
  },
];

export const TECH_STACK_ROADMAP: readonly TechLayerItem[] = [
  {
    name: 'Nhân phụ Chromium Content (Blink + V8)',
    versionOrScope: '156.0.8078.85 • In Development',
    role: 'Đã hoàn thiện mã nguồn C++20 Content API, SurfaceView ANativeWindow và ChromiumAvailabilityChecker; hiện khóa trung thực ở trạng thái BLOCKED chờ biên dịch gói SDK libveko_content_embedder.so trên máy chủ chuyên dụng (≥130 GB SSD / ≥32 GB RAM).',
    status: 'roadmap',
  },
  {
    name: 'Chặn tài nguyên con mức URLLoaderThrottle (Chromium)',
    versionOrScope: 'content::URLLoaderThrottle • In Development',
    role: 'Đã cài đặt lớp cầu nối C++20 VekoContentBrowserClient để hủy yêu cầu tài nguyên con khi gói nhị phân Chromium Content thượng nguồn được nạp và xác thực.',
    status: 'roadmap',
  },
  {
    name: 'Chuẩn bị Metadata & Công thức biên dịch nguồn cho F-Droid',
    versionOrScope: 'metadata/fdroid • In Development',
    role: 'Đã kiểm toán phụ thuộc và lập hồ sơ metadata io.veko.browser.yml; cần giải quyết phụ thuộc nhị phân Mozilla Maven và khóa Cargo.lock ngoại tuyến trước khi gửi fdroiddata.',
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
    responsibility: 'Giao diện Compose, BrowserEngineManager, nhân GeckoView & điều phối nhân phụ Chromium',
    state: 'Hoạt động',
  },
  {
    path: ':browser-core',
    responsibility: 'Hợp đồng BrowserEngine, TabManager (LRU 6 phiên, schema v3), UrlNavigationPolicy & MVI Store',
    state: 'Hoạt động',
  },
  {
    path: ':privacy',
    responsibility: 'Lá chắn nội dung PrivacyShieldEngine, gọt tham số URL & thống kê chặn theo thời gian thực',
    state: 'Hoạt động',
  },
  {
    path: ':security',
    responsibility: 'SitePermissionManager theo Origin (scheme://host:port) & kiểm duyệt Intent bên ngoài',
    state: 'Hoạt động',
  },
  {
    path: ':storage',
    responsibility: 'Room Database v1, SettingsRepository & mô hình EngineType / EngineAvailability',
    state: 'Hoạt động',
  },
  {
    path: ':downloads',
    responsibility: 'VekoDownloadManager qua MediaStore Scoped Storage & kiểm tra MIME / tên tệp an toàn',
    state: 'Hoạt động',
  },
  {
    path: ':chromium-native',
    responsibility: 'Cầu nối C++20 JNI, Surface ANativeWindow, GN/Ninja & thư viện tĩnh Rust adblock-rust',
    state: 'Hoạt động',
  },
  {
    path: ':chromium-embedder',
    responsibility: 'Khung nhúng Chromium Content 156.0.8078.85 (đang chờ gói SDK nhị phân thượng nguồn)',
    state: 'Đang phát triển',
  },
  {
    path: ':testing',
    responsibility: 'Bộ kiểm thử tích hợp MVI, JNI, quyền riêng tư và xác thực cấu hình CI',
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
    signingAndOptimization: 'Bật R8 minification + shrinkResources, tách 3 kiến trúc ABI (~86–93 MB/gói)',
    availability: 'Xuất tự động trong CI Artifacts (veko-preview-arm64-v8a / armeabi-v7a / x86_64)',
  },
  {
    name: 'Veko Release (Chính thức)',
    packageId: 'io.veko.browser',
    signingAndOptimization: 'Bắt buộc khóa ký phát hành riêng qua verifyReleaseSigningConfig + xác minh apksigner & SHA-256',
    availability: 'Đã phát hành v0.2.0 trên GitHub Releases (3 gói ABI đã ký số kèm SHA256SUMS.txt)',
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
    question: 'Veko đang sử dụng browser engine nào? GeckoView và Chromium có chạy cùng lúc không?',
    answer:
      'Không chạy ngầm hai nhân cùng lúc. Trong bản dựng hiện tại, Mozilla GeckoView 150.0 (kết xuất Gecko + JavaScript SpiderMonkey) là nhân mặc định duy nhất đang tải và hiển thị trang web thực tế (không dùng android.webkit.WebView). Kiến trúc Dual-Engine cho nhân phụ Chromium Content 156.0.8078.85 (Blink + V8) đang trong giai đoạn phát triển (In Development) và được khóa trung thực ở trạng thái BLOCKED cho tới khi gói SDK nhị phân thượng nguồn được biên dịch và kiểm định.',
  },
  {
    question: 'Veko Browser được cấp phép theo giấy phép mã nguồn mở nào?',
    answer:
      'Kho mã nguồn Veko Browser được phát hành theo giấy phép Mozilla Public License Version 2.0 (MPL-2.0, xem tệp LICENSE tại thư mục gốc của dự án trên GitHub), đồng bộ với giấy phép MPL-2.0 của Mozilla GeckoView và crate lọc nội dung brave/adblock-rust.',
  },
  {
    question: 'Veko xử lý dữ liệu giữa Tab thường và Tab Riêng tư như thế nào?',
    answer:
      'Veko không yêu cầu tài khoản và không nhúng SDK phân tích hành vi. Ở tab thường, dấu trang, lịch sử và metadata khôi phục tab được lưu cục bộ trên điện thoại (Room SQLite + DataStore) và bị loại trừ khỏi sao lưu đám mây Android Backup. Ở tab Riêng tư, phiên duyệt và nháp nhập liệu chỉ giữ trong bộ nhớ RAM và tự xóa sạch khi đóng các tab riêng tư.',
  },
  {
    question: 'Làm thế nào để cài đặt Veko Browser an toàn và trạng thái F-Droid hiện ra sao?',
    answer:
      'Bạn có thể tải gói APK chính thức v0.2.0 đã ký số (khuyên dùng bản arm64-v8a cho điện thoại Android 64-bit) trực tiếp từ GitHub Releases và đối chiếu mã băm trong tệp SHA256SUMS.txt. Hồ sơ metadata chuẩn bị cho F-Droid đã được thiết lập trong thư mục metadata/fdroid/, tuy nhiên ứng dụng hiện chưa có mặt trên F-Droid hay Google Play cho tới khi hoàn tất quy trình biên dịch nguồn tái lập của F-Droid.',
  },
  {
    question: 'Chế độ Riêng tư của Veko có giúp ẩn địa chỉ IP trước nhà mạng không?',
    answer:
      'Không. Chế độ Riêng tư và lá chắn tracker giúp không lưu lịch sử duyệt web trên thiết bị này và chặn nhiều tên miền theo dõi phổ biến, nhưng không thay thế mạng riêng ảo (VPN) hay mạng Tor. Nhà cung cấp dịch vụ Internet (ISP), quản trị mạng nội bộ hoặc trang web đích vẫn nhận biết kết nối mạng của thiết bị.',
  },
];
