import fs from 'node:fs';
import path from 'node:path';

const rootDir = path.resolve(import.meta.dirname, '..');
const distDir = path.join(rootDir, 'dist');
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

// 1. Kiểm tra các tệp bắt buộc ở gốc dist/
for (const requiredAsset of [
  'index.html',
  'privacy/index.html',
  '404.html',
  'favicon.svg',
  'og-cover.svg',
  'robots.txt',
  'sitemap-index.xml',
]) {
  if (!fs.existsSync(path.join(distDir, requiredAsset))) {
    errors.push(`Thiếu tệp đầu ra bắt buộc trong dist/: ${requiredAsset}`);
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

  // 2. Kiểm tra Metadata SEO & Open Graph & Accessibility cơ bản
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
  if (!/<link[^>]+rel=["']canonical["']/i.test(html)) {
    errors.push(`${rel}: Thiếu thẻ link canonical`);
  }
  if (!/<main\b[^>]*id=["']main-content["']/i.test(html)) {
    errors.push(`${rel}: Thiếu vùng <main id="main-content">`);
  }
  const h1Matches = [...html.matchAll(/<h1\b/gi)];
  if (h1Matches.length !== 1) {
    errors.push(`${rel}: Phải có đúng 1 thẻ <h1> (hiện có ${h1Matches.length})`);
  }

  // 3. Không tải script từ nguồn ngoài
  for (const scriptMatch of html.matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["']/gi)) {
    const src = scriptMatch[1];
    if (/^https?:\/\//i.test(src)) {
      errors.push(`${rel}: Không được tải script từ tên miền ngoài (${src})`);
    }
  }

  // 4. Kiểm tra toàn bộ thẻ <img> (alt, width, height và sự tồn tại của tệp ảnh)
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

  // 5. Kiểm tra tất cả liên kết nội bộ href (bao gồm cả #anchor)
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
      if (anchor && !idsByFile.get(file)?.has(anchor)) {
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

console.log(`✓ Xác minh dist/ hoàn tất: ${htmlFiles.length} trang HTML, toàn bộ liên kết nội bộ, ảnh, SEO/OG, sitemap và accessibility hợp lệ.`);
