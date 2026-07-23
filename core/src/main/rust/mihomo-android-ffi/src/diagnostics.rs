//! JNI wrappers around the platform-neutral connectivity probes in
//! `mihomo_ffi_core::diagnostics` (Settings diagnostics UI).

use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;
use mihomo_ffi_core as core;

fn result_to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeTestDirectTcp(
    mut env: JNIEnv,
    _class: JClass,
    host: jni::objects::JString,
    port: jni::sys::jint,
) -> jstring {
    let host_str: String = env.get_string(&host).map(|s| s.into()).unwrap_or_default();
    let result = core::diagnostics::test_direct_tcp(&host_str, port as u16);
    result_to_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_madeye_meow_core_MihomoCore_nativeTestDnsResolver(
    mut env: JNIEnv,
    _class: JClass,
    dns_addr: jni::objects::JString,
) -> jstring {
    let addr_str: String = env
        .get_string(&dns_addr)
        .map(|s| s.into())
        .unwrap_or_default();
    let result = core::diagnostics::test_dns_resolver(&addr_str);
    result_to_jstring(&mut env, &result)
}
