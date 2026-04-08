//go:build android

package main

/*
#include <stdint.h>

// Implemented in jni_bridge_android.c. Returns 1 on success, 0 on failure.
// Calls VpnService.protect(fd) on the cached global ref through an
// attached JNI thread.
int meow_jni_protect(int fd);
*/
import "C"

import (
	"syscall"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/log"
)

// installProtectHook wires mihomo's dialer.DefaultSocketHook so that
// every outbound TCP/UDP socket has VpnService.protect(fd) called on it
// before connect. Without this, proxy traffic would re-enter the TUN and
// loop forever.
//
// This is idempotent and safe to call multiple times.
func installProtectHook() {
	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		var protectErr error
		ctrlErr := conn.Control(func(fd uintptr) {
			if C.meow_jni_protect(C.int(fd)) == 0 {
				// protect() returning false is non-fatal — if the user
				// disallowed the VPN's own package the kernel still
				// routes correctly — but log it as a warning so unusual
				// cases surface.
				log.Warnln("meow: VpnService.protect(%d) returned false", int(fd))
			}
		})
		if ctrlErr != nil {
			protectErr = ctrlErr
		}
		return protectErr
	}
	log.Infoln("meow: protect hook installed")
}

// clearProtectHook drops the dialer hook. Called on engine shutdown so a
// later restart reinstalls a fresh hook against a fresh VpnService ref.
func clearProtectHook() {
	dialer.DefaultSocketHook = nil
}
