import fs from 'node:fs';
import path from 'node:path';

const rootDir = path.resolve(import.meta.dirname, '..');
const distDir = path.join(rootDir, 'dist');
const expectedOrigin = (process.env.SITE_URL || 'https://tiendayy.kdns.fr').replace(/\/+$/, '');
const rawBase = process.env.BASE_PATH || '/';
const normalizedBase = rawBase === '/' ? '' : `/${rawBase.replace(/^\/+|\/+$/g, '')}`;

if (!fs.existsSync(distDir)) {
  console.error('Không tìm thấy thư mục build dist/. Hãy chạy npm run build trước.');
  process.exit(1);
}

function collectHtmlFiles(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...collectHtmlFiles(full));
    } else if (entry.name.endsWith('.html')) {
      out.push(full);
    }
  }
  return out;
}

function stripBasePrefix(urlPath) {
  if (normalizedBase && urlPath.startsWith(normalizedBase)) {
    const rest = urlPath.slice(normalizedBase.length);
    return rest.startsWith('/') ? rest : `/${rest}`;
  }
  return urlPath;
}

function resolveDistTarget(localPath) {
  const clean = stripBasePrefix(localPath);
  const direct = path.join(distDir, clean);
  if (fs.existsSync(direct) && fs.statSync(direct).isFile()) {
    return direct;
  }
  const indexHtml = path.join(distDir, clean, 'index.html');
  if (fs.existsSync(indexHtml)) {
    return indexHtml;
  }
  const dotHtml = `${direct.replace(/\/+$/, '')}.html`;
  if (fs.existsSync(dotHtml)) {
    return dotHtml;
  }
  return null;
}

const errors = [];

// 1. Kiểm tra các tệp đầu ra bắt buộc trong dist/ (bao gồm CNAME cho tên miền tiendayy.kdns.fr)
for (const requiredAsset of [
  'index.html',
  'privacy/index.html',
  '404.html',
  'CNAME',
  'favicon.svg',
  'og-cover.svg',
  'robots.txt',
  'sitemap-index.xml',
  'screenshots/veko-home-refactored.svg',
  'screenshots/veko-tab-switcher.svg',
  'screenshots/veko-private-mode.svg',
  'screenshots/veko-privacy-matrix.svg',
]) {
  if (!fs.existsSync(path.join(distDir, requiredAsset))) {
    errors.push(`Thiếu tệp đầu ra bắt buộc trong dist/: ${requiredAsset}`);
  }
}

const cnamePath = path.join(distDir, 'CNAME');
if (fs.existsSync(cnamePath)) {
  const cnameValue = fs.readFileSync(cnamePath, 'utf8').trim();
  if (cnameValue !== 'tiendayy.kdns.fr') {
    errors.push(`Tệp dist/CNAME không khớp tên miền mong đợi: "${cnameValue}"`);
  }
}

const robotsPath = path.join(distDir, 'robots.txt');
if (fs.existsSync(robotsPath)) {
  const robotsText = fs.readFileSync(robotsPath, 'utf8');
  if (!robotsText.includes('https://tiendayy.kdns.fr/sitemap-index.xml')) {
    errors.push('robots.txt phải trỏ Sitemap tới https://tiendayy.kdns.fr/sitemap-index.xml');
  }
}

const htmlFiles = collectHtmlFiles(distDir);
const idsByFile = new Map();

for (const file of htmlFiles) {
  const html = fs.readFileSync(file, 'utf8');
  const ids = new Set();
  for (const m of html.matchAll(/\bid=["']([^"']+)["']/g)) {
    ids.add(m[1]);
  }
  idsByFile.set(file, ids);
}

for (const file of htmlFiles) {
  const rel = path.relative(distDir, file);
  const html = fs.readFileSync(file, 'utf8');
  const pageIds = idsByFile.get(file) ?? new Set();

  // 2. Kiểm tra Metadata SEO, Canonical URL, Open Graph, JSON-LD & Accessibility Landmarks
  if (!/<html[^>]*\blang=["']vi["']/i.test(html)) {
    errors.push(`${rel}: Thiếu thuộc tính lang="vi" trên thẻ <html>`);
  }
  if (!/<title>[^<]{5,}<\/title>/i.test(html)) {
    errors.push(`${rel}: Thiếu thẻ <title> hợp lệ`);
  }
  if (!/<meta[^>]+name=["']description["'][^>]+content=["'][^"']{15,}["']/i.test(html)) {
    errors.push(`${rel}: Thiếu thẻ meta description`);
  }
  if (!/<meta[^>]+property=["']og:title["']/i.test(html) || !/<meta[^>]+property=["']og:image["']/i.test(html)) {
    errors.push(`${rel}: Thiếu thẻ Open Graph (og:title / og:image)`);
  }

  const canonicalMatch = html.match(/<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)["']/i)
    || html.match(/<link[^>]+href=["']([^"']+)["'][^>]+rel=["']canonical["']/i);
  if (!canonicalMatch) {
    errors.push(`${rel}: Thiếu thẻ link canonical`);
  } else if (!canonicalMatch[1].startsWith(`${expectedOrigin}/`)) {
    errors.push(`${rel}: Canonical URL "${canonicalMatch[1]}" không bắt đầu bằng "${expectedOrigin}/"`);
  }

  const ldJsonMatch = html.match(/<script[^>]+type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/i);
  if (!ldJsonMatch) {
    errors.push(`${rel}: Thiếu dữ liệu có cấu trúc JSON-LD`);
  } else {
    try {
      JSON.parse(ldJsonMatch[1]);
    } catch (_err) {
      errors.push(`${rel}: JSON-LD không hợp lệ`);
    }
  }

  if (!/<header\b/i.test(html) || !/<nav\b/i.test(html) || !/<footer\b/i.test(html)) {
    errors.push(`${rel}: Thiếu landmark ngữ nghĩa (<header>, <nav> hoặc <footer>)`);
  }
  if (!/<main\b[^>]*id=["']main-content["']/i.test(html)) {
    errors.push(`${rel}: Thiếu vùng <main id="main-content">`);
  }
  const h1Matches = [...html.matchAll(/<h1\b/gi)];
  if (h1Matches.length !== 1) {
    errors.push(`${rel}: Phải có đúng 1 thẻ <h1> (hiện có ${h1Matches.length})`);
  }

  // Kiểm tra liên kết ARIA nội bộ (aria-controls, aria-labelledby)
  for (const ariaAttr of ['aria-controls', 'aria-labelledby']) {
    const regex = new RegExp(`\\b${ariaAttr}=["']([^"']+)["']`, 'gi');
    for (const m of html.matchAll(regex)) {
      for (const refId of m[1].trim().split(/\s+/)) {
        if (refId && !pageIds.has(refId)) {
          errors.push(`${rel}: Thuộc tính ${ariaAttr}="${refId}" trỏ tới id không tồn tại`);
        }
      }
    }
  }

  // 3. Không tải script từ tên miền bên ngoài
  for (const scriptMatch of html.matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["']/gi)) {
    const src = scriptMatch[1];
    if (/^https?:\/\//i.test(src)) {
      errors.push(`${rel}: Không được tải script từ tên miền ngoài (${src})`);
    }
  }

  // 4. Kiểm tra toàn bộ thẻ <img> (alt, width, height và sự tồn tại tệp ảnh)
  for (const imgMatch of html.matchAll(/<img\b([^>]+)>/gi)) {
    const attrs = imgMatch[1];
    const srcMatch = attrs.match(/\bsrc=["']([^"']+)["']/i);
    const altMatch = attrs.match(/\balt=["']([^"']+)["']/i);
    const wMatch = attrs.match(/\bwidth=["'](\d+)["']/i);
    const hMatch = attrs.match(/\bheight=["'](\d+)["']/i);

    if (!srcMatch) {
      errors.push(`${rel}: Thẻ <img> thiếu src`);
      continue;
    }
    if (!altMatch || altMatch[1].trim().length < 5) {
      errors.push(`${rel}: Thẻ <img src="${srcMatch[1]}"> thiếu mô tả alt rõ ràng`);
    }
    if (!wMatch || !hMatch) {
      errors.push(`${rel}: Thẻ <img src="${srcMatch[1]}"> thiếu kích thước width/height tường minh`);
    }
    if (!/^https?:\/\//i.test(srcMatch[1]) && !resolveDistTarget(srcMatch[1])) {
      errors.push(`${rel}: Không tìm thấy tệp ảnh cục bộ "${srcMatch[1]}" trong dist/`);
    }
  }

  // 5. Kiểm tra toàn bộ liên kết nội bộ href & #anchor
  for (const linkMatch of html.matchAll(/<(?:a|link)\b[^>]*\bhref=["']([^"']+)["']/gi)) {
    const href = linkMatch[1];
    if (
      href.startsWith('http://') ||
      href.startsWith('https://') ||
      href.startsWith('mailto:')
    ) {
      continue;
    }

    if (href.startsWith('#')) {
      const anchor = href.slice(1);
      if (anchor && !pageIds.has(anchor)) {
        errors.push(`${rel}: Anchor nội bộ "${href}" không khớp với bất kỳ id nào trên trang`);
      }
      continue;
    }

    const [pathPart, hashPart] = href.split('#');
    const targetFile = resolveDistTarget(pathPart || '/');
    if (!targetFile) {
      errors.push(`${rel}: Liên kết nội bộ bị gãy "${href}"`);
      continue;
    }
    if (hashPart && targetFile.endsWith('.html')) {
      const targetIds = idsByFile.get(targetFile);
      if (targetIds && !targetIds.has(hashPart)) {
        errors.push(`${rel}: Liên kết "${href}" trỏ tới id "#${hashPart}" không tồn tại trong ${path.relative(distDir, targetFile)}`);
      }
    }
  }
}

if (errors.length > 0) {
  console.error('Kiểm tra đầu ra dist/ thất bại:');
  for (const err of errors) {
    console.error(`  - ${err}`);
  }
  process.exit(1);
}

console.log(`✓ Xác minh dist/ hoàn tất: ${htmlFiles.length} trang HTML, CNAME (tiendayy.kdns.fr), liên kết nội bộ, ARIA, ảnh, JSON-LD, SEO/OG và sitemap hợp lệ.`);
