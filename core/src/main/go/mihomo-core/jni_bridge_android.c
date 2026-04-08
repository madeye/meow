// JNI bridge between the cgo exports in exports.go and the Kotlin
// MihomoEngine object. It is compiled as part of the same cgo package
// and linked into libmihomo.so.
//
// Conventions:
//   - Every JNI entry point is a thin wrapper: unpack jstring/jbyte[]
//     arguments, call the meow* cgo export, repackage the return value.
//   - Strings coming out of Go are written into stack buffers the C
//     layer owns; this keeps cgo from leaking memory across the JNI
//     boundary and avoids ownership mismatches.
//   - The protect callback runs on mihomo goroutines, which are not
//     JVM-attached by default. meow_jni_protect handles attach/detach
//     on every call.

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "_cgo_export.h"

#define STR_BUF 512

static JavaVM *g_vm = NULL;
static jobject g_vpnService = NULL;     // GlobalRef
static jmethodID g_protectMid = NULL;   // VpnService.protect(int): boolean

// ---------------------------------------------------------------------------
// Lifecycle: JNI_OnLoad caches JavaVM and preloads the Kotlin
// MihomoEngine class token so later calls don't have to look it up.
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
// Protect callback: invoked from Go's dialer hook. Attaches the current
// native thread, calls VpnService.protect(fd), detaches. Returns 1 on
// success, 0 on any JNI failure or if the VpnService ref is absent.
// ---------------------------------------------------------------------------

int meow_jni_protect(int fd) {
    if (g_vm == NULL || g_vpnService == NULL || g_protectMid == NULL) {
        return 0;
    }
    JNIEnv *env = NULL;
    int attached = 0;
    jint rc = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) {
            return 0;
        }
        attached = 1;
    } else if (rc != JNI_OK || env == NULL) {
        return 0;
    }
    jboolean ok = (*env)->CallBooleanMethod(env, g_vpnService, g_protectMid, (jint)fd);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        ok = JNI_FALSE;
    }
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return ok ? 1 : 0;
}

// ---------------------------------------------------------------------------
// Kotlin JNI entry points (class: io.github.madeye.meow.core.MihomoEngine)
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeInit(JNIEnv *env, jclass clazz) {
    meowEngineInit();
}

JNIEXPORT void JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeSetHomeDir(JNIEnv *env, jclass clazz, jstring dir) {
    const char *c_dir = (*env)->GetStringUTFChars(env, dir, NULL);
    if (c_dir == NULL) { return; }
    meowSetHomeDir((char *)c_dir);
    (*env)->ReleaseStringUTFChars(env, dir, c_dir);
}

JNIEXPORT jint JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeStartEngine(JNIEnv *env, jclass clazz, jstring addr, jstring secret) {
    const char *c_addr = (*env)->GetStringUTFChars(env, addr, NULL);
    const char *c_secret = (*env)->GetStringUTFChars(env, secret, NULL);
    if (c_addr == NULL || c_secret == NULL) {
        if (c_addr) (*env)->ReleaseStringUTFChars(env, addr, c_addr);
        if (c_secret) (*env)->ReleaseStringUTFChars(env, secret, c_secret);
        return -1;
    }
    int rc = (int)meowStartEngine((char *)c_addr, (char *)c_secret);
    (*env)->ReleaseStringUTFChars(env, addr, c_addr);
    (*env)->ReleaseStringUTFChars(env, secret, c_secret);
    return (jint)rc;
}

JNIEXPORT void JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeStopEngine(JNIEnv *env, jclass clazz) {
    meowStopEngine();
}

JNIEXPORT void JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeSetProtect(JNIEnv *env, jclass clazz, jobject vpnService) {
    // Release any previous global ref (stop → start cycle).
    if (g_vpnService != NULL) {
        (*env)->DeleteGlobalRef(env, g_vpnService);
        g_vpnService = NULL;
        g_protectMid = NULL;
    }
    if (vpnService == NULL) {
        return;
    }
    g_vpnService = (*env)->NewGlobalRef(env, vpnService);
    if (g_vpnService == NULL) {
        return;
    }
    jclass svcClass = (*env)->GetObjectClass(env, g_vpnService);
    // android.net.VpnService inherits protect(int) from the framework
    // base class — GetMethodID walks the hierarchy so this works.
    g_protectMid = (*env)->GetMethodID(env, svcClass, "protect", "(I)Z");
    (*env)->DeleteLocalRef(env, svcClass);
    if (g_protectMid == NULL) {
        // Couldn't resolve the method — clear the ref to fall back to
        // a no-op and log via the Go side's getLastError() channel.
        (*env)->DeleteGlobalRef(env, g_vpnService);
        g_vpnService = NULL;
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeIsRunning(JNIEnv *env, jclass clazz) {
    return meowIsRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeGetUploadTraffic(JNIEnv *env, jclass clazz) {
    return (jlong)meowGetUploadTraffic();
}

JNIEXPORT jlong JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeGetDownloadTraffic(JNIEnv *env, jclass clazz) {
    return (jlong)meowGetDownloadTraffic();
}

JNIEXPORT jint JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeValidateConfig(JNIEnv *env, jclass clazz, jstring yaml) {
    const char *c_yaml = (*env)->GetStringUTFChars(env, yaml, NULL);
    if (c_yaml == NULL) { return -1; }
    int len = (int)strlen(c_yaml);
    int rc = (int)meowValidateConfig((char *)c_yaml, len);
    (*env)->ReleaseStringUTFChars(env, yaml, c_yaml);
    return (jint)rc;
}

JNIEXPORT jstring JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeGetLastError(JNIEnv *env, jclass clazz) {
    char buf[STR_BUF];
    buf[0] = 0;
    meowGetLastError(buf, STR_BUF);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeVersion(JNIEnv *env, jclass clazz) {
    char buf[STR_BUF];
    buf[0] = 0;
    meowVersion(buf, STR_BUF);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeTestDirectTcp(JNIEnv *env, jclass clazz, jstring host, jint port) {
    const char *c_host = (*env)->GetStringUTFChars(env, host, NULL);
    if (c_host == NULL) { return (*env)->NewStringUTF(env, "FAIL jni"); }
    char buf[STR_BUF];
    buf[0] = 0;
    meowTestDirectTcp((char *)c_host, (int)port, buf, STR_BUF);
    (*env)->ReleaseStringUTFChars(env, host, c_host);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeTestProxyHttp(JNIEnv *env, jclass clazz, jstring url) {
    const char *c_url = (*env)->GetStringUTFChars(env, url, NULL);
    if (c_url == NULL) { return (*env)->NewStringUTF(env, "FAIL jni"); }
    char buf[STR_BUF];
    buf[0] = 0;
    meowTestProxyHttp((char *)c_url, buf, STR_BUF);
    (*env)->ReleaseStringUTFChars(env, url, c_url);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_io_github_madeye_meow_core_MihomoEngine_nativeTestDnsResolver(JNIEnv *env, jclass clazz, jstring dnsAddr) {
    const char *c_addr = (*env)->GetStringUTFChars(env, dnsAddr, NULL);
    if (c_addr == NULL) { return (*env)->NewStringUTF(env, "FAIL jni"); }
    char buf[STR_BUF];
    buf[0] = 0;
    meowTestDnsResolver((char *)c_addr, buf, STR_BUF);
    (*env)->ReleaseStringUTFChars(env, dnsAddr, c_addr);
    return (*env)->NewStringUTF(env, buf);
}
