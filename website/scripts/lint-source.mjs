import fs from 'node:fs';
import path from 'node:path';

const rootDir = path.resolve(import.meta.dirname, '..');
const srcDir = path.join(rootDir, 'src');

function collectFiles(dir, exts) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...collectFiles(full, exts));
    } else if (exts.some((ext) => entry.name.endsWith(ext))) {
      files.push(full);
    }
  }
  return files;
}

const files = collectFiles(srcDir, ['.astro', '.ts', '.css']);
const errors = [];

const forbiddenExternalResources = [
  'googletagmanager.com',
  'google-analytics.com',
  'plausible.io',
  'fonts.googleapis.com',
  'fonts.gstatic.com',
  'unpkg.com',
  'jsdelivr.net',
];

const forbiddenStoreClaims = [
  'play.google.com/store',
  'f-droid.org/packages',
];

for (const file of files) {
  const rel = path.relative(rootDir, file);
  const content = fs.readFileSync(file, 'utf8');

  for (const token of forbiddenExternalResources) {
    if (content.includes(token)) {
      errors.push(`${rel}: Phát hiện tài nguyên/tracking bên ngoài không được phép "${token}"`);
    }
  }

  for (const store of forbiddenStoreClaims) {
    if (content.includes(store)) {
      errors.push(`${rel}: Phát hiện liên kết cửa hàng ứng dụng chưa xác minh "${store}"`);
    }
  }

  // Không được chứa URL tải .apk giả mạo
  const fakeApkUrlMatch = content.match(/https?:\/\/[^\s"'`]+\.apk/i);
  if (fakeApkUrlMatch) {
    errors.push(`${rel}: Phát hiện URL tải APK trực tiếp chưa xác minh "${fakeApkUrlMatch[0]}"`);
  }
}

if (errors.length > 0) {
  console.error('Lint website thất bại:');
  for (const err of errors) {
    console.error(`  - ${err}`);
  }
  process.exit(1);
}

console.log(`✓ Lint nguồn website thành công (${files.length} tệp đã kiểm tra, 0 lỗi).`);
