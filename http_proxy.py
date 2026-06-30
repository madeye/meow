#!/usr/bin/env python3
"""Minimal threaded HTTP forward proxy with CONNECT (HTTPS tunneling) support.

Logs every CONNECT/absolute-URI request to stdout (line-buffered) so an E2E
harness can prove that traffic actually flowed through this proxy.

Usage: http_proxy.py <host> <port>
"""
import socket
import sys
import threading
import select
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "0.0.0.0"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8889

_lock = threading.Lock()


def log(msg):
    with _lock:
        print(f"{time.strftime('%H:%M:%S')} {msg}", flush=True)


def pipe(a, b):
    """Bidirectionally forward bytes between sockets a and b until EOF."""
    socks = [a, b]
    try:
        while True:
            r, _, x = select.select(socks, [], socks, 30)
            if x or not r:
                break
            for s in r:
                try:
                    data = s.recv(65536)
                except OSError:
                    return
                if not data:
                    return
                dst = b if s is a else a
                try:
                    dst.sendall(data)
                except OSError:
                    return
    finally:
        for s in socks:
            try:
                s.close()
            except OSError:
                pass


def read_headers(conn):
    """Read until end of HTTP headers; return raw bytes."""
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = conn.recv(4096)
        if not chunk:
            break
        buf += chunk
        if len(buf) > 65536:
            break
    return buf


def handle(conn, addr):
    try:
        conn.settimeout(30)
        raw = read_headers(conn)
        if not raw:
            conn.close()
            return
        first = raw.split(b"\r\n", 1)[0].decode("latin-1", "replace")
        parts = first.split()
        if len(parts) < 2:
            conn.close()
            return
        method, target = parts[0], parts[1]

        if method.upper() == "CONNECT":
            # target is host:port
            host, _, port_s = target.rpartition(":")
            port = int(port_s) if port_s.isdigit() else 443
            log(f"CONNECT {host}:{port}  (from {addr[0]})")
            try:
                upstream = socket.create_connection((host, port), timeout=15)
            except OSError as e:
                log(f"  ! CONNECT {host}:{port} failed: {e}")
                conn.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
                conn.close()
                return
            conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            pipe(conn, upstream)
        else:
            # Absolute-URI plain HTTP request, e.g. GET http://host/path
            log(f"{method} {target}  (from {addr[0]})")
            # Parse host from absolute URI
            if "://" in target:
                rest = target.split("://", 1)[1]
                hostport = rest.split("/", 1)[0]
                path = "/" + (rest.split("/", 1)[1] if "/" in rest else "")
            else:
                conn.close()
                return
            host, _, port_s = hostport.partition(":")
            port = int(port_s) if port_s.isdigit() else 80
            try:
                upstream = socket.create_connection((host, port), timeout=15)
            except OSError as e:
                log(f"  ! {method} {host}:{port} failed: {e}")
                conn.close()
                return
            # Rewrite request line to origin-form and forward.
            rebuilt = raw.replace(target.encode(), path.encode(), 1)
            upstream.sendall(rebuilt)
            pipe(conn, upstream)
    except Exception as e:  # noqa: BLE001 - keep the proxy alive on any error
        log(f"  ! handler error from {addr}: {e}")
        try:
            conn.close()
        except OSError:
            pass


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(128)
    log(f"PROXY listening on {HOST}:{PORT}")
    while True:
        try:
            conn, addr = srv.accept()
        except OSError:
            break
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
