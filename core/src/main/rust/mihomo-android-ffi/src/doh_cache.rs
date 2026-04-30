//! Persistent companion to the in-memory DoH answer cache in `doh_client`.
//!
//! Stores `(question_section → (response_bytes, expires_unix_secs))` in a
//! single redb file under `$HOME_DIR/doh-cache.redb`. Read at
//! `init_doh_client` to warm the in-memory map; written through on every
//! cache insert/eviction. Expires are wall-clock (`SystemTime`) so a reboot
//! correctly invalidates stale entries — the in-memory cache uses `Instant`
//! and is rebuilt fresh from the persisted wall-clock TTLs at hydrate time.
//!
//! Single-writer: the DoH client only runs inside the VPN service process
//! (gated by `engine::tunnel()`), so no two processes ever open this file
//! concurrently.

use parking_lot::Mutex;
use redb::{Database, ReadableTable, TableDefinition};
use std::path::Path;
use std::sync::OnceLock;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tracing::{info, warn};

const TABLE: TableDefinition<&[u8], (&[u8], u64)> = TableDefinition::new("doh_v1");

type CacheResult<T> = Result<T, Box<redb::Error>>;
type Entry = (Vec<u8>, Vec<u8>, u64);

static DB: OnceLock<Mutex<Option<Database>>> = OnceLock::new();

fn slot() -> &'static Mutex<Option<Database>> {
    DB.get_or_init(|| Mutex::new(None))
}

/// Open (or create) the persistent cache file. Idempotent — repeated calls
/// after a successful open are a no-op. On error (path missing, redb open
/// failure) the in-memory cache continues to work; persistence is best-effort.
pub fn init(home_dir: Option<&str>) {
    let mut guard = slot().lock();
    if guard.is_some() {
        return;
    }
    let Some(home) = home_dir else {
        info!("doh cache: no HOME_DIR, persistence disabled");
        return;
    };
    let path = Path::new(home).join("doh-cache.redb");
    match open_db(&path) {
        Ok(db) => {
            info!("doh cache: opened {}", path.display());
            *guard = Some(db);
        }
        Err(e) => {
            warn!("doh cache: open {} failed: {e}", path.display());
        }
    }
}

fn open_db(path: &Path) -> CacheResult<Database> {
    let db = Database::create(path).map_err(boxed)?;
    // Touch the table so a fresh file has the schema before the first read
    // transaction tries to open it.
    let txn = db.begin_write().map_err(boxed)?;
    {
        let _ = txn.open_table(TABLE).map_err(boxed)?;
    }
    txn.commit().map_err(boxed)?;
    Ok(db)
}

fn boxed<E: Into<redb::Error>>(e: E) -> Box<redb::Error> {
    Box::new(e.into())
}

fn now_unix_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Convert a Unix-seconds expiry into a `Duration` from now (saturating to
/// zero if already expired).
pub fn expires_in(unix_secs: u64) -> Duration {
    let now = now_unix_secs();
    if unix_secs > now {
        Duration::from_secs(unix_secs - now)
    } else {
        Duration::ZERO
    }
}

pub fn unix_secs_in(ttl: Duration) -> u64 {
    now_unix_secs().saturating_add(ttl.as_secs())
}

/// Load all unexpired `(key, response_bytes, expires_unix_secs)` triples.
pub fn load_unexpired() -> Vec<(Vec<u8>, Vec<u8>, u64)> {
    let guard = slot().lock();
    let Some(db) = guard.as_ref() else {
        return Vec::new();
    };
    load_unexpired_from(db).unwrap_or_else(|e| {
        warn!("doh cache: load failed: {e}");
        Vec::new()
    })
}

pub fn put(key: &[u8], bytes: &[u8], expires_unix_secs: u64) {
    let guard = slot().lock();
    let Some(db) = guard.as_ref() else { return };
    if let Err(e) = put_into(db, key, bytes, expires_unix_secs) {
        warn!("doh cache: put failed: {e}");
    }
}

pub fn remove(key: &[u8]) {
    let guard = slot().lock();
    let Some(db) = guard.as_ref() else { return };
    if let Err(e) = remove_from(db, key) {
        warn!("doh cache: remove failed: {e}");
    }
}

fn load_unexpired_from(db: &Database) -> CacheResult<Vec<Entry>> {
    let now = now_unix_secs();
    let txn = db.begin_read().map_err(boxed)?;
    let table = txn.open_table(TABLE).map_err(boxed)?;
    let mut out = Vec::new();
    for entry in table.iter().map_err(boxed)? {
        let (k, v) = entry.map_err(boxed)?;
        let (bytes, expires) = v.value();
        if expires > now {
            out.push((k.value().to_vec(), bytes.to_vec(), expires));
        }
    }
    Ok(out)
}

fn put_into(db: &Database, key: &[u8], bytes: &[u8], expires_unix_secs: u64) -> CacheResult<()> {
    let txn = db.begin_write().map_err(boxed)?;
    {
        let mut table = txn.open_table(TABLE).map_err(boxed)?;
        table
            .insert(key, (bytes, expires_unix_secs))
            .map_err(boxed)?;
    }
    txn.commit().map_err(boxed)?;
    Ok(())
}

fn remove_from(db: &Database, key: &[u8]) -> CacheResult<()> {
    let txn = db.begin_write().map_err(boxed)?;
    {
        let mut table = txn.open_table(TABLE).map_err(boxed)?;
        table.remove(key).map_err(boxed)?;
    }
    txn.commit().map_err(boxed)?;
    Ok(())
}
