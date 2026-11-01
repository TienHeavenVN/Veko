#!/usr/bin/env bash
set -euo pipefail

echo "=== Android Emulator Ready ==="
adb devices -l
echo "SDK API Level: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "CPU ABI: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"

# Tắt hoạt ảnh hệ thống, hộp thoại lỗi ANR nền (Pixel Launcher), bàn phím ảo và đóng hộp thoại khởi động
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true
adb shell settings put secure show_ime_with_hard_keyboard 0 || true
adb shell wm dismiss-keyguard || true
adb shell input keyevent 82 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true

mkdir -p app/build/outputs/emulator-diagnostics
adb logcat -G 16M || true
adb logcat -c || true

if ! ./gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=2 2>&1 | tee connected-test.log; then
  adb logcat -d -t 300 > app/build/outputs/emulator-diagnostics/emulator-logcat.txt || true
  ERR_SUMMARY=$(awk '/FAILURE:/,0' connected-test.log | head -n 45 | tr '\n' ' ')
  if [ -z "$ERR_SUMMARY" ]; then
    ERR_SUMMARY=$(tail -n 45 connected-test.log | tr '\n' ' ')
  fi
  FAILED_TESTS=$(python3 - << 'PY' || true
import glob, xml.etree.ElementTree as ET
msgs = []
for p in sorted(glob.glob("app/build/outputs/androidTest-results/**/*.xml", recursive=True)):
    r = ET.parse(p).getroot()
    for tc in r.iter("testcase"):
        for fail in list(tc.findall("failure")) + list(tc.findall("error")):
            raw = " ".join(((fail.attrib.get("message") or "") + " " + (fail.text or "")).split())
            msgs.append(f"{tc.attrib.get('name')}: {raw[:320]}")
print(" | ".join(msgs))
PY
)
  echo "::error title=Emulator Instrumentation Failure::${FAILED_TESTS} ${ERR_SUMMARY}"
  exit 1
fi

EMULATOR_SUMMARY=$(python3 - << 'PY'
import glob, os, xml.etree.ElementTree as ET

paths = sorted(glob.glob("app/build/outputs/androidTest-results/connected/**/TEST-*.xml", recursive=True))
assert paths, "No connectedDebugAndroidTest XML report found!"

total_tests = total_fail = total_err = total_skip = 0
cases = []
for p in paths:
    root = ET.parse(p).getroot()
    total_tests += int(root.attrib.get("tests", 0))
    total_fail += int(root.attrib.get("failures", 0))
    total_err += int(root.attrib.get("errors", 0))
    total_skip += int(root.attrib.get("skipped", 0))
    for tc in root.iter("testcase"):
        cases.append((tc.attrib.get("classname", ""), tc.attrib.get("name", ""), tc.attrib.get("time", "0")))

assert total_tests >= 3 and total_fail == 0 and total_err == 0, (
    f"Unexpected test counts: tests={total_tests}, failures={total_fail}, errors={total_err}"
)

summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
if summary_file:
    with open(summary_file, "a", encoding="utf-8") as f:
        f.write("## 📱 Veko Browser — Kết quả Kiểm thử trên Android Emulator (API 34 x86_64)\n\n")
        f.write(f"- **Tổng số bài kiểm thử thực thi:** `{total_tests}` passed (`0` failures, `0` errors)\n\n")
        f.write("| Test Class | Test Case | Thời gian (s) |\n")
        f.write("| :--- | :--- | :---: |\n")
        for cls, name, dur in cases:
            f.write(f"| `{cls}` | `{name}` | `{dur}` |\n")

case_names = ", ".join(f"{name} ({dur}s)" for _, name, dur in cases)
print(f"Total {total_tests} instrumented tests passed on Android Emulator (0 failures, 0 errors): {case_names}")
PY
)

# Thu thập dung lượng bộ nhớ thực tế và ảnh chụp giao diện thực từ Emulator
MEM_LOG_LINE=$(adb logcat -d 2>/dev/null | grep "VekoMetrics" | tail -n 1 | sed -E 's/.*VekoMetrics:[[:space:]]*//' | tr -d '\r' || true)
mkdir -p app/build/outputs/emulator-diagnostics/screenshots
for shot in veko_ci_home_empty.png veko_ci_home_shortcuts.png veko_ci_privacy_shield.png veko_ci_tab_switcher.png veko_ci_home_private.png veko_ci_dual_engine_settings.png veko_ci_icon_512.png; do
  adb pull "/sdcard/Download/${shot}" "app/build/outputs/emulator-diagnostics/screenshots/${shot}" >/dev/null 2>&1 || true
done

echo "::notice title=Android Emulator Instrumented Tests Verified::${EMULATOR_SUMMARY} | ${MEM_LOG_LINE:-RUNTIME_METRICS_CAPTURED}"
