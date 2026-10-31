# Website Giới thiệu Chính thức — Veko Browser (`website/`)

Đây là trang web tĩnh độc lập giới thiệu **Veko Browser**, đặt tại thư mục `website/` và hoàn toàn tách biệt với quy trình biên dịch Gradle/NDK của ứng dụng Android.

## Công nghệ sử dụng

- **Astro 7** (Static Site Generation — xuất HTML/CSS tĩnh tốc độ cao, hạn chế tối đa JavaScript phía trình duyệt)
- **TypeScript** (`strict` mode)
- **Tailwind CSS v4** (`@tailwindcss/vite`)
- **Lucide Icons** (`@lucide/astro`)
- **Sitemap tự động** (`@astrojs/sitemap`)

## Nguyên tắc quyền riêng tư & tính trung thực trên website

- **0 script theo dõi / quảng cáo / analytics**: Không tải Google Fonts, CDN bên thứ ba hay bộ đếm hành vi người dùng.
- **Không tuyên bố phóng đại**: Giải thích rõ dữ liệu nào lưu cục bộ trên máy, cơ chế cô lập tab riêng tư trên RAM và những giới hạn mạng thực tế.
- **Không tạo liên kết tải giả**: Khi dự án chưa công bố bản phát hành trên GitHub Releases, trang web hiển thị rõ trạng thái *"Đang phát triển"* kèm hướng dẫn tự biên dịch từ mã nguồn.

## Hướng dẫn cài đặt & chạy cục bộ

Yêu cầu **Node.js `>= 22.22.0`** và **npm `>= 10`**.

```bash
cd website

# 1. Cài đặt dependencies đúng theo package-lock.json
npm ci

# 2. Chạy máy chủ phát triển cục bộ (mặc định http://localhost:4321)
npm run dev

# 3. Kiểm tra kiểu TypeScript & cú pháp Astro
npm run check

# 4. Chạy bộ kiểm tra quy tắc nguồn (chống tracking/CDN ngoài & liên kết giả)
npm run lint

# 5. Xuất bản dựng tĩnh sản xuất ra website/dist/
npm run build

# 6. Kiểm tra toàn diện đầu ra dist/ (liên kết nội bộ, anchor, ảnh, SEO/OG, accessibility)
npm run test:dist

# Hoặc chạy trọn gói toàn bộ các bước kiểm định trong 1 lệnh:
npm run verify
```

## Cấu hình URL & Deploy lên GitHub Pages

Website hỗ trợ cấu hình động qua biến môi trường khi build:

- `SITE_URL`: Tên miền gốc (mặc định `https://tienheavenvn.github.io`)
- `BASE_PATH`: Đường dẫn con nếu deploy dưới dạng Project Pages (ví dụ `BASE_PATH=/Zeko`)

Ví dụ build cho GitHub Pages của repository `TienHeavenVN/Zeko`:

```bash
SITE_URL=https://tienheavenvn.github.io BASE_PATH=/Zeko npm run build
```

Workflow tự động tại `.github/workflows/website.yml` thực thi kiểm tra `npm ci`, `npm run check`, `npm run lint`, `npm run build` và `npm run test:dist` trên mỗi lần push/PR mà **không tự động deploy** khi chủ kho lưu trữ chưa bật GitHub Pages trong phần cài đặt repository. Khi đã bật GitHub Pages (Source: *GitHub Actions*), có thể kích hoạt deploy chủ động qua `workflow_dispatch` trên nhánh `main` hoặc đặt biến repository `VEKO_ENABLE_PAGES_DEPLOY=true`.
