//go:build android

package main

/*
#cgo LDFLAGS: -llog
#include <android/log.h>
#include <stdlib.h>

// Wrapper so Go code doesn't have to include android/log.h directly
// inside an import "C" preamble (cgo limitation with variadics).
static inline void meow_alog(int level, const char *tag, const char *msg) {
    __android_log_write(level, tag, msg);
}
*/
import "C"

import (
	"sync"
	"unsafe"

	"github.com/metacubex/mihomo/log"
)

var (
	androidLogOnce sync.Once
	androidLogTag  = C.CString("mihomo")
)

const (
	androidLogVerbose = 2
	androidLogDebug   = 3
	androidLogInfo    = 4
	androidLogWarn    = 5
	androidLogError   = 6
)

func mihomoToAndroidPriority(lv log.LogLevel) C.int {
	switch lv {
	case log.DEBUG:
		return androidLogDebug
	case log.INFO, log.SILENT:
		return androidLogInfo
	case log.WARNING:
		return androidLogWarn
	case log.ERROR:
		return androidLogError
	}
	return androidLogInfo
}

// installAndroidLog drains mihomo's log event stream and forwards each
// entry to logcat. Safe to call multiple times.
func installAndroidLog() {
	androidLogOnce.Do(func() {
		log.SetLevel(log.INFO)
		sub := log.Subscribe()
		go func() {
			for evt := range sub {
				cmsg := C.CString(evt.Payload)
				C.meow_alog(mihomoToAndroidPriority(evt.LogLevel), androidLogTag, cmsg)
				C.free(unsafe.Pointer(cmsg))
			}
		}()
	})
}
