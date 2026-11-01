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

// Cấm các tuyên bố bảo mật/ẩn danh tuyệt đối không thể chứng minh kỹ thuật
const forbiddenAbsoluteClaims = [
  /100%\s*anonymous/i,
  /\bunhackable\b/i,
  /zero\s+data\s+leakage/i,
  /absolute\s+security/i,
  /100%\s*ẩn\s*danh/i,
  /bảo\s*mật\s*tuyệt\s*đối/i,
  /không\s*thể\s*bị\s*hack/i,
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

  for (const pattern of forbiddenAbsoluteClaims) {
    const match = content.match(pattern);
    if (match) {
      errors.push(`${rel}: Phát hiện tuyên bố tuyệt đối bị cấm "${match[0]}"`);
    }
  }

  // Chỉ cho phép URL tải .apk chính thức từ GitHub Releases của dự án; cấm mọi URL .apk trôi nổi bên ngoài
  const apkUrlMatches = content.match(/https?:\/\/[^\s"'`]+\.apk/gi) ?? [];
  const allowedOfficialApkRegex =
    /^https:\/\/github\.com\/TienHeavenVN\/(?:Veko|Zeko)\/releases\/download\/v[0-9]+\.[0-9]+\.[0-9]+[-A-Za-z0-9.]*\/app-(?:arm64-v8a|armeabi-v7a|x86_64)-release\.apk$/;
  for (const apkUrl of apkUrlMatches) {
    if (!allowedOfficialApkRegex.test(apkUrl)) {
      errors.push(`${rel}: Phát hiện URL tải APK trực tiếp chưa xác minh "${apkUrl}"`);
    }
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
