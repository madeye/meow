//go:build !android || !cgo

package main

// installProtectHook / clearProtectHook are no-ops on non-Android builds.
// The real implementations live in protect.go behind a build tag so
// `go vet` on a developer host doesn't try to link against the JNI
// bridge C shim (which is only compiled when GOOS=android).
func installProtectHook() {}

func clearProtectHook() {}
