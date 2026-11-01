import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';
import tailwindcss from '@tailwindcss/vite';

// Mặc định sử dụng tên miền chính thức của dự án (veko.kdns.fr), vẫn cho phép ghi đè qua biến môi trường SITE_URL / BASE_PATH khi kiểm thử.
const siteUrl = (process.env.SITE_URL || 'https://veko.kdns.fr').replace(/\/+$/, '');
const basePath = process.env.BASE_PATH || '/';

export default defineConfig({
  site: siteUrl,
  base: basePath,
  trailingSlash: 'always',
  output: 'static',
  compressHTML: true,
  integrations: [
    sitemap({
      filter: (page) => !page.endsWith('/404/') && !page.endsWith('/404'),
    }),
  ],
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
