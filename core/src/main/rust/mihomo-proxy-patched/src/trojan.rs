//! Trojan outbound proxy adapter.
//!
//! TLS is provided by `mihomo_transport::tls::TlsLayer` (M1.A-1 migration).
//! Protocol logic — SHA-224 password hash, CRLF header, SOCKS5 address
//! encoding — remains here unchanged.

use crate::connect::protected_tcp_connect;
use async_trait::async_trait;
use mihomo_common::{
    AdapterType, Metadata, MihomoError, ProxyAdapter, ProxyConn, ProxyHealth, ProxyPacketConn,
    Result,
};
use mihomo_transport::{
    tls::{TlsConfig, TlsLayer},
    Transport,
};
use sha2::{Digest, Sha224};
use tokio::io::AsyncWriteExt;
use tracing::debug;

use crate::stream_conn::StreamConn;
use crate::transport_to_proxy_err;

pub struct TrojanAdapter {
    name: String,
    addr_str: String,
    hex_password: String,
    support_udp: bool,
    health: ProxyHealth,
    tls_layer: TlsLayer,
}

impl TrojanAdapter {
    pub fn new(
        name: &str,
        server: &str,
        port: u16,
        password: &str,
        sni: &str,
        skip_verify: bool,
        udp: bool,
    ) -> Self {
        // SHA-224 hash of password, hex-encoded = 56 chars.
        let mut hasher = Sha224::new();
        hasher.update(password.as_bytes());
        let hex_password = hex::encode(hasher.finalize());

        // Config resolves effective SNI: explicit sni if set, else server hostname.
        let effective_sni = if sni.is_empty() {
            server.to_string()
        } else {
            sni.to_string()
        };

        let tls_config = TlsConfig {
            skip_cert_verify: skip_verify,
            ..TlsConfig::new(effective_sni)
        };

        let tls_layer = TlsLayer::new(&tls_config)
            .expect("TrojanAdapter: failed to build TlsLayer — check SNI/cert config");

        Self {
            name: name.to_string(),
            addr_str: format!("{}:{}", server, port),
            hex_password,
            support_udp: udp,
            health: ProxyHealth::new(),
            tls_layer,
        }
    }

    fn build_header(&self, metadata: &Metadata, cmd: u8) -> Vec<u8> {
        let mut buf = Vec::new();
        // hex password + CRLF
        buf.extend_from_slice(self.hex_password.as_bytes());
        buf.extend_from_slice(b"\r\n");
        // command byte
        buf.push(cmd);
        // SOCKS5 address format
        if !metadata.host.is_empty() {
            buf.push(0x03); // ATYP domain
            let host_bytes = metadata.host.as_bytes();
            buf.push(host_bytes.len() as u8);
            buf.extend_from_slice(host_bytes);
        } else if let Some(ip) = metadata.dst_ip {
            match ip {
                std::net::IpAddr::V4(v4) => {
                    buf.push(0x01); // ATYP IPv4
                    buf.extend_from_slice(&v4.octets());
                }
                std::net::IpAddr::V6(v6) => {
                    buf.push(0x04); // ATYP IPv6
                    buf.extend_from_slice(&v6.octets());
                }
            }
        }
        // Port (big-endian)
        buf.extend_from_slice(&metadata.dst_port.to_be_bytes());
        // CRLF
        buf.extend_from_slice(b"\r\n");
        buf
    }
}

#[async_trait]
impl ProxyAdapter for TrojanAdapter {
    fn name(&self) -> &str {
        &self.name
    }

    fn adapter_type(&self) -> AdapterType {
        AdapterType::Trojan
    }

    fn addr(&self) -> &str {
        &self.addr_str
    }

    fn support_udp(&self) -> bool {
        self.support_udp
    }

    async fn dial_tcp(&self, metadata: &Metadata) -> Result<Box<dyn ProxyConn>> {
        debug!(
            "Trojan connecting to {} via {}",
            metadata.remote_address(),
            self.addr_str
        );

        // TCP connect — Android: route through the global pre-connect hook so
        // VpnService.protect(fd) fires before the SYN, otherwise the proxy
        // socket loops back into the VPN TUN.
        let tcp = protected_tcp_connect(&self.addr_str)
            .await
            .map_err(MihomoError::Io)?;

        // TLS handshake via the shared TlsLayer.
        let mut stream = self
            .tls_layer
            .connect(Box::new(tcp))
            .await
            .map_err(transport_to_proxy_err)?;

        // Send Trojan header (CMD_CONNECT = 0x01).
        let header = self.build_header(metadata, 0x01);
        stream.write_all(&header).await.map_err(MihomoError::Io)?;

        Ok(Box::new(StreamConn(stream)))
    }

    async fn dial_udp(&self, _metadata: &Metadata) -> Result<Box<dyn ProxyPacketConn>> {
        Err(MihomoError::NotSupported(
            "Trojan UDP not yet implemented".into(),
        ))
    }

    fn health(&self) -> &ProxyHealth {
        &self.health
    }
}
