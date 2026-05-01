//! Split-horizon DNS layer in the spirit of `madeye/trust-china-dns`. For
//! `A`/`AAAA` queries we race a Chinese plain-UDP resolver against the
//! trusted TCP DNS client, then pick the China answer iff at least one of
//! its A/AAAA records resolves to a CN IP per the bundled `Country.mmdb`.
//!
//! Anything not covered by the heuristic — disabled config, missing GeoIP,
//! non-A/AAAA qtype — passes straight through to
//! `dns_client::resolve_via_tcp_dns` so behaviour matches the pre-orchestrator
//! path. All chosen answers land in the same shared cache that `dns_client`
//! already manages, so the orchestration cost is paid once per (qname, qtype)
//! per TTL window.

use crate::dns_client;
use crate::dns_table;
use ipnet::{Ipv4Net, Ipv6Net};
use iprange::IpRange;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::OnceLock;
use std::time::Duration;
use tokio::net::UdpSocket;
use tracing::{info, warn};

const CHINA_TIMEOUT: Duration = Duration::from_millis(800);
const UDP_RECV_TIMEOUT: Duration = Duration::from_millis(700);
const UDP_BUF_SIZE: usize = 1500;

/// DNS RR types we treat as "GeoIP-meaningful". Anything else (CNAME-only,
/// MX, TXT, PTR, HTTPS, …) skips orchestration: there is no IP in the answer
/// to gate on, and the trusted DoH path is the existing default.
const QTYPE_A: u16 = 1;
const QTYPE_AAAA: u16 = 28;

/// CN-IP membership oracle, built once at init by scanning `Country.mmdb` for
/// entries with `country.iso_code == "CN"`. The mmdb buffer is dropped right
/// after the scan — only the merged CIDR set lives forever.
struct CnIpset {
    v4: IpRange<Ipv4Net>,
    v6: IpRange<Ipv6Net>,
}

impl CnIpset {
    fn is_empty(&self) -> bool {
        self.v4.iter().next().is_none() && self.v6.iter().next().is_none()
    }

    fn contains(&self, ip: IpAddr) -> bool {
        match ip {
            IpAddr::V4(v4) => self
                .v4
                .contains(&Ipv4Net::new(v4, 32).expect("static prefix")),
            IpAddr::V6(v6) => self
                .v6
                .contains(&Ipv6Net::new(v6, 128).expect("static prefix")),
        }
    }
}

/// CN-IP membership oracle — empty until `init` succeeds. An empty ipset
/// reads as "GeoIP unavailable" and short-circuits orchestration to DoH-only.
static CN_IPSET: OnceLock<CnIpset> = OnceLock::new();

/// Configured Chinese plain-UDP upstreams (DNSPod, AliDNS by default). Empty
/// vec disables the split entirely.
static CHINA_UPSTREAMS: OnceLock<Vec<SocketAddr>> = OnceLock::new();

/// Initialise the CN-IP set and the China-upstream list. Reads `Country.mmdb`
/// off disk, extracts every CN-tagged network into a sorted `(start, end)`
/// range vector, and **drops the mmdb buffer** before returning. Idempotent —
/// second calls are no-ops because the `OnceLock`s reject re-init.
pub fn init(home_dir: Option<&str>, upstreams: Vec<SocketAddr>) {
    let _ = CHINA_UPSTREAMS.set(upstreams.clone());

    let mmdb_path: PathBuf = match home_dir {
        Some(h) => PathBuf::from(h).join("mihomo").join("Country.mmdb"),
        None => PathBuf::from("Country.mmdb"),
    };

    let started = std::time::Instant::now();
    let ipset = match build_cn_ipset(&mmdb_path) {
        Ok(set) => {
            info!(
                "china_dns: CN ipset built from {} in {} ms — {} v4 prefixes, {} v6 prefixes, {} upstream(s) {:?}",
                mmdb_path.display(),
                started.elapsed().as_millis(),
                set.v4.iter().count(),
                set.v6.iter().count(),
                upstreams.len(),
                upstreams,
            );
            set
        }
        Err(e) => {
            warn!(
                "china_dns: GeoIP unavailable at {} ({}); split disabled, all queries via TCP DNS",
                mmdb_path.display(),
                e
            );
            CnIpset {
                v4: IpRange::new(),
                v6: IpRange::new(),
            }
        }
    };

    let _ = CN_IPSET.set(ipset);
}

/// Open `Country.mmdb`, walk every network, retain CN-tagged entries in an
/// `IpRange<IpNet>`, then `simplify()` to merge adjacent prefixes. The
/// `Reader` (which owns the `Vec<u8>` mmdb buffer) is dropped at end of scope,
/// so the function returns with only the compact CN-only ipset retained.
fn build_cn_ipset(mmdb_path: &std::path::Path) -> Result<CnIpset, String> {
    let bytes = std::fs::read(mmdb_path).map_err(|e| format!("read: {e}"))?;
    let reader = maxminddb::Reader::from_source(bytes).map_err(|e| format!("parse: {e}"))?;
    let reader_ref = &reader;

    let iso_path = [
        maxminddb::PathElement::Key("country"),
        maxminddb::PathElement::Key("iso_code"),
    ];
    let path_ref = &iso_path;

    let (v4_nets, v6_nets) = std::thread::scope(|s| {
        let v4 = std::thread::Builder::new()
            .stack_size(512 * 1024)
            .spawn_scoped(s, move || {
                collect_cn_networks(reader_ref, "0.0.0.0/0", path_ref)
            })
            .expect("spawn v4 scan thread");
        let v6 = std::thread::Builder::new()
            .stack_size(512 * 1024)
            .spawn_scoped(s, move || collect_cn_networks(reader_ref, "::/0", path_ref))
            .expect("spawn v6 scan thread");
        (
            v4.join().expect("v4 scan panicked"),
            v6.join().expect("v6 scan panicked"),
        )
    });

    let mut v4: IpRange<Ipv4Net> = IpRange::new();
    let mut v6: IpRange<Ipv6Net> = IpRange::new();
    for net in v4_nets {
        insert_network(&mut v4, &mut v6, net);
    }
    for net in v6_nets {
        insert_network(&mut v4, &mut v6, net);
    }

    v4.simplify();
    v6.simplify();

    if v4.iter().next().is_none() && v6.iter().next().is_none() {
        return Err("no CN networks found in mmdb".to_string());
    }
    Ok(CnIpset { v4, v6 })
}

fn collect_cn_networks(
    reader: &maxminddb::Reader<Vec<u8>>,
    cidr: &str,
    iso_path: &[maxminddb::PathElement<'_>],
) -> Vec<ipnetwork::IpNetwork> {
    let query: ipnetwork::IpNetwork = cidr.parse().expect("static cidr");
    let iter = match reader.within(query, maxminddb::WithinOptions::default()) {
        Ok(it) => it,
        Err(_) => return Vec::new(),
    };

    let mut out = Vec::new();
    for item in iter {
        let lookup = match item {
            Ok(l) => l,
            Err(_) => continue,
        };
        if !matches!(lookup.decode_path::<&str>(iso_path), Ok(Some("CN"))) {
            continue;
        }
        if let Ok(net) = lookup.network() {
            out.push(net);
        }
    }
    out
}

fn insert_network(v4: &mut IpRange<Ipv4Net>, v6: &mut IpRange<Ipv6Net>, net: ipnetwork::IpNetwork) {
    let prefix = net.prefix();
    match net.network() {
        IpAddr::V4(addr) => {
            if let Ok(n) = Ipv4Net::new(addr, prefix) {
                v4.add(n);
            }
        }
        IpAddr::V6(addr) => {
            if let Ok(n) = Ipv6Net::new(addr, prefix) {
                v6.add(n);
            }
        }
    }
}

/// Default Chinese plain-UDP upstreams used when the user has not set
/// `dns.china_nameserver` in `config.yaml`. DNSPod (119.29.29.29:53) and
/// AliDNS (223.5.5.5:53).
pub fn default_china_upstreams() -> Vec<SocketAddr> {
    vec![
        "119.29.29.29:53".parse().expect("static literal"),
        "223.5.5.5:53".parse().expect("static literal"),
    ]
}

/// Top-level resolver replacing `dns_client::resolve_via_tcp_dns` at the
/// `tun2socks::handle_dns_query` entry point.
pub async fn resolve(query: &[u8]) -> Option<Vec<u8>> {
    if let Some(resp) = dns_client::cache_lookup_for_external(query) {
        return Some(resp);
    }

    if !split_applies(query) {
        return dns_client::resolve_via_tcp_dns(query).await;
    }

    let china_query = query.to_vec();
    let china_fut = async move { udp_query_race(&china_query).await };
    let trusted_fut = dns_client::resolve_via_tcp_dns(query);

    tokio::pin!(china_fut);
    tokio::pin!(trusted_fut);

    let china_response = tokio::select! {
        biased;
        china = &mut china_fut => china,
        _ = tokio::time::sleep(CHINA_TIMEOUT) => None,
    };

    if let Some(ref resp) = china_response {
        if response_has_cn_ip(resp) {
            dns_client::cache_store_external(query, resp);
            return Some(resp.clone());
        }
    }

    if let Some(resp) = (&mut trusted_fut).await {
        return Some(resp);
    }

    if let Some(resp) = china_response {
        dns_client::cache_store_external(query, &resp);
        return Some(resp);
    }

    None
}

fn split_applies(query: &[u8]) -> bool {
    let ipset_ready = CN_IPSET.get().map(|s| !s.is_empty()).unwrap_or(false);
    if !ipset_ready {
        return false;
    }

    match CHINA_UPSTREAMS.get() {
        Some(u) if !u.is_empty() => {}
        _ => return false,
    }

    matches!(query_qtype(query), Some(QTYPE_A) | Some(QTYPE_AAAA))
}

/// Walks the question section to extract the QTYPE. Returns `None` for
/// malformed queries (caller falls back to TCP DNS).
fn query_qtype(query: &[u8]) -> Option<u16> {
    if query.len() < 12 {
        return None;
    }
    let mut i = 12usize;
    loop {
        if i >= query.len() {
            return None;
        }
        let len = query[i];
        if len == 0 {
            i += 1;
            break;
        }
        if len & 0xc0 != 0 {
            return None;
        }
        i += 1 + len as usize;
        if i > query.len() {
            return None;
        }
    }
    if i + 4 > query.len() {
        return None;
    }
    Some(u16::from_be_bytes([query[i], query[i + 1]]))
}

async fn udp_query_race(query: &[u8]) -> Option<Vec<u8>> {
    let upstreams = CHINA_UPSTREAMS.get()?;
    if upstreams.is_empty() {
        return None;
    }

    let futs: Vec<_> = upstreams
        .iter()
        .copied()
        .map(|addr| {
            let q = query.to_vec();
            Box::pin(async move { udp_query_one(addr, &q).await })
        })
        .collect();

    match futures::future::select_ok(futs).await {
        Ok((resp, _rest)) => Some(resp),
        Err(_) => None,
    }
}

async fn udp_query_one(addr: SocketAddr, query: &[u8]) -> Result<Vec<u8>, anyhow::Error> {
    let bind: SocketAddr = match addr {
        SocketAddr::V4(_) => "0.0.0.0:0".parse().expect("static literal"),
        SocketAddr::V6(_) => "[::]:0".parse().expect("static literal"),
    };
    let socket = UdpSocket::bind(bind).await?;
    socket.connect(addr).await?;
    socket.send(query).await?;

    let mut buf = vec![0u8; UDP_BUF_SIZE];
    let n = tokio::time::timeout(UDP_RECV_TIMEOUT, socket.recv(&mut buf)).await??;
    buf.truncate(n);
    if buf.len() < 12 {
        anyhow::bail!("udp dns response truncated ({} bytes)", buf.len());
    }
    Ok(buf)
}

/// True iff at least one A/AAAA record in `response` resolves to a CN IP per
/// the pre-built ipset.
pub(crate) fn response_has_cn_ip(response: &[u8]) -> bool {
    let Some(ipset) = CN_IPSET.get() else {
        return false;
    };
    if ipset.is_empty() {
        return false;
    }
    for (ip, _name, _ttl) in dns_table::parse_dns_response_records(response) {
        if ipset.contains(ip) {
            return true;
        }
    }
    false
}
