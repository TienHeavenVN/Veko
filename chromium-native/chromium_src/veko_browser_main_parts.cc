#include "veko_browser_main_parts.h"

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include "base/files/file_path.h"
#include "base/path_service.h"
#include "build/build_config.h"
#include "content/public/browser/android/compositor.h"
#include "content/public/common/result_codes.h"
#include "veko_content_browser_client.h"

#if BUILDFLAG(IS_ANDROID)
#include "components/crash/content/browser/child_exit_observer_android.h"
#include "components/crash/content/browser/child_process_crash_observer_android.h"
#include "net/android/network_change_notifier_factory_android.h"
#include "net/base/network_change_notifier.h"
#endif

namespace veko::embedder {

VekoBrowserMainParts::VekoBrowserMainParts() = default;
VekoBrowserMainParts::~VekoBrowserMainParts() = default;

int VekoBrowserMainParts::PreEarlyInitialization() {
#if BUILDFLAG(IS_ANDROID)
  net::NetworkChangeNotifier::SetFactory(
      new net::NetworkChangeNotifierFactoryAndroid());
#endif
  return content::RESULT_CODE_NORMAL_EXIT;
}

int VekoBrowserMainParts::PreCreateThreads() {
#if BUILDFLAG(IS_ANDROID)
  child_exit_observer_ = std::make_unique<crash_reporter::ChildExitObserver>();
  base::FilePath crash_dump_dir;
  if (base::PathService::Get(base::DIR_ANDROID_APP_DATA, &crash_dump_dir)) {
    child_exit_observer_->RegisterClient(
        std::make_unique<crash_reporter::ChildProcessCrashObserver>(
            crash_dump_dir.Append(FILE_PATH_LITERAL("crash_dumps")), 0));
  }
#endif
  return content::RESULT_CODE_NORMAL_EXIT;
}

int VekoBrowserMainParts::PreMainMessageLoopRun() {
#if BUILDFLAG(IS_ANDROID)
  content::Compositor::Initialize();
#endif
  base::FilePath app_data_dir;
  base::PathService::Get(base::DIR_ANDROID_APP_DATA, &app_data_dir);
  const base::FilePath profile_dir =
      app_data_dir.Append(FILE_PATH_LITERAL("veko_chromium_profile"));
  regular_browser_context_ =
      std::make_unique<VekoBrowserContext>(profile_dir, /*is_off_the_record=*/false);
  return content::RESULT_CODE_NORMAL_EXIT;
}

void VekoBrowserMainParts::PostMainMessageLoopRun() {
  if (regular_browser_context_) {
    regular_browser_context_->DestroyOffTheRecordContext();
    regular_browser_context_.reset();
  }
}

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
