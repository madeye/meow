// ArkTS declarations for the NAPI module in napi_init.cpp (libmeow.so).
// Return codes follow the Rust C ABI: 0 = success, -1 = failure with the
// message available via getLastError().

export const init: () => void;
export const setHomeDir: (dir: string) => void;
export const setProtectHandler: (handler: (fd: number) => boolean) => void;
export const startEngine: (externalController: string, secret: string) => number;
export const stopEngine: () => void;
export const startTun2Socks: (fd: number) => number;
export const isRunning: () => boolean;
export const getUploadTraffic: () => number;
export const getDownloadTraffic: () => number;
export const validateConfig: (yaml: string) => number;
export const getLastError: () => string;
export const getLogs: () => string;
export const version: () => string;
export const testDirectTcp: (host: string, port: number) => string;
export const testDnsResolver: (dnsAddr: string) => string;
