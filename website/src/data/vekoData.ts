export const VEKO_SITE_ORIGIN = 'https://tiendayy.kdns.fr';
export const VEKO_REPO_URL = 'https://github.com/TienHeavenVN/Zeko';
export const VEKO_RELEASES_URL = 'https://github.com/TienHeavenVN/Zeko/releases';
export const VEKO_ACTIONS_URL = 'https://github.com/TienHeavenVN/Zeko/actions/workflows/build-apk.yml';
export const VEKO_WEBSITE_ACTIONS_URL = 'https://github.com/TienHeavenVN/Zeko/actions/workflows/website.yml';
export const VEKO_README_URL = 'https://github.com/TienHeavenVN/Zeko/blob/main/README.md';
export const VEKO_AUDIT_DOC_URL = 'https://github.com/TienHeavenVN/Zeko/blob/main/docs/CHROMIUM_MIGRATION_AUDIT.md';

/**
 * Thông tin phiên bản và trạng thái phát hành thực tế đối chiếu trực tiếp từ app/build.gradle.kts & GitHub Releases API:
 * - versionName trong mã nguồn hiện tại: "0.2.0-chromium-arch" (minSdk 26 / targetSdk 36 / compileSdk 37).
 * - GitHub Releases chính thức: 0 bản phát hành (chưa gắn tag v* từ main).
 */
export const VEKO_SOURCE_VERSION = '0.2.0-chromium-arch';
export const VEKO_MIN_ANDROID = 'Android 8.0+ (API 26+)';
export const HAS_OFFICIAL_RELEASE = false;
export const OFFICIAL_RELEASE_VERSION: string | null = null;

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
      'Thay vì giữ hàng chục phiên trình duyệt chạy nền gây nóng máy, TabManager chỉ duy trì tối đa 6 phiên sống đồng thời trên RAM và bảo toàn URL cùng trạng thái điều hướng để khôi phục lười khi bạn chạm lại.',
    modulePath: ':browser-core (TabManager • MAX_LIVE_SESSIONS = 6)',
    bullets: [
      'Vuốt lên từ thanh địa chỉ đáy để mở ngay lưới Tab Preview Sheet với ảnh xem trước thu nhỏ 360px.',
      'Tự động gọi releaseSession() và setActive(false) khi chuyển tab hoặc đưa ứng dụng xuống nền.',
      'Tách riêng danh sách Tab thường và Tab riêng tư ngay trong khay chuyển tab.',
    ],
  },
  {
    id: 'private-mode',
    badge: '03 • CHẾ ĐỘ RIÊNG TƯ TRÊN RAM',
    shortLabel: 'Duyệt riêng tư',
    title: 'Phiên riêng tư tách biệt hoàn toàn khỏi SQLite, DataStore và Bundle hệ thống',
    summary:
      'Mỗi tab riêng tư chạy trên phiên riêng tư của engine hiển thị kết hợp ngữ cảnh Off-The-Record gốc trên bộ nhớ khả biến. Không một dòng lịch sử, dấu trang tạm hay nháp thanh địa chỉ nào được ghi xuống đĩa.',
    modulePath: ':browser-core + :chromium-native (Off-The-Record)',
    bullets: [
      'Gợi ý trên thanh địa chỉ tự động loại bỏ lịch sử duyệt web khi đang ở chế độ Riêng tư.',
      'Nháp nhập liệu chỉ giữ bằng bộ nhớ RAM tạm thời và xóa sạch ngay khi rời phiên hoặc đóng tab.',
      'Đóng toàn bộ tab riêng tư sẽ hủy tức thì ngữ cảnh Off-The-Record đang cấp phát.',
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
    title: 'Kiểm soát quyền phần cứng theo từng Origin và làm chủ giao diện hiển thị',
    summary:
      'Quyền truy cập Máy ảnh, Micrô và Vị trí được chuẩn hóa chặt chẽ theo từng định danh scheme://host:port thay vì cấp tràn lan. Người dùng có thể xem, thu hồi từng trang hoặc xóa sạch bất kỳ lúc nào.',
    modulePath: ':security (SitePermissionManager) + :storage',
    bullets: [
      'Quyền cấp tạm trong tab Riêng tư chỉ nằm trên RAM và tự động hết hiệu lực khi đóng phiên.',
      'Kiểm tra nghiêm ngặt liên kết mở ứng dụng bên ngoài (intent://, mailto:, tel:) qua hộp thoại xác nhận.',
      'Tùy chỉnh linh hoạt công cụ tìm kiếm mặc định, chế độ Cookie và tùy chọn màu động Material You.',
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
      'backup_rules.xml (API 26–30) và data_extraction_rules.xml (API 31+) khóa allowlist chỉ cho phép sao lưu tệp cài đặt giao diện, loại trừ hoàn toàn lịch sử duyệt web, phiên tab và cookie.',
  },
  {
    codeTag: 'RAM ISOLATION • OTR',
    title: 'Tab Riêng tư không chạm vào bộ nhớ lưu trữ',
    summary:
      'Khi bật chế độ Riêng tư, Veko kích hoạt usePrivateMode(true) cùng ngữ cảnh Off-The-Record trên RAM và bỏ qua hoàn toàn mọi thao tác ghi xuống SQLite hay DataStore.',
    technicalDetail:
      'Được kiểm chứng tự động trên máy ảo Android 14 bằng bài test quét hệ thống tệp để xác nhận chuỗi canary riêng tư không xuất hiện trong bất kỳ tệp cơ sở dữ liệu nào trên đĩa.',
  },
  {
    codeTag: 'NAVIGATION GUARD • TLS',
    title: 'Kiểm soát điều hướng & Không bỏ qua lỗi chứng chỉ',
    summary:
      'UrlNavigationPolicy chặn chuyển hướng tự động trái phép sang data:/blob:/javascript:, hạn chế nội dung hỗn hợp (mixed content) và không bao giờ tự động bỏ qua lỗi chứng chỉ TLS không hợp lệ.',
    technicalDetail:
      'Trang báo lỗi nội bộ được escape đầy đủ ký tự đặc biệt/bidi và gắn chính sách Content-Security-Policy (CSP) khóa thực thi script.',
  },
  {
    codeTag: 'ZERO TELEMETRY • COIL RAM',
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
    privateTab: 'Không ghi lịch sử xuống đĩa; tự hủy khi đóng tab',
    networkNote: 'Không đồng bộ lên đám mây của Veko',
  },
  {
    aspect: 'Nháp nhập liệu thanh địa chỉ',
    regularTab: 'Giữ tạm trong phiên làm việc trên RAM',
    privateTab: 'Chỉ giữ trên RAM, xóa ngay khi ẩn/đóng phiên',
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
    privateTab: 'Không tồn tại trên đĩa để sao lưu',
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
  'Chế độ Riêng tư và lá chắn tracker KHÔNG biến bạn thành "ẩn danh tuyệt đối" trên mạng; nhà mạng (ISP), quản trị Wi-Fi, máy chủ DNS hoặc chính trang web bạn truy cập vẫn nhận biết kết nối nếu không dùng VPN/Tor.',
  'Bộ lọc Rust (adblock-rust) hiện kiểm duyệt yêu cầu điều hướng và gọt tham số URL thông qua cầu nối JNI; tầng chặn mọi tài nguyên con bên trong ống dẫn Chromium Content (blink::URLLoaderThrottle) vẫn đang trong lộ trình hoàn thiện.',
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
    name: 'Mozilla GeckoView Runtime',
    versionOrScope: '150.0 • Active Web Engine',
    role: 'Nhân kết xuất trang web chính đang vận hành ổn định trên Android với kiến trúc đa tiến trình (:tab, :gpu), Enhanced Tracking Protection và SafeBrowsing.',
    status: 'active',
  },
  {
    name: 'Kotlin + Jetpack Compose + Material 3',
    versionOrScope: 'AGP 9.1.1 • JDK 17 • MVI / UDF',
    role: 'Toàn bộ giao diện người dùng bất biến (@Immutable HomeUiState), điều hướng một Activity duy nhất và luồng dữ liệu một chiều qua StateFlow.',
    status: 'active',
  },
  {
    name: 'Rust brave/adblock-rust + C++20 JNI',
    versionOrScope: 'v0.13.4 • GN / Ninja Build',
    role: 'Thư viện lọc quảng cáo/tracker biên dịch tĩnh liên kết vào libveko_chromium_native.so cho cả 3 kiến trúc arm64-v8a, armeabi-v7a và x86_64.',
    status: 'active',
  },
  {
    name: 'Room SQLite v1 + Preferences DataStore',
    versionOrScope: ':storage • Scoped Allowlist',
    role: 'Lưu trữ bền vững cục bộ cho dấu trang, lịch sử tab thường và cài đặt người dùng với chính sách loại trừ sao lưu dữ liệu nhạy cảm.',
    status: 'active',
  },
  {
    name: 'Kiểm thử Tự động Đa tầng trên CI',
    versionOrScope: '45 Unit Tests + 7 Emulator Tests',
    role: 'Mỗi thay đổi đều chạy kiểm thử Rust/C++20, JUnit, Android Lint và kiểm thử thực tế trên máy ảo Android 14 (API 34 x86_64 KVM).',
    status: 'active',
  },
];

export const TECH_STACK_ROADMAP: readonly TechLayerItem[] = [
  {
    name: 'Tầng nhúng Chromium Content API',
    versionOrScope: 'Target 156.0.8078.85 • Đang phát triển',
    role: 'Đã xây dựng hợp đồng kiến trúc C++20/JNI trong :chromium-embedder và :chromium-native; hiện chưa liên kết nhị phân libcontent.so vào APK tiêu chuẩn.',
    status: 'roadmap',
  },
  {
    name: 'Chặn tài nguyên con mức URLLoaderThrottle',
    versionOrScope: 'blink::URLLoaderThrottle • Lộ trình Phase 6',
    role: 'Thiết kế sẵn điểm móc hủy tải tài nguyên con bằng net::ERR_BLOCKED_BY_CLIENT khi hoàn tất quá trình chuyển đổi sang nhân Chromium Content.',
    status: 'roadmap',
  },
];

export interface GradleModuleInfo {
  readonly path: string;
  readonly responsibility: string;
  readonly state: 'Hoạt động' | 'Hợp đồng lộ trình';
}

export const GRADLE_MODULES: readonly GradleModuleInfo[] = [
  { path: ':app', responsibility: 'Giao diện Compose, HomeScreen, BrowserBottomBar & điều phối ViewModel', state: 'Hoạt động' },
  { path: ':browser-core', responsibility: 'TabManager (LRU 6 phiên), BrowserRuntimeManager & UrlNavigationPolicy', state: 'Hoạt động' },
  { path: ':privacy', responsibility: 'Tích hợp chính sách lọc nội dung, gọt tham số URL & thống kê lá chắn', state: 'Hoạt động' },
  { path: ':security', responsibility: 'SitePermissionManager theo Origin & kiểm duyệt Intent bên ngoài', state: 'Hoạt động' },
  { path: ':storage', responsibility: 'Room Database v1 (dấu trang, lịch sử) & DataStore cài đặt', state: 'Hoạt động' },
  { path: ':downloads', responsibility: 'VekoDownloadManager qua MediaStore Scoped Storage & chống giả mạo MIME', state: 'Hoạt động' },
  { path: ':chromium-native', responsibility: 'Cầu nối C++20 JNI, biên dịch GN/Ninja & thư viện tĩnh Rust adblock', state: 'Hoạt động' },
  { path: ':chromium-embedder', responsibility: 'Lớp trừu tượng nhúng Chromium Content phục vụ lộ trình chuyển đổi engine', state: 'Hợp đồng lộ trình' },
  { path: ':testing', responsibility: 'Tiện ích kiểm thử tích hợp MVI, JNI và quyền riêng tư', state: 'Hoạt động' },
];

export interface ScreenshotItem {
  readonly src: string;
  readonly tag: string;
  readonly alt: string;
  readonly captionTitle: string;
  readonly captionBody: string;
}

export const SCREENSHOT_ITEMS: readonly ScreenshotItem[] = [
  {
    src: 'screenshots/veko-home-refactored.svg',
    tag: 'MÀN HÌNH CHÍNH',
    alt: 'Giao diện Trang chủ Veko Browser với header tối giản không phụ đề, ô tìm kiếm trung tâm, 4 nút tác vụ nhanh và thanh điều hướng dưới đáy',
    captionTitle: 'Trang chủ tinh gọn & thao tác một tay',
    captionBody:
      'Loại bỏ các dòng khẩu hiệu rườm rà dưới tên Veko; đặt ô nhập địa chỉ, bộ chọn tìm kiếm, 4 nút tác vụ nhanh và thanh điều hướng đáy vào đúng tầm với tự nhiên.',
  },
  {
    src: 'screenshots/veko-tab-switcher.svg',
    tag: 'QUẢN LÝ TAB LRU',
    alt: 'Khay lưới quản lý Tab của Veko Browser hiển thị các thẻ xem trước tab, bộ chuyển Tab thường và Riêng tư cùng trạng thái giới hạn 6 phiên sống LRU',
    captionTitle: 'Khay lưới Tab & Trần 6 phiên sống',
    captionBody:
      'Mở nhanh bằng thao tác vuốt lên từ thanh địa chỉ đáy, phân tách rõ Tab thường và Riêng tư kèm cơ chế đóng băng khôi phục lười tiết kiệm RAM.',
  },
  {
    src: 'screenshots/veko-private-mode.svg',
    tag: 'CHẾ ĐỘ RIÊNG TƯ',
    alt: 'Giao diện Chế độ Riêng tư của Veko Browser giải thích rõ cơ chế cô lập trên RAM và những giới hạn mạng thực tế',
    captionTitle: 'Không gian Riêng tư trung thực',
    captionBody:
      'Ẩn hoàn toàn lối tắt và lịch sử tab thường, giải thích rõ phạm vi dữ liệu giữ trên RAM so với những gì nhà mạng hoặc trang đích vẫn thấy.',
  },
  {
    src: 'screenshots/veko-privacy-matrix.svg',
    tag: 'LÁ CHẮN & COOKIE',
    alt: 'Khay Ma trận Quyền riêng tư Veko hiển thị bộ đếm chặn quảng cáo, tracker, làm sạch URL và 3 mức kiểm soát Cookie',
    captionTitle: 'Ma trận Lá chắn & Kiểm soát Cookie',
    captionBody:
      'Quan sát trực quan số lượng tracker đã chặn, tham số bám đuôi đã cắt bỏ bởi nhân Rust và chuyển đổi nhanh giữa 3 chế độ cô lập Cookie.',
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
    signingAndOptimization: 'Khóa ký phát triển cục bộ, giữ biểu tượng gỡ lỗi phục vụ kiểm thử',
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
    signingAndOptimization: 'Bắt buộc khóa ký phát hành riêng (chặn fallback debug key) + xác minh apksigner & SHA-256',
    availability: 'Chưa phát hành — chỉ xuất bản lên GitHub Releases khi gắn tag v* hợp lệ từ nhánh main',
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
      'Veko Browser (kho mã nguồn Zeko) là trình duyệt web dành cho Android 8.0 trở lên, tập trung vào 3 giá trị cốt lõi: bố cục điều hướng một tay ở cạnh dưới không nhồi nhét tin tức rác, lưu trữ dữ liệu cá nhân cục bộ trên thiết bị, và minh bạch hóa cơ chế bảo vệ quyền riêng tư bằng kiểm thử tự động.',
  },
  {
    question: 'Veko đang sử dụng browser engine nào để hiển thị trang web?',
    answer:
      'Trong mã nguồn vận hành hiện tại, Veko sử dụng nhân kết xuất thực Mozilla GeckoView (phiên bản 150.x, không dùng android.webkit.WebView hệ thống) kết hợp thư viện lọc nội dung Rust (brave/adblock-rust 0.13.4) qua cầu nối C++20 JNI. Song song đó, dự án đang phát triển tầng nhúng hợp đồng cho lộ trình chuyển đổi sang Chromium Content trong các module :chromium-embedder và :chromium-native.',
  },
  {
    question: 'Veko có phải phần mềm mã nguồn mở không?',
    answer:
      'Toàn bộ mã nguồn ứng dụng Android, tầng C++20/Rust JNI, kịch bản kiểm thử máy ảo và mã nguồn của chính website này đều được công khai minh bạch trên GitHub tại TienHeavenVN/Zeko. Các thành phần nền tảng như GeckoView và adblock-rust tuân theo giấy phép nguồn mở tương ứng (MPL-2.0); tệp LICENSE cấp cao nhất ở thư mục gốc của kho lưu trữ hiện đang chờ chủ dự án bổ sung chính thức trước kỳ phát hành đầu tiên.',
  },
  {
    question: 'Veko xử lý và lưu trữ dữ liệu người dùng như thế nào?',
    answer:
      'Veko không yêu cầu đăng nhập tài khoản và không tích hợp SDK phân tích hành vi (0 telemetry). Ở tab thường, dấu trang và lịch sử chỉ nằm trong cơ sở dữ liệu SQLite nội bộ trên điện thoại của bạn và bị loại trừ khỏi bản sao lưu đám mây Android Backup. Ở tab Riêng tư, phiên duyệt chỉ sống trên bộ nhớ RAM và tự hủy ngay khi đóng các tab riêng tư.',
  },
  {
    question: 'Làm thế nào để cài đặt và cập nhật Veko Browser ở thời điểm hiện tại?',
    answer:
      'Hiện tại Veko chưa phát hành trên Google Play hay F-Droid và chưa công bố bản Release chính thức đầu tiên trên kênh GitHub Releases. Để trải nghiệm sớm, bạn có thể tự biên dịch gói APK từ mã nguồn bằng lệnh ./gradlew :app:assembleDebug (hoặc :app:assemblePreview), hoặc tải gói artifact kiểm thử đã vượt qua CI trên trang GitHub Actions nếu bạn có quyền truy cập GitHub. Khi bản chính thức đầu tiên ra mắt, các gói APK đã ký khóa phát hành kèm mã kiểm tra SHA-256 sẽ được đăng tải trực tiếp tại GitHub Releases.',
  },
  {
    question: 'Chế độ Riêng tư của Veko có giúp ẩn địa chỉ IP trước nhà mạng không?',
    answer:
      'Không. Chúng tôi luôn nêu rõ giới hạn kỹ thuật này: Chế độ Riêng tư và bộ lọc tracker giúp ngăn lưu lịch sử trên thiết bị của bạn và chặn nhiều tên miền theo dõi phổ biến, nhưng không thay thế mạng riêng ảo (VPN) hay mạng Tor. Nhà cung cấp dịch vụ Internet (ISP), quản trị mạng nội bộ hoặc chính trang web bạn truy cập vẫn biết kết nối mạng của thiết bị.',
  },
];
