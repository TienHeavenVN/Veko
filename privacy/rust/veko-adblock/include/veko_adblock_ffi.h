#ifndef VEKO_ADBLOCK_FFI_H_
#define VEKO_ADBLOCK_FFI_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct VekoAdblockHandle VekoAdblockHandle;

typedef enum VekoFilterAction {
  VEKO_FILTER_ALLOW = 0,
  VEKO_FILTER_BLOCK_AD = 1,
  VEKO_FILTER_BLOCK_TRACKER = 2,
  VEKO_FILTER_REWRITE_URL = 3,
} VekoFilterAction;

typedef struct VekoFilterStats {
  uint64_t total_requests_checked;
  uint64_t ads_blocked;
  uint64_t trackers_blocked;
  uint64_t params_stripped;
  uint64_t active_rule_count;
} VekoFilterStats;

const char* veko_adblock_engine_version(void);

VekoAdblockHandle* veko_adblock_create_default(void);

VekoAdblockHandle* veko_adblock_create_with_rules(const char* custom_rules_utf8,
                                                  size_t custom_rules_len,
                                                  bool include_builtin);

bool veko_adblock_reload_rules(VekoAdblockHandle* handle,
                               const char* custom_rules_utf8,
                               size_t custom_rules_len,
                               bool include_builtin);

int32_t veko_adblock_evaluate_request(const VekoAdblockHandle* handle,
                                      const char* request_url,
                                      const char* source_url,
                                      const char* resource_type,
                                      const char* http_method,
                                      bool block_ads,
                                      bool block_trackers,
                                      bool strip_query_params,
                                      bool record_stats,
                                      char* rewritten_url_out,
                                      size_t rewritten_url_capacity);

void veko_adblock_get_stats(const VekoAdblockHandle* handle,
                            VekoFilterStats* out_stats);

void veko_adblock_reset_stats(VekoAdblockHandle* handle);

void veko_adblock_destroy(VekoAdblockHandle* handle);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // VEKO_ADBLOCK_FFI_H_
