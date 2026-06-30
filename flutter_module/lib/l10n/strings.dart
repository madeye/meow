import 'package:flutter/widgets.dart';

class S {
  static S of(BuildContext context) {
    final locale = Localizations.localeOf(context);
    return locale.languageCode == 'zh' ? _Zh() : S();
  }

  // App
  String get appName => 'Meow';

  // Home
  String get home => 'Home';
  String get notConnected => 'Not Connected';
  String get connecting => 'Connecting...';
  String get connected => 'Connected';
  String get disconnecting => 'Disconnecting...';
  String get disconnected => 'Disconnected';
  String get proxyNodes => 'Proxy Nodes';
  String get active => 'Active';
  String get upload => 'Upload';
  String get download => 'Download';
  String get noSubscriptionHint =>
      'No subscription selected.\nGo to Subscribe tab to add one.';

  // Proxy groups
  String get proxyGroups => 'Proxy Groups';
  String get urlTestAll => 'URL Test All';
  String get testing => 'Testing...';
  String get untested => '--';
  String get noGroups => 'No proxy groups found.\nConnect VPN to load groups.';
  String latencyMs(int ms) => '${ms}ms';

  // Subscriptions
  String get subscribe => 'Subscribe';
  String get subscriptions => 'Subscriptions';
  String get noSubscriptions => 'No subscriptions';
  String get addSubscription => 'Add Subscription';
  String get editSubscription => 'Edit Subscription';
  String get deleteSubscription => 'Delete Subscription';
  String deleteConfirm(String name) => 'Delete "$name"?';
  String get name => 'Name';
  String get subscriptionUrl => 'Subscription URL';
  String get pasteFromClipboard => 'Paste from clipboard';
  String get clipboardEmpty => 'Clipboard is empty';
  String get cancel => 'Cancel';
  String get save => 'Save';
  String get add => 'Add';
  String get delete => 'Delete';
  String get select => 'Select';
  String get edit => 'Edit';
  String get refresh => 'Refresh';
  String updated(String name) => '$name updated';
  String refreshFailed(String err) => 'Refresh failed: $err';
  String get proxies => 'proxies';
  String get editYaml => 'Edit YAML';
  String get yamlValid => 'Valid YAML';
  String yamlInvalid(int line, int col, String msg) =>
      'Line $line, Col $col: $msg';
  String configInvalid(String msg) => 'Invalid config: $msg';
  String get revert => 'Revert';
  String get revertConfirm => 'Revert to last downloaded version?';
  String get discardChanges => 'Discard unsaved changes?';
  String get discard => 'Discard';
  String get yamlSaved => 'YAML saved';
  String get yamlReverted => 'Reverted to original';

  // Traffic
  String get traffic => 'Traffic';
  String get currentSession => 'Current Session';
  String get total => 'Total';
  String get speedChart => 'Speed Chart';
  String get sessionSummary => 'Session Summary';
  String get collectingData => 'Collecting data...';
  String get connectToSeeTraffic => 'Connect VPN to see traffic';
  String get dataUsage => 'Data Usage';
  String get today => 'Today';
  String get thisMonth => 'This Month';
  String get dailyHistory => 'Daily History (30 days)';
  String get noHistoryData => 'No traffic history yet';
  String get tapBarHint => 'Tap a bar to see details';

  // Settings
  String get settings => 'Settings';
  String get general => 'General';
  String get version => 'Version';
  String get engine => 'Engine';
  String get network => 'Network';
  String get dnsServer => 'DNS Server';
  String get dnsBuiltIn => 'Plain TCP via tunnel';
  String get about => 'About';
  String get sourceCode => 'Source Code';
  String get sourceCodeUrl => 'github.com/madeye/meow';
  String get memoryUsage => 'Memory Usage';
  String memoryStats(int inuse, int limit) =>
      '${(inuse / 1048576).toStringAsFixed(1)} MB / ${(limit / 1048576).toStringAsFixed(1)} MB';

  // Logs
  String get logs => 'Logs';
  String get noLogs => 'No logs yet';
  String get logPause => 'Pause';
  String get logResume => 'Resume';
  String get copyAll => 'Copy All';
  String get autoScroll => 'Auto-scroll';
  String get clear => 'Clear';

  // Connections
  String get connections => 'Connections';
  String get noConnections => 'No active connections';
  String get closeAll => 'Close All';
  String get closeAllConfirm => 'Close all active connections?';
  String get filterConnections => 'Filter by host...';
  String get chain => 'Chain';
  String get rule => 'Rule';

  // Mode & Runtime
  String get mode => 'Mode';
  String get modeRule => 'Rule';
  String get modeGlobal => 'Global';
  String get modeDirect => 'Direct';
  String get runtimeConfig => 'Runtime';
  String get allowLan => 'Allow LAN';
  String get allowLanDesc =>
      'Allow devices on the local network to use this proxy';
  String get ipv6 => 'IPv6';
  String get ipv6Desc => 'Enable IPv6 support';

  // Rules
  String get rules => 'Rules';
  String get noRules => 'No rules found.\nConnect VPN to load rules.';
  String get filterRules => 'Filter by type, payload or proxy...';
  String rulesCount(int n) => '$n rules';
  String get rulesLoadError => 'Failed to load rules';
  String get retry => 'Retry';
  String get engineOffline => 'Engine offline';
  String get versionUnavailable => 'Not available';

  // Providers
  String get providers => 'Providers';
  String get proxyProviders => 'Proxy Providers';
  String get ruleProviders => 'Rule Providers';
  String get noProviders => 'No providers configured';
  String get update => 'Update';
  String get updating => 'Updating...';
  String providerProxyCount(int n) => '$n proxies';
  String providerRuleCount(int n) => '$n rules';
  String get providerUpdated => 'Updated';

  // Per-App Proxy
  String get perAppProxy => 'Per-App Proxy';
  String get perAppProxyDesc => 'Select which apps use the VPN';
  String get perAppModeProxy => 'Proxy Selected';
  String get perAppModeBypass => 'Bypass Selected';
  String get perAppSearch => 'Search apps...';
  String get perAppShowSystem => 'Show system apps';
  String get perAppSelectAll => 'Select All';
  String get perAppDeselectAll => 'Deselect All';
  String perAppSelected(int count) => '$count selected';
  String get perAppDisabledHint => 'Disabled when no apps selected';
  String get perAppRestartRequired => 'Reconnect VPN to apply changes';
}

class _Zh extends S {
  @override
  String get appName => 'Meow';

  // Home
  @override
  String get home => '首页';
  @override
  String get notConnected => '未连接';
  @override
  String get connecting => '连接中...';
  @override
  String get connected => '已连接';
  @override
  String get disconnecting => '断开中...';
  @override
  String get disconnected => '已断开';
  @override
  String get proxyNodes => '代理节点';
  @override
  String get active => '使用中';
  @override
  String get upload => '上传';
  @override
  String get download => '下载';
  @override
  String get noSubscriptionHint => '未选择订阅\n请前往订阅页面添加';

  // Proxy groups
  @override
  String get proxyGroups => '代理分组';
  @override
  String get urlTestAll => '全部测速';
  @override
  String get testing => '测速中...';
  @override
  String get untested => '--';
  @override
  String get noGroups => '未找到代理分组。\n连接 VPN 以加载分组。';
  @override
  String latencyMs(int ms) => '${ms}ms';

  // Subscriptions
  @override
  String get subscribe => '订阅';
  @override
  String get subscriptions => '订阅管理';
  @override
  String get noSubscriptions => '暂无订阅';
  @override
  String get addSubscription => '添加订阅';
  @override
  String get editSubscription => '编辑订阅';
  @override
  String get deleteSubscription => '删除订阅';
  @override
  String deleteConfirm(String name) => '确定删除 "$name"？';
  @override
  String get name => '名称';
  @override
  String get subscriptionUrl => '订阅链接';
  @override
  String get pasteFromClipboard => '从剪贴板粘贴';
  @override
  String get clipboardEmpty => '剪贴板为空';
  @override
  String get cancel => '取消';
  @override
  String get save => '保存';
  @override
  String get add => '添加';
  @override
  String get delete => '删除';
  @override
  String get select => '选择';
  @override
  String get edit => '编辑';
  @override
  String get refresh => '刷新';
  @override
  String updated(String name) => '$name 已更新';
  @override
  String refreshFailed(String err) => '刷新失败：$err';
  @override
  String get proxies => '个节点';
  @override
  String get editYaml => '编辑 YAML';
  @override
  String get yamlValid => 'YAML 格式正确';
  @override
  String yamlInvalid(int line, int col, String msg) => '第 $line 行第 $col 列：$msg';
  @override
  String configInvalid(String msg) => '配置无效：$msg';
  @override
  String get revert => '还原';
  @override
  String get revertConfirm => '还原为最近下载的版本？';
  @override
  String get discardChanges => '放弃未保存的更改？';
  @override
  String get discard => '放弃';
  @override
  String get yamlSaved => 'YAML 已保存';
  @override
  String get yamlReverted => '已还原';

  // Traffic
  @override
  String get traffic => '流量';
  @override
  String get currentSession => '当前会话';
  @override
  String get total => '合计';
  @override
  String get speedChart => '速度图表';
  @override
  String get sessionSummary => '会话统计';
  @override
  String get collectingData => '正在收集数据...';
  @override
  String get connectToSeeTraffic => '连接 VPN 查看流量';
  @override
  String get dataUsage => '流量统计';
  @override
  String get today => '今日';
  @override
  String get thisMonth => '本月';
  @override
  String get dailyHistory => '每日流量（30 天）';
  @override
  String get noHistoryData => '暂无流量记录';
  @override
  String get tapBarHint => '点击柱状图查看详情';

  // Settings
  @override
  String get settings => '设置';
  @override
  String get general => '通用';
  @override
  String get version => '版本';
  @override
  String get engine => '引擎';
  @override
  String get network => '网络';
  @override
  String get dnsServer => 'DNS 服务器';
  @override
  String get dnsBuiltIn => '通过隧道走纯 TCP';
  @override
  String get about => '关于';
  @override
  String get sourceCode => '源代码';
  @override
  String get sourceCodeUrl => 'github.com/madeye/meow';
  @override
  String get memoryUsage => '内存使用';
  @override
  String memoryStats(int inuse, int limit) =>
      '${(inuse / 1048576).toStringAsFixed(1)} MB / ${(limit / 1048576).toStringAsFixed(1)} MB';

  // Logs
  @override
  String get logs => '日志';
  @override
  String get noLogs => '暂无日志';
  @override
  String get logPause => '暂停';
  @override
  String get logResume => '继续';
  @override
  String get copyAll => '复制全部';
  @override
  String get autoScroll => '自动滚动';
  @override
  String get clear => '清除';
  @override
  String get connections => '连接';
  @override
  String get noConnections => '无活跃连接';
  @override
  String get closeAll => '关闭全部';
  @override
  String get closeAllConfirm => '关闭所有活跃连接？';
  @override
  String get filterConnections => '按主机过滤...';
  @override
  String get chain => '链路';
  @override
  String get rule => '规则';

  // Mode & Runtime
  @override
  String get mode => '模式';
  @override
  String get modeRule => '规则';
  @override
  String get modeGlobal => '全局';
  @override
  String get modeDirect => '直连';
  @override
  String get runtimeConfig => '运行时';
  @override
  String get allowLan => '允许局域网';
  @override
  String get allowLanDesc => '允许局域网内设备使用此代理';
  @override
  String get ipv6 => 'IPv6';
  @override
  String get ipv6Desc => '启用 IPv6 支持';

  // Rules
  @override
  String get rules => '规则';
  @override
  String get noRules => '未找到规则。\n连接 VPN 以加载规则。';
  @override
  String get filterRules => '按类型、载荷或代理过滤...';
  @override
  String rulesCount(int n) => '$n 条规则';
  @override
  String get rulesLoadError => '加载规则失败';
  @override
  String get retry => '重试';
  @override
  String get engineOffline => '引擎离线';
  @override
  String get versionUnavailable => '不可用';

  // Providers
  @override
  String get providers => '提供者';
  @override
  String get proxyProviders => '代理提供者';
  @override
  String get ruleProviders => '规则提供者';
  @override
  String get noProviders => '未配置提供者';
  @override
  String get update => '更新';
  @override
  String get updating => '更新中...';
  @override
  String providerProxyCount(int n) => '$n 个节点';
  @override
  String providerRuleCount(int n) => '$n 条规则';
  @override
  String get providerUpdated => '已更新';

  // Per-App Proxy
  @override
  String get perAppProxy => '分应用代理';
  @override
  String get perAppProxyDesc => '选择哪些应用使用 VPN';
  @override
  String get perAppModeProxy => '仅代理选中';
  @override
  String get perAppModeBypass => '绕过选中';
  @override
  String get perAppSearch => '搜索应用...';
  @override
  String get perAppShowSystem => '显示系统应用';
  @override
  String get perAppSelectAll => '全选';
  @override
  String get perAppDeselectAll => '取消全选';
  @override
  String perAppSelected(int count) => '已选 $count 个';
  @override
  String get perAppDisabledHint => '未选择应用时不生效';
  @override
  String get perAppRestartRequired => '重新连接 VPN 以应用更改';
}
