static INIT: std::sync::Once = std::sync::Once::new();

/// Initialize android_logger. Safe to call multiple times. The core crate
/// logs through the `log` facade, so this sink receives everything.
pub fn init_android_logger() {
    INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("mihomo-ffi"),
        );
    });
}
