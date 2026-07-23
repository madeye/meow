// NAPI module "meow" — marshals between ArkTS and the Rust C ABI in
// libmihomo_ohos_ffi.so. The Rust surface is exercised end-to-end on the
// host by core/src/main/rust/mihomo-ohos-ffi/tests/c_abi_e2e.rs; this file
// only converts values and owns the protect-callback bridge.
//
// Protect bridge: the engine protects outbound sockets by fd. On HarmonyOS
// the only protect API is the ArkTS VpnConnection.protect(fd), so the ArkTS
// side registers a callback via setProtectHandler() and the Rust engine
// reaches it through a napi_threadsafe_function. NOTE: meow-rs v0.18.0 only
// compiles its SocketProtector registry for Android, so on OpenHarmony the
// callback is stored but not yet invoked per-fd by the engine — see
// mihomo-ohos-ffi/src/protect.rs for the tracked engine gap.

#include "mihomo_ohos_ffi.h"
#include "napi/native_api.h"
#include <hilog/log.h>

#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>

#undef LOG_TAG
#define LOG_TAG "meow-napi"
#undef LOG_DOMAIN
#define LOG_DOMAIN 0x0001

namespace {

std::string GetStringArg(napi_env env, napi_value value) {
    size_t len = 0;
    napi_get_value_string_utf8(env, value, nullptr, 0, &len);
    std::string out(len, '\0');
    napi_get_value_string_utf8(env, value, out.data(), len + 1, &len);
    return out;
}

napi_value MakeString(napi_env env, char *owned) {
    napi_value result = nullptr;
    if (owned == nullptr) {
        napi_create_string_utf8(env, "", 0, &result);
        return result;
    }
    napi_create_string_utf8(env, owned, NAPI_AUTO_LENGTH, &result);
    meow_string_free(owned);
    return result;
}

// ---------------------------------------------------------------------------
// Protect-callback bridge (ArkTS VpnConnection.protect via TSFN)
// ---------------------------------------------------------------------------

napi_threadsafe_function g_protect_tsfn = nullptr;

struct ProtectRequest {
    int fd = -1;
    bool done = false;
    bool ok = false;
    std::mutex mu;
    std::condition_variable cv;
};

// Runs on the ArkTS thread: invoke the registered JS handler synchronously.
// The handler is expected to call VpnConnection.protect and return a
// boolean-ish result; a Promise return counts as success optimistically
// (see the protect.rs engine-gap note — by the time the engine can invoke
// per-fd protect on OpenHarmony this should be replaced with a
// promise-aware completion).
void ProtectCallJs(napi_env env, napi_value js_cb, void * /*context*/, void *data) {
    auto *req = static_cast<ProtectRequest *>(data);
    bool ok = false;
    if (env != nullptr && js_cb != nullptr) {
        napi_value undefined = nullptr;
        napi_get_undefined(env, &undefined);
        napi_value arg = nullptr;
        napi_create_int32(env, req->fd, &arg);
        napi_value result = nullptr;
        if (napi_call_function(env, undefined, js_cb, 1, &arg, &result) == napi_ok) {
            bool is_bool_false = false;
            napi_valuetype type = napi_undefined;
            napi_typeof(env, result, &type);
            if (type == napi_boolean) {
                bool value = false;
                napi_get_value_bool(env, result, &value);
                is_bool_false = !value;
            }
            ok = !is_bool_false;
        }
    }
    {
        std::lock_guard<std::mutex> lock(req->mu);
        req->ok = ok;
        req->done = true;
    }
    req->cv.notify_one();
}

// Called by Rust on an engine worker thread for every outbound socket.
int ProtectTrampoline(int fd) {
    if (g_protect_tsfn == nullptr) {
        OH_LOG_WARN(LOG_APP, "protect(%{public}d): no ArkTS handler registered", fd);
        return 0;
    }
    ProtectRequest req;
    req.fd = fd;
    if (napi_call_threadsafe_function(g_protect_tsfn, &req, napi_tsfn_blocking) != napi_ok) {
        return 0;
    }
    std::unique_lock<std::mutex> lock(req.mu);
    if (!req.cv.wait_for(lock, std::chrono::seconds(2), [&req] { return req.done; })) {
        OH_LOG_WARN(LOG_APP, "protect(%{public}d): ArkTS handler timed out", fd);
        return 0;
    }
    return req.ok ? 1 : 0;
}

// ---------------------------------------------------------------------------
// NAPI-exported functions
// ---------------------------------------------------------------------------

napi_value Init(napi_env env, napi_callback_info /*info*/) {
    meow_init();
    napi_value undefined = nullptr;
    napi_get_undefined(env, &undefined);
    return undefined;
}

napi_value SetHomeDir(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    std::string dir = GetStringArg(env, args[0]);
    meow_set_home_dir(dir.c_str());
    napi_value undefined = nullptr;
    napi_get_undefined(env, &undefined);
    return undefined;
}

napi_value SetProtectHandler(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    if (g_protect_tsfn != nullptr) {
        napi_release_threadsafe_function(g_protect_tsfn, napi_tsfn_release);
        g_protect_tsfn = nullptr;
    }
    napi_value name = nullptr;
    napi_create_string_utf8(env, "meowProtect", NAPI_AUTO_LENGTH, &name);
    napi_create_threadsafe_function(env, args[0], nullptr, name, 0, 1, nullptr, nullptr,
                                    nullptr, ProtectCallJs, &g_protect_tsfn);
    napi_value undefined = nullptr;
    napi_get_undefined(env, &undefined);
    return undefined;
}

napi_value StartEngine(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    std::string controller = argc > 0 ? GetStringArg(env, args[0]) : "";
    std::string secret = argc > 1 ? GetStringArg(env, args[1]) : "";
    int rc = meow_start_engine(controller.empty() ? nullptr : controller.c_str(), secret.c_str());
    napi_value result = nullptr;
    napi_create_int32(env, rc, &result);
    return result;
}

napi_value StopEngine(napi_env env, napi_callback_info /*info*/) {
    meow_stop_engine();
    napi_value undefined = nullptr;
    napi_get_undefined(env, &undefined);
    return undefined;
}

napi_value StartTun2Socks(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    int32_t fd = -1;
    napi_get_value_int32(env, args[0], &fd);
    int rc = meow_start_tun2socks(fd, ProtectTrampoline);
    napi_value result = nullptr;
    napi_create_int32(env, rc, &result);
    return result;
}

napi_value IsRunning(napi_env env, napi_callback_info /*info*/) {
    napi_value result = nullptr;
    napi_get_boolean(env, meow_is_running() != 0, &result);
    return result;
}

napi_value GetUploadTraffic(napi_env env, napi_callback_info /*info*/) {
    napi_value result = nullptr;
    napi_create_int64(env, meow_get_upload_traffic(), &result);
    return result;
}

napi_value GetDownloadTraffic(napi_env env, napi_callback_info /*info*/) {
    napi_value result = nullptr;
    napi_create_int64(env, meow_get_download_traffic(), &result);
    return result;
}

napi_value ValidateConfig(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    std::string yaml = GetStringArg(env, args[0]);
    napi_value result = nullptr;
    napi_create_int32(env, meow_validate_config(yaml.c_str()), &result);
    return result;
}

napi_value GetLastError(napi_env env, napi_callback_info /*info*/) {
    return MakeString(env, meow_get_last_error());
}

napi_value GetLogs(napi_env env, napi_callback_info /*info*/) {
    return MakeString(env, meow_get_logs());
}

napi_value Version(napi_env env, napi_callback_info /*info*/) {
    return MakeString(env, meow_version());
}

napi_value TestDirectTcp(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    std::string host = GetStringArg(env, args[0]);
    int32_t port = 0;
    napi_get_value_int32(env, args[1], &port);
    return MakeString(env, meow_test_direct_tcp(host.c_str(), port));
}

napi_value TestDnsResolver(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    std::string addr = GetStringArg(env, args[0]);
    return MakeString(env, meow_test_dns_resolver(addr.c_str()));
}

napi_value ModuleInit(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"init", nullptr, Init, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setHomeDir", nullptr, SetHomeDir, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setProtectHandler", nullptr, SetProtectHandler, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"startEngine", nullptr, StartEngine, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"stopEngine", nullptr, StopEngine, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"startTun2Socks", nullptr, StartTun2Socks, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"isRunning", nullptr, IsRunning, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getUploadTraffic", nullptr, GetUploadTraffic, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getDownloadTraffic", nullptr, GetDownloadTraffic, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"validateConfig", nullptr, ValidateConfig, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getLastError", nullptr, GetLastError, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getLogs", nullptr, GetLogs, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"version", nullptr, Version, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"testDirectTcp", nullptr, TestDirectTcp, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"testDnsResolver", nullptr, TestDnsResolver, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}

napi_module g_module = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = ModuleInit,
    .nm_modname = "meow",
    .nm_priv = nullptr,
    .reserved = {nullptr},
};

} // namespace

extern "C" __attribute__((constructor)) void RegisterMeowModule(void) {
    napi_module_register(&g_module);
}
