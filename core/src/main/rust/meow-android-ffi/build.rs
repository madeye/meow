use std::env;

// boring-sys links libc++ statically (BORING_BSSL_RUST_CPPLIB=c++_static,
// exported by core/build.gradle.kts) so the APK doesn't have to package
// libc++_shared.so. libc++_static.a in turn needs libc++abi.a for the
// __cxa_* runtime symbols; both are resolved by the NDK clang linker driver
// from its own sysroot.
fn main() {
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }
    println!("cargo:rustc-link-lib=c++abi");
}
