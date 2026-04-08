//go:build !android || !cgo

package main

// installAndroidLog is a no-op on non-Android builds. The real
// implementation lives in android_log.go and is gated behind a build
// tag so `go vet` on a developer host (Linux/macOS) doesn't try to
// include <android/log.h>.
func installAndroidLog() {}
