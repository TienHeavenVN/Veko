# Website Chính thức — Veko Browser (`website/`)

Trang giới thiệu sản phẩm chính thức cho **Veko Browser** tại tên miền **`https://tiendayy.kdns.fr/`**, được thiết kế theo ngôn ngữ **"Abyssal Precision"** và tách biệt hoàn toàn với hệ thống build Gradle/NDK của ứng dụng Android.

## Công nghệ sử dụng

- **Astro `7.4.1`** (Static Site Generation — xuất HTML/CSS tĩnh tốc độ cao, không kèm runtime framework nặng phía trình duyệt)
- **TypeScript `5.9.x`** (`strict` mode)
- **Tailwind CSS v4** (`@tailwindcss/vite`, hệ thống CSS Custom Properties hỗ trợ đồng thời **Dark Theme mặc định** và **Light Theme** chuẩn tương phản WCAG AA)
- **Lucide Icons** (`@lucide/astro`)
- **Sitemap & Structured Data** (`@astrojs/sitemap` + JSON-LD `SoftwareApplication`)

## Cấu trúc các phân mục trên Website

1. **Navigation (`Navbar.astro`)**: Logo Veko, menu điều hướng, nút chuyển đổi giao diện Sáng/Tối không nhấp nháy (zero-FOUC), liên kết GitHub, nút Tải về và ngăn menu di động.
2. **Hero (`HeroSection.astro`)**: Lưới tọa độ kỹ thuật, thông điệp giá trị cốt lõi, dải thông số kiến trúc thực tế và khung thiết bị Android có chiều sâu.
3. **Product Showcase (`ProductShowcaseSection.astro`)**: Bảng chuyển đổi tương tác 5 luồng thao tác thực tế (`role="tablist"` hỗ trợ bàn phím): Thanh địa chỉ cạnh dưới, Quản lý Tab LRU, Duyệt riêng tư trên RAM, Lá chắn nội dung Rust FFI, và Phân quyền theo Origin.
4. **Features (`FeaturesSection.astro`)**: Bố cục Asymmetric Bento 5 khối với đồ họa trực quan riêng cho từng tính năng (không lặp lại các thẻ giống hệt nhau).
5. **Privacy & Security (`PrivacySection.astro` + `/privacy/`)**: 4 trụ cột kỹ thuật, bảng đối chiếu phạm vi *Tab thường vs. Tab Riêng tư vs. Mạng* và hộp công bố giới hạn thực tế.
6. **Technology (`TechnologySection.astro`)**: Tách bạch rõ công nghệ đang vận hành thực tế (Mozilla GeckoView, Kotlin Compose, `adblock-rust` + C++20 JNI) và lộ trình kỹ thuật đang phát triển (hợp đồng nhúng Chromium Content) cùng bản đồ 9 module Gradle.
7. **Screenshots (`ScreenshotsSection.astro`)**: 4 màn hình dọc `360×760` sắc nét kèm liên kết minh bạch tới ảnh chụp máy ảo Android 14 trên CI.
8. **Download (`DownloadSection.astro`)**: Hiển thị chính xác trạng thái phát hành (`0` GitHub Releases chính thức), hướng dẫn tự biên dịch có nút sao chép 1 chạm và bảng so sánh 3 biến thể APK (`debug`, `preview`, `release`).
9. **FAQ (`FaqSection.astro`)**: 6 câu hỏi thường gặp sử dụng thẻ ngữ nghĩa `<details>/<summary>`.
10. **Footer (`Footer.astro`)**: GitHub, Documentation, Chính sách Quyền riêng tư, thông tin License và Phiên bản (`0.2.0-chromium-arch`).

## Chạy và kiểm định cục bộ

Yêu cầu **Node.js `>= 22.22.0`** và **npm `>= 10`**.

```bash
cd website

# 1. Cài đặt từ lockfile
npm ci

# 2. Chạy máy chủ phát triển (http://localhost:4321)
npm run dev

# 3. Chạy trọn gói kiểm tra kiểu, lint nguồn, build sản xuất và kiểm chứng đầu ra dist/
npm run verify
```

## Cấu hình Custom Domain `tiendayy.kdns.fr` & GitHub Pages

- Tệp `website/public/CNAME` được sao chép tự động ra `website/dist/CNAME` với giá trị `tiendayy.kdns.fr`.
- Mặc định `astro.config.mjs` xuất Canonical URL, Open Graph và Sitemap theo `https://tiendayy.kdns.fr` (`BASE_PATH=/`).
- Nếu cần kiểm thử xuất bản dưới đường dẫn con (ví dụ `/Zeko`), truyền biến môi trường:
  ```bash
  SITE_URL=https://tienheavenvn.github.io BASE_PATH=/Zeko npm run build
  SITE_URL=https://tienheavenvn.github.io BASE_PATH=/Zeko npm run test:dist
  ```
- Để trang Astro thay thế bản render `README.md` mặc định tại `https://tiendayy.kdns.fr/`, vào **GitHub Repository Settings → Pages → Build and deployment → Source: GitHub Actions**, sau đó đặt Repository Variable `VEKO_ENABLE_PAGES_DEPLOY=true` (hoặc chạy `workflow_dispatch` với `deploy_pages=true` trên nhánh `main`).
