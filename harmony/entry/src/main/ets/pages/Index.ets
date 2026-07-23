// Minimal home page: VPN toggle + traffic readout.
//
// The engine runs in the VPN extension process; this UI process controls it
// via startVpnExtensionAbility/stopVpnExtensionAbility and reads live state
// from the engine's REST API on 127.0.0.1:9090 (same pattern as the Flutter
// UI on Android — see flutter_module/lib/services/mihomo_api.dart). A full
// UI port (subscriptions, node picker, connections/logs/rules) tracks the
// Flutter feature set; this page is the functional seed.

// HarmonyOS NEXT-style Kit imports (@kit.*), per the API 12 conventions.
import { vpnExtension, http } from '@kit.NetworkKit';
import { Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const TAG = 'meow-ui';

interface TrafficSnapshot {
  up: number;
  down: number;
}

@Entry
@Component
struct Index {
  @State connected: boolean = false;
  @State busy: boolean = false;
  @State traffic: TrafficSnapshot = { up: 0, down: 0 };
  private pollTimer: number = -1;

  private vpnWant: Want = {
    deviceId: '',
    bundleName: 'io.github.madeye.meow',
    abilityName: 'MeowVpnAbility',
  };

  aboutToDisappear(): void {
    this.stopPolling();
  }

  private startPolling(): void {
    this.stopPolling();
    this.pollTimer = setInterval(() => {
      this.fetchTraffic();
    }, 2000);
  }

  private stopPolling(): void {
    if (this.pollTimer >= 0) {
      clearInterval(this.pollTimer);
      this.pollTimer = -1;
    }
  }

  private fetchTraffic(): void {
    const request = http.createHttp();
    request.request('http://127.0.0.1:9090/traffic', {
      method: http.RequestMethod.GET,
      readTimeout: 1500,
      connectTimeout: 1500,
    }).then((resp) => {
      if (resp.responseCode === 200 && typeof resp.result === 'string') {
        const line = (resp.result as string).split('\n')[0];
        const parsed = JSON.parse(line) as TrafficSnapshot;
        this.traffic = parsed;
      }
      request.destroy();
    }).catch(() => {
      request.destroy();
    });
  }

  private async toggleVpn(on: boolean): Promise<void> {
    if (this.busy) {
      return;
    }
    this.busy = true;
    try {
      if (on) {
        await vpnExtension.startVpnExtensionAbility(this.vpnWant);
        this.connected = true;
        this.startPolling();
      } else {
        await vpnExtension.stopVpnExtensionAbility(this.vpnWant);
        this.connected = false;
        this.stopPolling();
        this.traffic = { up: 0, down: 0 };
      }
    } catch (err) {
      hilog.error(0x0001, TAG, 'toggleVpn failed: %{public}s', JSON.stringify(err));
      this.connected = !on;
    }
    this.busy = false;
  }

  private formatBytes(value: number): string {
    if (value >= 1024 * 1024) {
      return (value / (1024 * 1024)).toFixed(1) + ' MB/s';
    }
    if (value >= 1024) {
      return (value / 1024).toFixed(1) + ' KB/s';
    }
    return value.toFixed(0) + ' B/s';
  }

  build() {
    Column({ space: 24 }) {
      Text('Meow')
        .fontSize(28)
        .fontWeight(FontWeight.Bold)
        .margin({ top: 48 })

      Row({ space: 12 }) {
        Text(this.connected ? 'Connected' : 'Disconnected')
          .fontSize(18)
        Toggle({ type: ToggleType.Switch, isOn: this.connected })
          .onChange((on: boolean) => {
            this.toggleVpn(on);
          })
      }

      Row({ space: 24 }) {
        Column({ space: 4 }) {
          Text('Upload').fontSize(14).opacity(0.6)
          Text(this.formatBytes(this.traffic.up)).fontSize(16)
        }
        Column({ space: 4 }) {
          Text('Download').fontSize(14).opacity(0.6)
          Text(this.formatBytes(this.traffic.down)).fontSize(16)
        }
      }
      .visibility(this.connected ? Visibility.Visible : Visibility.Hidden)

      Blank()
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }
}
