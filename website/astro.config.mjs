import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';
import tailwindcss from '@tailwindcss/vite';

// Mặc định sử dụng tên miền chính thức của dự án (tiendayy.kdns.fr), vẫn cho phép ghi đè qua biến môi trường khi kiểm thử subpath.
const siteUrl = process.env.SITE_URL || 'https://tiendayy.kdns.fr';
const basePath = process.env.BASE_PATH || '/';

export default defineConfig({
  site: siteUrl,
  base: basePath,
  trailingSlash: 'always',
  output: 'static',
  compressHTML: true,
  integrations: [sitemap()],
  vite: {
    plugins: [tailwindcss()],
    server: {
      allowedHosts: true,
    },
    preview: {
      allowedHosts: true,
    },
  },
});
