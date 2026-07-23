//! Log sink installation. On OpenHarmony the `log` facade (which also
//! carries the engine's `tracing` events via the `log-always` feature) is
//! bridged to hilog through the NDK's `OH_LOG_Print`; on other targets
//! (host `cargo test`) a stderr logger is installed so test failures are
//! debuggable.

static INIT: std::sync::Once = std::sync::Once::new();

pub fn init() {
    INIT.call_once(|| {
        let _ = log::set_boxed_logger(Box::new(sink::Logger));
        log::set_max_level(log::LevelFilter::Debug);
    });
}

#[cfg(target_env = "ohos")]
mod sink {
    use std::ffi::{c_char, c_int, c_uint, CString};

    // libhilog_ndk.z.so — LogType LOG_APP = 0; LogLevel DEBUG..FATAL = 3..7.
    #[link(name = "hilog_ndk.z")]
    extern "C" {
        fn OH_LOG_Print(
            log_type: c_int,
            level: c_int,
            domain: c_uint,
            tag: *const c_char,
            fmt: *const c_char,
            ...
        ) -> c_int;
    }

    const LOG_APP: c_int = 0;
    const DOMAIN: c_uint = 0x0001;

    pub struct Logger;

    impl log::Log for Logger {
        fn enabled(&self, _metadata: &log::Metadata) -> bool {
            true
        }

        fn log(&self, record: &log::Record) {
            let level = match record.level() {
                log::Level::Error => 6,
                log::Level::Warn => 5,
                log::Level::Info => 4,
                log::Level::Debug | log::Level::Trace => 3,
            };
            let tag = c"mihomo-ffi";
            let fmt = c"%{public}s";
            let msg = CString::new(format!("{}", record.args()).replace('\0', " "))
                .expect("NULs replaced");
            unsafe {
                OH_LOG_Print(
                    LOG_APP,
                    level,
                    DOMAIN,
                    tag.as_ptr(),
                    fmt.as_ptr(),
                    msg.as_ptr(),
                );
            }
        }

        fn flush(&self) {}
    }
}

#[cfg(not(target_env = "ohos"))]
mod sink {
    pub struct Logger;

    impl log::Log for Logger {
        fn enabled(&self, _metadata: &log::Metadata) -> bool {
            true
        }

        fn log(&self, record: &log::Record) {
            eprintln!("[{}] {}", record.level(), record.args());
        }

        fn flush(&self) {}
    }
}
