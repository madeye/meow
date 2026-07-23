// C ABI of libmihomo_ohos_ffi.so (Rust). Keep in sync with
// core/src/main/rust/mihomo-ohos-ffi/src/lib.rs — that file is the source of
// truth and is exercised end-to-end by its tests/c_abi_e2e.rs.
#ifndef MIHOMO_OHOS_FFI_H
#define MIHOMO_OHOS_FFI_H

#ifdef __cplusplus
extern "C" {
#endif

typedef int (*MeowProtectCallback)(int fd);

void meow_init(void);
void meow_set_home_dir(const char *dir);
int meow_start_engine(const char *external_controller, const char *secret);
void meow_stop_engine(void);
int meow_start_tun2socks(int fd, MeowProtectCallback protect_cb);
int meow_is_running(void);
long long meow_get_upload_traffic(void);
long long meow_get_download_traffic(void);
int meow_validate_config(const char *yaml);
char *meow_get_last_error(void);
char *meow_get_logs(void);
char *meow_version(void);
char *meow_test_direct_tcp(const char *host, int port);
char *meow_test_dns_resolver(const char *dns_addr);
void meow_string_free(char *ptr);

#ifdef __cplusplus
}
#endif

#endif // MIHOMO_OHOS_FFI_H
