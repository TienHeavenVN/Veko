# Website Chính thức — Veko Browser (`website/`)

Trang giới thiệu sản phẩm chính thức cho **Veko Browser** tại tên miền **`https://veko.kdns.fr/`**, được thiết kế theo ngôn ngữ **"Abyssal Precision"** (Dark Mode Navy/Cyan/Cobalt mặc định + Light Mode WCAG AA) và tách biệt hoàn toàn với hệ thống build Gradle/NDK của ứng dụng Android.

## Công nghệ sử dụng

- **Astro `7.4.1`** (Static Site Generation — xuất HTML/CSS tĩnh tốc độ cao, không kèm runtime framework nặng phía trình duyệt)
- **TypeScript `5.9.x`** (`strict` mode)
- **Tailwind CSS v4** (`@tailwindcss/vite`, hệ thống CSS Custom Properties hỗ trợ đồng thời **Dark Theme mặc định** và **Light Theme** chuẩn tương phản WCAG AA, tôn trọng `prefers-reduced-motion: reduce`)
- **Lucide Icons** (`@lucide/astro`)
- **SEO, Sitemap & Structured Data** (`@astrojs/sitemap` loại trừ `/404/`, endpoint động `src/pages/robots.txt.ts` theo `SITE_URL`, Open Graph, Twitter Card + JSON-LD `SoftwareApplication`)

## Cấu trúc các phân mục trên Website

1. **Navigation (`Navbar.astro`)**: Logo Veko (unique SVG `gradientId`), menu điều hướng đầy đủ cho Desktop (`>=1024px`), Tablet (`768–1023px`) và Mobile (`<768px`), nút chuyển đổi giao diện Sáng/Tối không nhấp nháy (zero-FOUC), đóng ngăn menu bằng `Escape` hoặc chạm ngoài vùng.
2. **Hero (`HeroSection.astro`)**: Lưới tọa độ kỹ thuật, thông điệp giá trị cốt lõi, phân biệt rõ `Release v0.2.0` vs `Source 0.3.0-dual-engine` (`MPL-2.0`), và khung minh họa giao diện gắn nhãn rõ **`UI Mockup • SVG`**.
3. **Product Showcase (`ProductShowcaseSection.astro`)**: Bảng chuyển đổi tương tác 5 luồng thao tác (`role="tablist"` hỗ trợ phím mũi tên Trái/Phải/Home/End): Thanh địa chỉ cạnh dưới, Quản lý Tab LRU, Duyệt riêng tư cô lập trên RAM, Lá chắn nội dung Rust FFI, và Phân quyền theo Origin.
4. **Features (`FeaturesSection.astro`)**: Bố cục Asymmetric Bento 5 khối với sơ đồ trực quan và trạng thái xác minh riêng cho từng tính năng.
5. **Privacy & Security (`PrivacySection.astro` + `/privacy/`)**: 4 trụ cột kỹ thuật đã kiểm thử, bảng đối chiếu phạm vi *Tab thường vs. Tab Riêng tư vs. Mạng* và hộp công bố giới hạn mạng thực tế (không dùng tuyên bố bảo mật tuyệt đối).
6. **Technology (`TechnologySection.astro`)**: Tách bạch thành phần `Verified` (Mozilla GeckoView `150.0`, Kotlin Compose, `adblock-rust` + C++20 JNI 16 KB ELF) và `In Development` (nhân phụ Chromium Content `156.0.8078.85`), bản đồ 9 module Gradle và **Bảng kiểm toán 11 hạng mục kỹ thuật đối chiếu tệp mã nguồn**.
7. **Screenshots (`ScreenshotsSection.astro`)**: 4 thẻ minh họa vector `360×760` gắn nhãn rõ **`UI Mockup • SVG`** (lazy loading, `decoding="async"`, `sizes` đáp ứng) kèm liên kết tới ảnh PNG thực tế xuất từ máy ảo Android 14 trên CI.
8. **Download (`DownloadSection.astro`)**: Hiển thị gói phát hành đã ký số `v0.2.0` kèm mã băm `SHA256SUMS.txt`, hướng dẫn tự biên dịch khớp cấu trúc 9 module + `setup-native-toolchains.sh` và bảng so sánh 3 biến thể APK (`debug`, `preview`, `release`).
9. **FAQ (`FaqSection.astro`)**: 6 câu hỏi thường gặp sử dụng thẻ ngữ nghĩa `<details>/<summary>`.
10. **Footer (`Footer.astro`)**: GitHub, Documentation, Tài liệu Quyền riêng tư, giấy phép `MPL-2.0` (`LICENSE`) và thông tin phiên bản.

## Chạy và kiểm định cục bộ

Yêu cầu **Node.js `>= 22.22.0`** và **npm `>= 10`**.

```bash
cd website

# 1. Cài đặt từ lockfile
npm ci

# 2. Chạy kiểm tra kiểu Astro + TypeScript strict
npm run check

# 3. Chạy lint nguồn (chống tracker/CDN ngoài & cấm tuyên bố bảo mật tuyệt đối)
npm run lint

# 4. Build sản xuất và kiểm chứng đầu ra dist/ (ARIA, trùng lặp ID, SEO, Sitemap, CNAME)
npm run build
npm run test:dist
```

## Cấu hình Custom Domain `tiendayy.kdns.fr` & GitHub Pages

- Tệp `website/public/CNAME` được sao chép tự động ra `website/dist/CNAME` với giá trị `tiendayy.kdns.fr`.
- Mặc định `astro.config.mjs` và `src/pages/robots.txt.ts` xuất Canonical URL, Open Graph, `robots.txt` và Sitemap theo `https://tiendayy.kdns.fr` (`BASE_PATH=/`).
- Nếu cần kiểm thử xuất bản dưới đường dẫn con (ví dụ `/Zeko`), truyền biến môi trường:
  ```bash
  SITE_URL=https://tienheavenvn.github.io BASE_PATH=/Zeko npm run build
  SITE_URL=https://tienheavenvn.github.io BASE_PATH=/Zeko npm run test:dist
  ```
- Để triển khai tự động qua GitHub Actions Pages tại `https://tiendayy.kdns.fr/`, vào **GitHub Repository Settings → Pages → Build and deployment → Source: GitHub Actions**, sau đó đặt Repository Variable `VEKO_ENABLE_PAGES_DEPLOY=true` (hoặc chạy `workflow_dispatch` với `deploy_pages=true` trên nhánh `main`).
