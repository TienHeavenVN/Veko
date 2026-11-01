#include "veko_main_delegate.h"

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <utility>

#include "base/android/apk_assets.h"
#include "base/android/jni_android.h"
#include "base/android/library_loader/library_loader_hooks.h"
#include "base/files/file.h"
#include "base/files/file_path.h"
#include "base/files/memory_mapped_file.h"
#include "base/logging.h"
#include "base/path_service.h"
#include "base/posix/global_descriptors.h"
#include "content/public/app/content_jni_onload.h"
#include "content/public/app/content_main.h"
#include "ui/base/resource/resource_bundle.h"
#include "ui/base/resource/resource_scale_factor.h"

namespace veko::embedder {
namespace {

bool OnAndroidNativeInitialization(
    base::android::LibraryProcessType /*library_process_type*/) {
  if (!content::android::OnJNIOnLoadInit()) {
    return false;
  }
  content::SetContentMainDelegate(new VekoMainDelegate());
  return true;
}

}  // namespace

std::u16string VekoContentClient::GetLocalizedString(int message_id) {
  return ui::ResourceBundle::GetSharedInstance().GetLocalizedString(message_id);
}

base::StringPiece VekoContentClient::GetDataResource(
    int resource_id,
    ui::ResourceScaleFactor scale_factor) {
  return ui::ResourceBundle::GetSharedInstance().GetRawDataResourceForScale(
      resource_id, scale_factor);
}

base::RefCountedMemory* VekoContentClient::GetDataResourceBytes(
    int resource_id) {
  return ui::ResourceBundle::GetSharedInstance().LoadDataResourceBytes(
      resource_id);
}

VekoMainDelegate::VekoMainDelegate() = default;
VekoMainDelegate::~VekoMainDelegate() = default;

std::optional<int> VekoMainDelegate::BasicStartupComplete() {
  return std::nullopt;
}

void VekoMainDelegate::PreSandboxStartup() {
  if (!InitializeResourceBundle()) {
    LOG(ERROR) << "VekoMainDelegate: Failed to initialize veko_content.pak resource bundle.";
  }
}

std::optional<int> VekoMainDelegate::PreBrowserMain() {
  return content::ContentMainDelegate::PreBrowserMain();
}

bool VekoMainDelegate::InitializeResourceBundle() {
#if BUILDFLAG(IS_ANDROID)
  auto* global_descriptors = base::GlobalDescriptors::GetInstance();
  int pak_fd = global_descriptors->MaybeGet(kVekoContentPakDescriptor);
  base::MemoryMappedFile::Region pak_region;
  if (pak_fd >= 0) {
    pak_region = global_descriptors->GetRegion(kVekoContentPakDescriptor);
  } else {
    pak_fd = base::android::OpenApkAsset("assets/veko_content.pak", &pak_region);
    if (pak_fd < 0) {
      return false;
    }
    global_descriptors->Set(kVekoContentPakDescriptor, pak_fd, pak_region);
  }
  base::File android_pak_file(pak_fd);
  ui::ResourceBundle::InitSharedInstanceWithPakFileRegion(
      android_pak_file.Duplicate(), pak_region);
  ui::ResourceBundle::GetSharedInstance().AddDataPackFromFileRegion(
      std::move(android_pak_file), pak_region, ui::k100Percent);
  return true;
#else
  base::FilePath pak_file;
  if (!base::PathService::Get(base::DIR_ASSETS, &pak_file)) {
    return false;
  }
  pak_file = pak_file.Append(FILE_PATH_LITERAL("veko_content.pak"));
  ui::ResourceBundle::InitSharedInstanceWithPakPath(pak_file);
  return true;
#endif
}

content::ContentClient* VekoMainDelegate::CreateContentClient() {
  content_client_ = std::make_unique<VekoContentClient>();
  return content_client_.get();
}

content::ContentBrowserClient* VekoMainDelegate::CreateContentBrowserClient() {
  browser_client_ = std::make_unique<VekoContentBrowserClient>();
  return browser_client_.get();
}

content::ContentRendererClient* VekoMainDelegate::CreateContentRendererClient() {
  renderer_client_ = std::make_unique<VekoContentRendererClient>();
  return renderer_client_.get();
}

bool RegisterVekoContentMainOnLoad(JavaVM* vm) {
  base::android::InitVM(vm);
  base::android::SetNativeInitializationHook(OnAndroidNativeInitialization);
  return true;
}

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
