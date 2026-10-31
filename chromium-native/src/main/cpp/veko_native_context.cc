#include "veko_native_context.h"

#include <algorithm>
#include <cctype>
#include <sstream>

namespace veko::chromium {
namespace {

std::string ToLowerAscii(std::string input) {
  std::transform(input.begin(), input.end(), input.begin(), [](unsigned char c) {
    return static_cast<char>(std::tolower(c));
  });
  return input;
}

std::string ExtractHost(const std::string& raw_url) {
  const auto scheme_pos = raw_url.find("://");
  const size_t start = (scheme_pos == std::string::npos) ? 0 : scheme_pos + 3;
  if (start >= raw_url.size()) return "";

  size_t end = raw_url.find_first_of("/?#", start);
  if (end == std::string::npos) end = raw_url.size();

  std::string authority = raw_url.substr(start, end - start);
  const auto at_pos = authority.rfind('@');
  if (at_pos != std::string::npos) {
    authority = authority.substr(at_pos + 1);
  }
  if (!authority.empty() && authority.front() == '[') {
    const auto bracket_end = authority.find(']');
    if (bracket_end != std::string::npos) {
      return ToLowerAscii(authority.substr(0, bracket_end + 1));
    }
  }
  const auto colon_pos = authority.find(':');
  if (colon_pos != std::string::npos) {
    authority = authority.substr(0, colon_pos);
  }
  return ToLowerAscii(authority);
}

bool IsIpLiteral(const std::string& host) {
  if (host.empty()) return false;
  if (host.front() == '[') return true;
  return std::all_of(host.begin(), host.end(), [](unsigned char c) {
    return std::isdigit(c) || c == '.';
  });
}

}  // namespace

NativeBrowserContextRegistry& NativeBrowserContextRegistry::Instance() {
  static NativeBrowserContextRegistry registry;
  return registry;
}

std::string NativeBrowserContextRegistry::ExtractRegistrableSiteKey(const std::string& raw_url) {
  std::string host = ExtractHost(raw_url);
  if (host.empty() || IsIpLiteral(host)) return host;

  std::vector<std::string> labels;
  std::stringstream ss(host);
  std::string part;
  while (std::getline(ss, part, '.')) {
    if (!part.empty()) labels.push_back(part);
  }
  if (labels.size() <= 2) return host;
  // Ghép 2 nhãn cuối (hoặc 3 nhãn nếu thuộc nhóm eTLD phổ biến dạng .co.uk / .com.vn)
  const std::string& second_last = labels[labels.size() - 2];
  if (labels.size() >= 3 &&
      (second_last == "co" || second_last == "com" || second_last == "org" ||
       second_last == "net" || second_last == "gov" || second_last == "edu") &&
      labels.back().size() == 2) {
    return labels[labels.size() - 3] + "." + second_last + "." + labels.back();
  }
  return second_last + "." + labels.back();
}

bool NativeBrowserContextRegistry::IsThirdPartyRequest(const std::string& request_url,
                                                       const std::string& top_frame_url) {
  if (top_frame_url.empty()) return false;
  const std::string req_site = ExtractRegistrableSiteKey(request_url);
  const std::string top_site = ExtractRegistrableSiteKey(top_frame_url);
  if (req_site.empty() || top_site.empty()) return false;
  return req_site != top_site;
}

int64_t NativeBrowserContextRegistry::CreateContext(bool is_off_the_record,
                                                    const std::string& requested_disk_path) {
  std::lock_guard<std::mutex> lock(mutex_);
  const int64_t id = ++next_handle_;
  ContextEntry entry;
  entry.handle = id;
  entry.is_off_the_record = is_off_the_record;
  // Bắt buộc Off-The-Record BrowserContext không bao giờ có đường dẫn ghi đĩa
  entry.storage_path = is_off_the_record ? "" : requested_disk_path;
  contexts_.emplace(id, std::move(entry));
  return id;
}

bool NativeBrowserContextRegistry::Exists(int64_t handle) const {
  std::lock_guard<std::mutex> lock(mutex_);
  return contexts_.find(handle) != contexts_.end();
}

bool NativeBrowserContextRegistry::IsOffTheRecord(int64_t handle) const {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = contexts_.find(handle);
  return it != contexts_.end() && it->second.is_off_the_record;
}

std::string NativeBrowserContextRegistry::GetStoragePath(int64_t handle) const {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = contexts_.find(handle);
  if (it == contexts_.end()) return "";
  return it->second.storage_path;
}

bool NativeBrowserContextRegistry::SetCookie(int64_t handle,
                                             const std::string& request_url,
                                             const std::string& top_frame_url,
                                             const std::string& cookie_kv,
                                             NativeCookieMode mode) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = contexts_.find(handle);
  if (it == contexts_.end()) return false;

  if (mode == NativeCookieMode::kBlockAll) return false;
  const bool third_party = IsThirdPartyRequest(request_url, top_frame_url);
  // Trong Off-The-Record hoặc chế độ kBlockThirdParty, chặn cứng mọi cookie bên thứ ba
  if (third_party && (it->second.is_off_the_record || mode == NativeCookieMode::kBlockThirdParty)) {
    return false;
  }

  const std::string host = ExtractHost(request_url);
  if (host.empty()) return false;

  const auto eq_pos = cookie_kv.find('=');
  if (eq_pos == std::string::npos || eq_pos == 0) return false;
  const std::string name = cookie_kv.substr(0, eq_pos);
  const auto semi_pos = cookie_kv.find(';', eq_pos + 1);
  const std::string value = (semi_pos == std::string::npos)
                                ? cookie_kv.substr(eq_pos + 1)
                                : cookie_kv.substr(eq_pos + 1, semi_pos - (eq_pos + 1));

  it->second.cookies_by_host[host][name] = value;
  return true;
}

std::string NativeBrowserContextRegistry::GetCookies(int64_t handle,
                                                     const std::string& request_url,
                                                     const std::string& top_frame_url,
                                                     NativeCookieMode mode) const {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = contexts_.find(handle);
  if (it == contexts_.end()) return "";
  if (mode == NativeCookieMode::kBlockAll) return "";

  const bool third_party = IsThirdPartyRequest(request_url, top_frame_url);
  if (third_party && (it->second.is_off_the_record || mode == NativeCookieMode::kBlockThirdParty)) {
    return "";
  }

  const std::string host = ExtractHost(request_url);
  auto host_it = it->second.cookies_by_host.find(host);
  if (host_it == it->second.cookies_by_host.end()) return "";

  std::ostringstream oss;
  bool first = true;
  for (const auto& [k, v] : host_it->second) {
    if (!first) oss << "; ";
    first = false;
    oss << k << "=" << v;
  }
  return oss.str();
}

bool NativeBrowserContextRegistry::ClearStoragePartition(int64_t handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = contexts_.find(handle);
  if (it == contexts_.end()) return false;
  it->second.cookies_by_host.clear();
  return true;
}

bool NativeBrowserContextRegistry::DestroyContext(int64_t handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = contexts_.find(handle);
  if (it == contexts_.end()) return false;
  it->second.cookies_by_host.clear();
  contexts_.erase(it);
  return true;
}

size_t NativeBrowserContextRegistry::ActiveOffTheRecordContextCount() const {
  std::lock_guard<std::mutex> lock(mutex_);
  size_t count = 0;
  for (const auto& [_, entry] : contexts_) {
    if (entry.is_off_the_record) ++count;
  }
  return count;
}

}  // namespace veko::chromium
