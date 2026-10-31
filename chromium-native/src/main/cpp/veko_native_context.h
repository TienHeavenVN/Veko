#ifndef VEKO_CHROMIUM_NATIVE_CONTEXT_H_
#define VEKO_CHROMIUM_NATIVE_CONTEXT_H_

#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace veko::chromium {

enum class NativeCookieMode : int32_t {
  kBlockThirdParty = 0,
  kBlockAll = 1,
  kAllowAll = 2,
};

struct BrowserContextSnapshot {
  int64_t handle = 0;
  bool is_off_the_record = false;
  std::string storage_partition_path;
  size_t cookie_count = 0;
};

/**
 * Quản lý vòng đời BrowserContext và StoragePartition ở tầng C++:
 * - Tách biệt cứng giữa Regular BrowserContext (có đường dẫn đĩa) và Off-The-Record BrowserContext (100% RAM, path rỗng).
 * - Áp đặt kiểm soát Cookie theo trang gốc (Same-Site vs Third-Party) và hủy sạch phân vùng nhớ khi đóng phiên riêng tư.
 */
class NativeBrowserContextRegistry {
 public:
  static NativeBrowserContextRegistry& Instance();

  int64_t CreateContext(bool is_off_the_record, const std::string& requested_disk_path);
  bool Exists(int64_t handle) const;
  bool IsOffTheRecord(int64_t handle) const;
  std::string GetStoragePath(int64_t handle) const;

  bool SetCookie(int64_t handle,
                 const std::string& request_url,
                 const std::string& top_frame_url,
                 const std::string& cookie_kv,
                 NativeCookieMode mode);

  std::string GetCookies(int64_t handle,
                         const std::string& request_url,
                         const std::string& top_frame_url,
                         NativeCookieMode mode) const;

  bool ClearStoragePartition(int64_t handle);
  bool DestroyContext(int64_t handle);
  size_t ActiveOffTheRecordContextCount() const;

  static std::string ExtractRegistrableSiteKey(const std::string& raw_url);
  static bool IsThirdPartyRequest(const std::string& request_url,
                                  const std::string& top_frame_url);

 private:
  NativeBrowserContextRegistry() = default;

  struct ContextEntry {
    int64_t handle = 0;
    bool is_off_the_record = false;
    std::string storage_path;
    // Khóa phân vùng: registrable_site -> (cookie_name -> cookie_value)
    std::map<std::string, std::map<std::string, std::string>> cookies_by_host;
  };

  mutable std::mutex mutex_;
  int64_t next_handle_ = 1000;
  std::map<int64_t, ContextEntry> contexts_;
};

}  // namespace veko::chromium

#endif  // VEKO_CHROMIUM_NATIVE_CONTEXT_H_
