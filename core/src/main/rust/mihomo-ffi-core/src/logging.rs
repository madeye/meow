//! Platform-neutral logging shim. Messages go through the `log` facade; the
//! platform crates install the actual sink (`android_logger` → logcat on
//! Android, hilog on HarmonyOS). With no sink installed (host tests) the
//! calls are no-ops unless a test harness installs its own logger.

use log::info;

pub fn bridge_log(msg: &str) {
    info!("{}", msg);
}
