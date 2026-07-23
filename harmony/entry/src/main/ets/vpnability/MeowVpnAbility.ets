// VPN extension hosting the meow-rs engine + tun2socks — the HarmonyOS
// counterpart of the Android `bg/VpnService.kt`.
//
// Data flow (mirrors Android):
//   startVpn() → VpnConnection.create(TUN 172.19.0.1/30, DNS 172.19.0.2,
//   default route) → meow.startEngine() (loads config.yaml from the app
//   files dir, REST API on 127.0.0.1:9090) → meow.startTun2Socks(tunFd).
//   DNS at 172.19.0.2 routes into the TUN where the in-process fake-IP
//   resolver answers; every TCP/UDP flow is dispatched in-process by the
//   engine (see core/src/main/rust/mihomo-ffi-core/src/tun2socks.rs).
//
// Socket protection: meow.setProtectHandler bridges the engine's per-fd
// protect requests to VpnConnection.protect. NOTE meow-rs v0.18.0 does not
// yet invoke the hook on OpenHarmony (Android-only cfg upstream) — see
// mihomo-ohos-ffi/src/protect.rs. Until the engine bump lands, a default
// route through the TUN would loop proxy-server traffic.

// HarmonyOS NEXT-style Kit imports (@kit.*), per the API 12 conventions.
import { VpnExtensionAbility, vpnExtension } from '@kit.NetworkKit';
import { Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import meow from 'libmeow.so';

const TAG = 'meow-vpn';

export default class MeowVpnAbility extends VpnExtensionAbility {
  private connection: vpnExtension.VpnConnection | null = null;

  onCreate(want: Want): void {
    hilog.info(0x0001, TAG, 'MeowVpnAbility onCreate');
    this.startVpn();
  }

  onDestroy(): void {
    hilog.info(0x0001, TAG, 'MeowVpnAbility onDestroy');
    this.stopVpn();
  }

  private async startVpn(): Promise<void> {
    try {
      this.connection = vpnExtension.createVpnConnection(this.context);

      const config: vpnExtension.VpnConfig = {
        // Same addressing plan as Android VpnService.kt: TUN at
        // 172.19.0.1/30, DNS at 172.19.0.2 (routed into the TUN and
        // answered in-process by the engine's fake-IP resolver).
        addresses: [
          {
            address: { address: '172.19.0.1', family: 1 },
            prefixLength: 30,
          },
        ],
        routes: [
          {
            interface: 'vpn-tun',
            destination: {
              address: { address: '0.0.0.0', family: 1 },
              prefixLength: 0,
            },
            gateway: { address: '172.19.0.1', family: 1 },
            hasGateway: false,
            isDefaultRoute: true,
          },
        ],
        dnsAddresses: ['172.19.0.2'],
        mtu: 1500,
        isIPv4Accepted: true,
        isIPv6Accepted: false,
        trustedApplications: [],
        blockedApplications: [],
      };

      const tunFd: number = await this.connection.create(config);
      hilog.info(0x0001, TAG, 'TUN created, fd=%{public}d', tunFd);

      meow.init();
      // config.yaml + geodata live under the app files dir, mirroring the
      // Android home-dir layout (<filesDir>/meow/config.yaml).
      meow.setHomeDir(this.context.filesDir + '/meow');

      const connection = this.connection;
      meow.setProtectHandler((fd: number): boolean => {
        // VpnConnection.protect returns a Promise; fire it and report
        // optimistic success (see the protect bridge notes in
        // napi_init.cpp).
        connection?.protect(fd);
        return true;
      });

      let rc = meow.startEngine('127.0.0.1:9090', '');
      if (rc !== 0) {
        hilog.error(0x0001, TAG, 'startEngine failed: %{public}s', meow.getLastError());
        return;
      }
      rc = meow.startTun2Socks(tunFd);
      if (rc !== 0) {
        hilog.error(0x0001, TAG, 'startTun2Socks failed: %{public}s', meow.getLastError());
        meow.stopEngine();
        return;
      }
      hilog.info(0x0001, TAG, 'VPN up (%{public}s)', meow.version());
    } catch (err) {
      hilog.error(0x0001, TAG, 'startVpn failed: %{public}s', JSON.stringify(err));
    }
  }

  private async stopVpn(): Promise<void> {
    meow.stopEngine();
    if (this.connection !== null) {
      try {
        await this.connection.destroy();
      } catch (err) {
        hilog.error(0x0001, TAG, 'destroy failed: %{public}s', JSON.stringify(err));
      }
      this.connection = null;
    }
  }
}
