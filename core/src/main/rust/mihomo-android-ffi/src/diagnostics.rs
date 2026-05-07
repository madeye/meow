use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;
use std::net::{TcpStream, UdpSocket};
use std::time::{Duration, Instant};

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
    let addr = format!("{}:{}", host_str, port);
    let start = Instant::now();
    match TcpStream::connect_timeout(
        &addr
            .parse()
            .unwrap_or_else(|_| "0.0.0.0:0".parse().unwrap()),
        Duration::from_secs(5),
    ) {
        Ok(_) => {
            let elapsed = start.elapsed();
            result_to_jstring(
                &mut env,
                &format!("OK: connected to {} in {:?}", addr, elapsed),
            )
        }
        Err(e) => {
            let elapsed = start.elapsed();
            result_to_jstring(&mut env, &format!("FAIL after {:?}: {}", elapsed, e))
        }
    }
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
    let sock = match UdpSocket::bind("0.0.0.0:0") {
        Ok(s) => s,
        Err(e) => return result_to_jstring(&mut env, &format!("DNS-TEST: FAIL bind: {}", e)),
    };
    let _ = sock.set_read_timeout(Some(Duration::from_secs(5)));

    if let Err(e) = sock.connect(&addr_str) {
        return result_to_jstring(
            &mut env,
            &format!("DNS-TEST: FAIL connect to {}: {}", addr_str, e),
        );
    }

    let query = build_dns_query("www.baidu.com");
    if let Err(e) = sock.send(&query) {
        return result_to_jstring(&mut env, &format!("DNS-TEST: FAIL write: {}", e));
    }

    let mut buf = vec![0u8; 512];
    match sock.recv(&mut buf) {
        Ok(n) => {
            if let Some(ip) = parse_dns_response_a(&buf[..n]) {
                result_to_jstring(&mut env, &format!("DNS-TEST: OK {} for www.baidu.com", ip))
            } else {
                result_to_jstring(&mut env, "DNS-TEST: FAIL could not parse A record")
            }
        }
        Err(e) => result_to_jstring(&mut env, &format!("DNS-TEST: FAIL read: {}", e)),
    }
}

fn build_dns_query(domain: &str) -> Vec<u8> {
    let mut buf = Vec::with_capacity(64);
    buf.extend_from_slice(&[
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    ]);
    for label in domain.split('.') {
        buf.push(label.len() as u8);
        buf.extend_from_slice(label.as_bytes());
    }
    buf.push(0x00);
    buf.extend_from_slice(&[0x00, 0x01, 0x00, 0x01]);
    buf
}

fn parse_dns_response_a(msg: &[u8]) -> Option<String> {
    if msg.len() < 12 {
        return None;
    }
    let mut pos = 12;
    let qdcount = (msg[4] as usize) << 8 | msg[5] as usize;
    for _ in 0..qdcount {
        while pos < msg.len() {
            let l = msg[pos] as usize;
            pos += 1;
            if l == 0 {
                break;
            }
            if l >= 0xC0 {
                pos += 1;
                break;
            }
            pos += l;
        }
        pos += 4;
    }
    let ancount = (msg[6] as usize) << 8 | msg[7] as usize;
    for _ in 0..ancount {
        if pos < msg.len() && msg[pos] >= 0xC0 {
            pos += 2;
        } else {
            while pos < msg.len() {
                let l = msg[pos] as usize;
                pos += 1;
                if l == 0 {
                    break;
                }
                pos += l;
            }
        }
        if pos + 10 > msg.len() {
            break;
        }
        let rtype = (msg[pos] as usize) << 8 | msg[pos + 1] as usize;
        let rdlen = (msg[pos + 8] as usize) << 8 | msg[pos + 9] as usize;
        pos += 10;
        if rtype == 1 && rdlen == 4 && pos + 4 <= msg.len() {
            return Some(format!(
                "{}.{}.{}.{}",
                msg[pos],
                msg[pos + 1],
                msg[pos + 2],
                msg[pos + 3]
            ));
        }
        pos += rdlen;
    }
    None
}
