export const VEKO_REPO_URL = 'https://github.com/TienHeavenVN/Zeko';
export const VEKO_RELEASES_URL = 'https://github.com/TienHeavenVN/Zeko/releases';
export const VEKO_ACTIONS_URL = 'https://github.com/TienHeavenVN/Zeko/actions/workflows/build-apk.yml';
export const VEKO_README_URL = 'https://github.com/TienHeavenVN/Zeko/blob/main/README.md';
export const VEKO_AUDIT_DOC_URL = 'https://github.com/TienHeavenVN/Zeko/blob/main/docs/CHROMIUM_MIGRATION_AUDIT.md';

/**
 * Cờ trạng thái phát hành chính thức:
 * Hiện tại repository chưa công bố tag phát hành chính thức trên GitHub Releases (0 releases).
 * Khi có bản phát hành chính thức hợp lệ, cập nhật cờ này sang true kèm URL phát hành đã xác minh.
 */
export const HAS_OFFICIAL_RELEASE = false;
export const OFFICIAL_RELEASE_VERSION: string | null = null;

export interface FeatureItem {
  readonly id: string;
  readonly icon: 'smartphone' | 'layers' | 'shield' | 'lock' | 'bookmark' | 'download';
  readonly title: string;
  readonly description: string;
  readonly codeModule: string;
  readonly status: 'verified' | 'in-development';
}

export const VERIFIED_FEATURES: readonly FeatureItem[] = [
  {
    id: 'ergonomic-ui',
    icon: 'smartphone',
    title: 'Giao diện tối giản & Thao tác một tay',
    description:
      'Xây dựng bằng Jetpack Compose và Material 3 trên nền xanh đen dịu mắt. Thanh điều hướng đặt ở cạnh dưới hỗ trợ vuốt ngang để chuyển tab và vuốt lên để mở khay xem trước tab.',
    codeModule: ':app (HomeScreen, BrowserBottomBar)',
    status: 'verified',
  },
  {
    id: 'lru-tab-management',
    icon: 'layers',
    title: 'Quản lý Tab với trần bộ nhớ LRU',
    description:
      'Duy trì tối đa 6 phiên trình duyệt sống đồng thời trên RAM (MAX_LIVE_SESSIONS = 6), tự động giải phóng phiên cũ và khôi phục lười URL cùng trạng thái điều hướng khi chọn lại tab.',
    codeModule: ':browser-core (TabManager)',
    status: 'verified',
  },
  {
    id: 'ram-private-browsing',
    icon: 'shield',
    title: 'Duyệt web Riêng tư cô lập trên RAM',
    description:
      'Phiên riêng tư không ghi lịch sử truy cập, nháp thanh địa chỉ hay trạng thái tab xuống SQLite và DataStore. Ngữ cảnh Off-The-Record gốc tự hủy ngay khi đóng hết tab riêng tư.',
    codeModule: ':browser-core + :chromium-native',
    status: 'verified',
  },
  {
    id: 'origin-permissions',
    icon: 'lock',
    title: 'Quyền Website tách biệt theo Origin',
    description:
      'Kiểm soát quyền Máy ảnh, Micrô và Vị trí theo từng nguồn chuẩn hóa (scheme://host:port). Quyền cấp trong tab riêng tư chỉ tồn tại tạm thời trên RAM.',
    codeModule: ':security (SitePermissionManager)',
    status: 'verified',
  },
  {
    id: 'local-bookmarks-history',
    icon: 'bookmark',
    title: 'Lịch sử, Dấu trang & Lối tắt cục bộ',
    description:
      'Toàn bộ dấu trang và lịch sử tab thường được lưu cục bộ trên thiết bị qua Room SQLite. Cho phép ghim, sửa tên hoặc gỡ lối tắt trực tiếp trên trang chủ mà không chèn dữ liệu quảng cáo.',
    codeModule: ':storage (VekoDatabase v1)',
    status: 'verified',
  },
  {
    id: 'downloads-and-tracker-shield',
    icon: 'download',
    title: 'Tải xuống an toàn & Lọc Tracker điều hướng',
    description:
      'Tải tệp qua MediaStore Scoped Storage kèm kiểm tra giả mạo MIME tệp thực thi, đồng thời kết nối bộ lọc Rust (brave/adblock-rust) qua C++20 JNI để chặn tên miền tracker và cắt tham số utm_*, fbclid.',
    codeModule: ':downloads + :privacy (Rust FFI)',
    status: 'verified',
  },
];

export interface PrivacyExplanation {
  readonly title: string;
  readonly summary: string;
  readonly technicalDetail: string;
}

export const PRIVACY_PILLARS: readonly PrivacyExplanation[] = [
  {
    title: 'Dữ liệu nào được lưu trên thiết bị?',
    summary:
      'Lịch sử tab thường và dấu trang lưu cục bộ trong cơ sở dữ liệu SQLite nội bộ (veko_browser.db); tùy chọn công cụ tìm kiếm và hiển thị lưu trong DataStore (veko_user_settings.preferences_pb).',
    technicalDetail:
      'Tệp cấu hình backup_rules.xml và data_extraction_rules.xml chỉ cho phép hệ điều hành Android sao lưu duy nhất tệp cài đặt giao diện, loại trừ lịch sử duyệt web và phiên tab.',
  },
  {
    title: 'Chế độ Riêng tư hoạt động như thế nào?',
    summary:
      'Khi mở tab riêng tư, Veko kích hoạt phiên riêng tư của engine hiển thị cùng ngữ cảnh Off-The-Record trên RAM (không tạo thư mục lưu trữ trên đĩa), đồng thời bỏ qua hoàn toàn tầng ghi lịch sử Room.',
    technicalDetail:
      'Nháp nhập liệu trên thanh địa chỉ dùng bộ nhớ tạm trên RAM thay vì Bundle hệ thống và tự động xóa trắng khi ứng dụng chuyển xuống nền hoặc đóng tab riêng tư.',
  },
  {
    title: 'Cách xóa lịch sử và dữ liệu trang web',
    summary:
      'Người dùng có thể xóa từng mục lịch sử, xóa toàn bộ lịch sử duyệt web trong khay Thư viện, hoặc thu hồi quyền truy cập phần cứng của từng website ngay trong phần Cài đặt Veko.',
    technicalDetail:
      'Thao tác đóng toàn bộ tab riêng tư giải phóng tức thì các phiên riêng tư đang mở và hủy bộ đếm ngữ cảnh Off-The-Record gốc.',
  },
  {
    title: 'Không tích hợp Telemetry mặc định',
    summary:
      'Mã nguồn ứng dụng Veko Browser và trang web giới thiệu này không nhúng SDK phân tích hành vi, không gắn mã theo dõi bên thứ ba và không gửi dữ liệu sử dụng về máy chủ riêng.',
    technicalDetail:
      'Thư viện hiển thị ảnh giao diện (Coil 3) được khóa cứng CachePolicy.DISABLED cho mạng và bộ nhớ đệm đĩa để chỉ giải mã tài nguyên cục bộ trên RAM.',
  },
];

export const PRIVACY_LIMITATIONS: readonly string[] = [
  'Chế độ Riêng tư và bộ lọc trình theo dõi KHÔNG giúp bạn "ẩn danh hoàn toàn" trên Internet; nhà cung cấp dịch vụ mạng (ISP), máy chủ DNS, quản trị mạng nội bộ hoặc chính trang web bạn truy cập vẫn biết kết nối mạng nếu không dùng thêm VPN hoặc Tor.',
  'Đường ống lọc Rust (adblock-rust) hiện kiểm duyệt yêu cầu điều hướng khung chính/khung con và làm sạch tham số URL; quá trình di trú nhân kết xuất sang Chromium Content (blink::URLLoaderThrottle cho mọi tài nguyên con) vẫn đang trong giai đoạn phát triển.',
  'Khi bạn truy cập một trang web hoặc sử dụng công cụ tìm kiếm đã chọn (DuckDuckGo, Google, Brave Search, Bing), truy vấn đó tuân theo chính sách quyền riêng tư của dịch vụ đích.',
];

export interface ScreenshotItem {
  readonly src: string;
  readonly alt: string;
  readonly captionTitle: string;
  readonly captionBody: string;
}

export const SCREENSHOT_ITEMS: readonly ScreenshotItem[] = [
  {
    src: 'screenshots/veko-home-refactored.svg',
    alt: 'Màn hình Trang chủ Veko Browser với header gọn không tagline, ô tìm kiếm trung tâm, 4 nút tác vụ nhanh đồng nhất và thanh điều hướng dưới đáy',
    captionTitle: 'Trang chủ tinh gọn & một tay',
    captionBody:
      'Header tối giản giữ nhận diện Veko rõ nét, ô nhập địa chỉ nổi bật trung tâm, 4 tác vụ nhanh kích thước đồng nhất và thanh điều hướng đáy không bị co hẹp.',
  },
  {
    src: 'screenshots/veko-private-mode.svg',
    alt: 'Màn hình Chế độ Riêng tư của Veko Browser giải thích rõ cơ chế lưu trên RAM và các giới hạn mạng thực tế',
    captionTitle: 'Không gian Riêng tư minh bạch',
    captionBody:
      'Tách biệt hoàn toàn khỏi dấu trang và lịch sử tab thường, hiển thị giải thích trung thực về phạm vi bảo vệ trên thiết bị so với kết nối mạng.',
  },
  {
    src: 'screenshots/veko-privacy-matrix.svg',
    alt: 'Khay Ma trận Quyền riêng tư Veko hiển thị bộ đếm chặn quảng cáo, tracker, làm sạch URL và 3 chế độ kiểm soát Cookie',
    captionTitle: 'Ma trận Quyền riêng tư & Cookie',
    captionBody:
      'Theo dõi số lượng tên miền quảng cáo/tracker đã chặn và tham số theo dõi đã gọt bởi nhân lọc Rust, cùng 3 mức kiểm soát Cookie trực quan.',
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
    signingAndOptimization: 'Khóa ký phát triển cục bộ, giữ thông tin gỡ lỗi',
    availability: 'Tự biên dịch từ mã nguồn hoặc kiểm tra từ GitHub Actions CI',
  },
  {
    name: 'Veko Preview (Thử nghiệm)',
    packageId: 'io.veko.browser.preview',
    signingAndOptimization: 'Bật tối ưu hóa R8 + thu gọn tài nguyên, tách ABI (arm64-v8a, armeabi-v7a, x86_64)',
    availability: 'Xuất tự động dưới dạng CI Artifact sau mỗi lần kiểm thử vượt qua',
  },
  {
    name: 'Veko Release (Chính thức)',
    packageId: 'io.veko.browser',
    signingAndOptimization: 'Bắt buộc khóa ký phát hành riêng + xác minh chữ ký apksigner & SHA-256',
    availability: 'Đang phát triển — sẽ công bố trên GitHub Releases khi gắn tag v* từ nhánh main',
  },
];
