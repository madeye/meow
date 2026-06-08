import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import '../l10n/strings.dart';
import '../services/vpn_channel.dart';
import '../services/traffic_history.dart';
import '../models/vpn_state.dart';
import '../models/traffic_stats.dart';
import '../theme/app_theme.dart';

class TrafficScreen extends StatefulWidget {
  const TrafficScreen({super.key});

  @override
  State<TrafficScreen> createState() => _TrafficScreenState();
}

class _TrafficScreenState extends State<TrafficScreen> {
  final _vpn = VpnChannel.instance;
  final _history = TrafficHistory.instance;
  VpnState _state = VpnState.stopped;
  TrafficStats _traffic = const TrafficStats();
  final List<_TrafficSample> _samples = [];
  StreamSubscription? _stateSub;
  StreamSubscription? _trafficSub;
  int _sessionUpload = 0;
  int _sessionDownload = 0;
  int _trafficUpdateCount = 0;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    await _history.load();
    if (mounted) setState(() {});
    _loadState();
    _stateSub = _vpn.stateStream.listen((s) {
      if (!mounted) return;
      setState(() => _state = s);
      if (s == VpnState.connected) {
        _sessionUpload = 0;
        _sessionDownload = 0;
        _samples.clear();
      }
    });
    _trafficSub = _vpn.trafficStream.listen((t) {
      if (!mounted) return;
      setState(() {
        _traffic = t;
        _sessionUpload = t.txTotal;
        _sessionDownload = t.rxTotal;
        _samples.add(
          _TrafficSample(
            time: DateTime.now(),
            txRate: t.txRate,
            rxRate: t.rxRate,
          ),
        );
        if (_samples.length > 60) _samples.removeAt(0);
      });
      // Reload history from DB every 10 updates (~10 seconds)
      _trafficUpdateCount++;
      if (_trafficUpdateCount % 10 == 0) {
        _history.load().then((_) {
          if (mounted) setState(() {});
        });
      }
    });
  }

  Future<void> _loadState() async {
    try {
      final state = await _vpn.getState();
      if (mounted) setState(() => _state = state);
    } catch (_) {}
  }

  @override
  void dispose() {
    _stateSub?.cancel();
    _trafficSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final isOn = _state == VpnState.connected;
    final todayTraffic = _history.today;
    final monthTraffic = _history.thisMonth;
    final theme = Theme.of(context);
    final meow = theme.extension<MeowColors>()!;

    return Scaffold(
      appBar: AppBar(title: Text(s.traffic)),
      body: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        children: [
          // Connection status
          _StatusIndicator(connected: isOn),
          const SizedBox(height: 16),

          // Today & This Month
          _SectionTitle(s.dataUsage),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: _UsageCard(
                  label: s.today,
                  icon: Icons.today,
                  color: meow.upload,
                  tx: todayTraffic.tx,
                  rx: todayTraffic.rx,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _UsageCard(
                  label: s.thisMonth,
                  icon: Icons.calendar_month,
                  color: theme.colorScheme.tertiary,
                  tx: monthTraffic.tx,
                  rx: monthTraffic.rx,
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),

          // Daily history chart
          _SectionTitle(s.dailyHistory),
          const SizedBox(height: 8),
          SizedBox(
            height: 220,
            child: _history.days.isEmpty
                ? Center(
                    child: Text(
                      s.noHistoryData,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  )
                : _DailyChart(days: _history.days),
          ),
          const SizedBox(height: 24),

          // Current session
          _SectionTitle(s.currentSession),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: _StatCard(
                  icon: Icons.arrow_upward,
                  color: meow.upload,
                  label: s.upload,
                  value: _formatBytes(_sessionUpload),
                  rate: _traffic.txRateStr,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _StatCard(
                  icon: Icons.arrow_downward,
                  color: meow.download,
                  label: s.download,
                  value: _formatBytes(_sessionDownload),
                  rate: _traffic.rxRateStr,
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),

          // Speed chart
          _SectionTitle(s.speedChart),
          const SizedBox(height: 8),
          SizedBox(
            height: 200,
            child: _samples.length < 2
                ? Center(
                    child: Text(
                      isOn ? s.collectingData : s.connectToSeeTraffic,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  )
                : _SpeedChart(samples: _samples),
          ),
          const SizedBox(height: 16),
        ],
      ),
    );
  }

  static String _formatBytes(int bytes) {
    if (bytes >= 1073741824) {
      return '${(bytes / 1073741824).toStringAsFixed(2)} GB';
    }
    if (bytes >= 1048576) return '${(bytes / 1048576).toStringAsFixed(2)} MB';
    if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '$bytes B';
  }
}

class _TrafficSample {
  final DateTime time;
  final int txRate;
  final int rxRate;
  const _TrafficSample({
    required this.time,
    required this.txRate,
    required this.rxRate,
  });
}

// --- Widgets ---

class _StatusIndicator extends StatelessWidget {
  final bool connected;
  const _StatusIndicator({required this.connected});

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final theme = Theme.of(context);
    final meow = theme.extension<MeowColors>()!;
    final color = connected
        ? meow.connected
        : theme.colorScheme.onSurfaceVariant;
    return Row(
      children: [
        Container(
          width: 10,
          height: 10,
          decoration: BoxDecoration(shape: BoxShape.circle, color: color),
        ),
        const SizedBox(width: 8),
        Text(
          connected ? s.connected : s.disconnected,
          style: TextStyle(color: color, fontSize: 13),
        ),
      ],
    );
  }
}

class _SectionTitle extends StatelessWidget {
  final String title;
  const _SectionTitle(this.title);

  @override
  Widget build(BuildContext context) {
    return Text(
      title,
      style: TextStyle(
        color: Theme.of(context).colorScheme.primary,
        fontWeight: FontWeight.w600,
        fontSize: 13,
      ),
    );
  }
}

class _UsageCard extends StatelessWidget {
  final String label;
  final IconData icon;
  final Color color;
  final int tx;
  final int rx;

  const _UsageCard({
    required this.label,
    required this.icon,
    required this.color,
    required this.tx,
    required this.rx,
  });

  @override
  Widget build(BuildContext context) {
    final meow = Theme.of(context).extension<MeowColors>()!;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: color, size: 18),
                const SizedBox(width: 6),
                Text(
                  label,
                  style: TextStyle(
                    color: color,
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              _TrafficScreenState._formatBytes(tx + rx),
              style: const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w700,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
            const SizedBox(height: 4),
            Row(
              children: [
                Icon(
                  Icons.arrow_upward,
                  size: 12,
                  color: meow.upload.withAlpha(180),
                ),
                const SizedBox(width: 2),
                Text(
                  _TrafficScreenState._formatBytes(tx),
                  style: TextStyle(
                    fontSize: 11,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(width: 8),
                Icon(
                  Icons.arrow_downward,
                  size: 12,
                  color: meow.download.withAlpha(180),
                ),
                const SizedBox(width: 2),
                Text(
                  _TrafficScreenState._formatBytes(rx),
                  style: TextStyle(
                    fontSize: 11,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _StatCard extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String label;
  final String value;
  final String rate;

  const _StatCard({
    required this.icon,
    required this.color,
    required this.label,
    required this.value,
    required this.rate,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(icon, color: color, size: 28),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: TextStyle(
                      color: color,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    value,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                ],
              ),
            ),
            Text(
              rate,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                fontSize: 12,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// --- Daily bar chart (interactive) ---

class _DailyChart extends StatefulWidget {
  final List<DailyTraffic> days;
  const _DailyChart({required this.days});

  @override
  State<_DailyChart> createState() => _DailyChartState();
}

class _DailyChartState extends State<_DailyChart> {
  int? _selectedIndex;

  List<DailyTraffic> _buildAllDays() {
    final now = DateTime.now();
    final allDays = <DailyTraffic>[];
    for (var i = 29; i >= 0; i--) {
      final dt = now.subtract(Duration(days: i));
      final key =
          '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')}';
      final entry = widget.days.cast<DailyTraffic?>().firstWhere(
        (d) => d!.date == key,
        orElse: () => null,
      );
      allDays.add(entry ?? DailyTraffic(date: key));
    }
    return allDays;
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final meow = Theme.of(context).extension<MeowColors>()!;
    final allDays = _buildAllDays();
    final selected = _selectedIndex != null && _selectedIndex! < allDays.length
        ? allDays[_selectedIndex!]
        : null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Tooltip for selected day
        SizedBox(
          height: 36,
          child: selected != null
              ? Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Row(
                    children: [
                      Text(
                        selected.date,
                        style: const TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Icon(
                        Icons.arrow_upward,
                        size: 11,
                        color: meow.upload.withAlpha(200),
                      ),
                      const SizedBox(width: 2),
                      Text(
                        _TrafficScreenState._formatBytes(selected.tx),
                        style: TextStyle(
                          fontSize: 11,
                          color: meow.upload,
                          fontFeatures: const [FontFeature.tabularFigures()],
                        ),
                      ),
                      const SizedBox(width: 10),
                      Icon(
                        Icons.arrow_downward,
                        size: 11,
                        color: meow.download.withAlpha(200),
                      ),
                      const SizedBox(width: 2),
                      Text(
                        _TrafficScreenState._formatBytes(selected.rx),
                        style: TextStyle(
                          fontSize: 11,
                          color: meow.download,
                          fontFeatures: const [FontFeature.tabularFigures()],
                        ),
                      ),
                      const SizedBox(width: 10),
                      Icon(
                        Icons.swap_vert,
                        size: 11,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                      const SizedBox(width: 2),
                      Text(
                        _TrafficScreenState._formatBytes(selected.total),
                        style: TextStyle(
                          fontSize: 11,
                          color: Theme.of(context).colorScheme.onSurface,
                          fontWeight: FontWeight.w600,
                          fontFeatures: const [FontFeature.tabularFigures()],
                        ),
                      ),
                    ],
                  ),
                )
              : Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text(
                    'Tap a bar to see details',
                    style: TextStyle(
                      fontSize: 11,
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
        ),
        // Chart
        Expanded(
          child: GestureDetector(
            onTapDown: (details) {
              final box = context.findRenderObject() as RenderBox?;
              if (box == null) return;
              final localX = details.localPosition.dx;
              final leftMargin = 44.0;
              final chartW = box.size.width - leftMargin;
              if (localX < leftMargin) return;
              final index = ((localX - leftMargin) / chartW * 30).floor().clamp(
                0,
                29,
              );
              setState(() {
                _selectedIndex = _selectedIndex == index ? null : index;
              });
            },
            child: CustomPaint(
              size: Size.infinite,
              painter: _DailyChartPainter(
                days: allDays,
                selectedIndex: _selectedIndex,
                gridColor: cs.outlineVariant,
                labelColor: cs.onSurfaceVariant,
                emphasisColor: cs.onSurface,
                highlightColor: cs.onSurface.withValues(alpha: 0.06),
                uploadColor: meow.upload,
                downloadColor: meow.download,
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _DailyChartPainter extends CustomPainter {
  final List<DailyTraffic> days;
  final int? selectedIndex;
  final Color gridColor;
  final Color labelColor;
  final Color emphasisColor;
  final Color highlightColor;
  final Color uploadColor;
  final Color downloadColor;
  _DailyChartPainter({
    required this.days,
    this.selectedIndex,
    required this.gridColor,
    required this.labelColor,
    required this.emphasisColor,
    required this.highlightColor,
    required this.uploadColor,
    required this.downloadColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    final h = size.height;
    final bottomMargin = 28.0;
    final leftMargin = 44.0;
    final chartW = w - leftMargin;
    final chartH = h - bottomMargin;

    // Find max
    int maxTotal = 1;
    for (final d in days) {
      maxTotal = max(maxTotal, d.total);
    }

    // Grid
    final gridPaint = Paint()
      ..color = gridColor
      ..strokeWidth = 0.5;
    for (var i = 0; i <= 4; i++) {
      final y = (chartH / 4) * i;
      canvas.drawLine(Offset(leftMargin, y), Offset(w, y), gridPaint);
    }

    // Y-axis labels
    final labelStyle = TextStyle(
      color: labelColor,
      fontSize: 9,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    for (var i = 0; i <= 4; i++) {
      final val = maxTotal * (4 - i) ~/ 4;
      final y = (chartH / 4) * i;
      _drawText(
        canvas,
        _formatBytes(val),
        Offset(0, y - 6),
        labelStyle,
        leftMargin - 4,
      );
    }

    // Bars
    final barCount = days.length;
    final barWidth = (chartW / barCount) * 0.7;
    final gap = (chartW / barCount) * 0.3;

    for (var i = 0; i < barCount; i++) {
      final d = days[i];
      final x = leftMargin + (chartW / barCount) * i + gap / 2;
      final isSelected = i == selectedIndex;

      // Download on bottom, upload on top.
      final rxH = (d.rx / maxTotal) * chartH;
      final txH = (d.tx / maxTotal) * chartH;

      final rxAlpha = isSelected ? 255 : 140;
      final txAlpha = isSelected ? 255 : 140;

      // Highlight background for selected bar
      if (isSelected) {
        final highlightRect = Rect.fromLTWH(
          leftMargin + (chartW / barCount) * i,
          0,
          chartW / barCount,
          chartH,
        );
        canvas.drawRect(highlightRect, Paint()..color = highlightColor);
      }

      // Download bar
      if (rxH > 0) {
        final rxRect = RRect.fromRectAndRadius(
          Rect.fromLTWH(x, chartH - rxH, barWidth, rxH),
          const Radius.circular(1.5),
        );
        canvas.drawRRect(
          rxRect,
          Paint()..color = downloadColor.withAlpha(rxAlpha),
        );
      }

      // Upload bar (stacked on top)
      if (txH > 0) {
        final txRect = RRect.fromRectAndRadius(
          Rect.fromLTWH(x, chartH - rxH - txH, barWidth, txH),
          const Radius.circular(1.5),
        );
        canvas.drawRRect(
          txRect,
          Paint()..color = uploadColor.withAlpha(txAlpha),
        );
      }

      // X-axis labels (show every 5 days, last day, and selected)
      if (i % 5 == 0 || i == barCount - 1 || isSelected) {
        final dateLabel = d.date.substring(5); // MM-DD
        _drawText(
          canvas,
          dateLabel,
          Offset(x - 2, chartH + 4),
          TextStyle(
            color: isSelected ? emphasisColor : labelColor,
            fontSize: 8,
            fontWeight: isSelected ? FontWeight.w600 : FontWeight.normal,
          ),
          40,
        );
      }
    }

    // Legend
    final legendY = h - 8;
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(w / 2 - 80, legendY - 5, 10, 10),
        const Radius.circular(2),
      ),
      Paint()..color = uploadColor.withAlpha(180),
    );
    _drawText(
      canvas,
      'Upload',
      Offset(w / 2 - 66, legendY - 7),
      TextStyle(color: labelColor, fontSize: 10),
      50,
    );

    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(w / 2 + 10, legendY - 5, 10, 10),
        const Radius.circular(2),
      ),
      Paint()..color = downloadColor.withAlpha(180),
    );
    _drawText(
      canvas,
      'Download',
      Offset(w / 2 + 24, legendY - 7),
      TextStyle(color: labelColor, fontSize: 10),
      60,
    );
  }

  void _drawText(
    Canvas canvas,
    String text,
    Offset offset,
    TextStyle style,
    double maxWidth,
  ) {
    final tp = TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: TextDirection.ltr,
      maxLines: 1,
    )..layout(maxWidth: maxWidth);
    tp.paint(canvas, offset);
  }

  static String _formatBytes(int bytes) {
    if (bytes >= 1073741824) {
      return '${(bytes / 1073741824).toStringAsFixed(1)}G';
    }
    if (bytes >= 1048576) return '${(bytes / 1048576).toStringAsFixed(0)}M';
    if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(0)}K';
    return '${bytes}B';
  }

  @override
  bool shouldRepaint(covariant _DailyChartPainter oldDelegate) =>
      oldDelegate.selectedIndex != selectedIndex || oldDelegate.days != days;
}

// --- Speed chart ---

class _SpeedChart extends StatelessWidget {
  final List<_TrafficSample> samples;
  const _SpeedChart({required this.samples});

  @override
  Widget build(BuildContext context) {
    if (samples.isEmpty) return const SizedBox();

    final cs = Theme.of(context).colorScheme;
    final meow = Theme.of(context).extension<MeowColors>()!;
    int maxRate = 1024;
    for (final s in samples) {
      maxRate = max(maxRate, max(s.txRate, s.rxRate));
    }

    return CustomPaint(
      size: const Size(double.infinity, 200),
      painter: _ChartPainter(
        samples: samples,
        maxRate: maxRate,
        gridColor: cs.outlineVariant,
        labelColor: cs.onSurfaceVariant,
        uploadColor: meow.upload,
        downloadColor: meow.download,
      ),
    );
  }
}

class _ChartPainter extends CustomPainter {
  final List<_TrafficSample> samples;
  final int maxRate;
  final Color gridColor;
  final Color labelColor;
  final Color uploadColor;
  final Color downloadColor;

  _ChartPainter({
    required this.samples,
    required this.maxRate,
    required this.gridColor,
    required this.labelColor,
    required this.uploadColor,
    required this.downloadColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (samples.length < 2) return;

    final w = size.width;
    final h = size.height;
    final margin = 40.0;
    final chartW = w - margin;
    final chartH = h - 24;

    final gridPaint = Paint()
      ..color = gridColor
      ..strokeWidth = 0.5;
    for (var i = 0; i <= 4; i++) {
      final y = (chartH / 4) * i;
      canvas.drawLine(Offset(margin, y), Offset(w, y), gridPaint);
    }

    final labelStyle = TextStyle(
      color: labelColor,
      fontSize: 10,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    for (var i = 0; i <= 4; i++) {
      final val = maxRate * (4 - i) / 4;
      final y = (chartH / 4) * i;
      _drawText(
        canvas,
        _formatRate(val.toInt()),
        Offset(0, y - 6),
        labelStyle,
        margin - 4,
      );
    }

    _drawLine(
      canvas,
      samples.map((s) => s.txRate).toList(),
      uploadColor,
      margin,
      chartW,
      chartH,
    );
    _drawLine(
      canvas,
      samples.map((s) => s.rxRate).toList(),
      downloadColor,
      margin,
      chartW,
      chartH,
    );
    _drawLegend(canvas, size);
  }

  void _drawLine(
    Canvas canvas,
    List<int> values,
    Color color,
    double margin,
    double chartW,
    double chartH,
  ) {
    if (values.length < 2) return;

    final fillPaint = Paint()
      ..color = color.withAlpha(30)
      ..style = PaintingStyle.fill;

    final linePaint = Paint()
      ..color = color
      ..strokeWidth = 1.5
      ..style = PaintingStyle.stroke
      ..strokeJoin = StrokeJoin.round;

    final path = Path();
    final fillPath = Path();

    for (var i = 0; i < values.length; i++) {
      final x = margin + (chartW / (values.length - 1)) * i;
      final y = chartH - (values[i] / maxRate) * chartH;
      if (i == 0) {
        path.moveTo(x, y);
        fillPath.moveTo(x, chartH);
        fillPath.lineTo(x, y);
      } else {
        path.lineTo(x, y);
        fillPath.lineTo(x, y);
      }
    }

    fillPath.lineTo(margin + chartW, chartH);
    fillPath.close();

    canvas.drawPath(fillPath, fillPaint);
    canvas.drawPath(path, linePaint);
  }

  void _drawLegend(Canvas canvas, Size size) {
    final y = size.height - 10;
    canvas.drawCircle(
      Offset(size.width / 2 - 60, y),
      4,
      Paint()..color = uploadColor,
    );
    _drawText(
      canvas,
      'Upload',
      Offset(size.width / 2 - 52, y - 6),
      TextStyle(color: labelColor, fontSize: 10),
      50,
    );
    canvas.drawCircle(
      Offset(size.width / 2 + 20, y),
      4,
      Paint()..color = downloadColor,
    );
    _drawText(
      canvas,
      'Download',
      Offset(size.width / 2 + 28, y - 6),
      TextStyle(color: labelColor, fontSize: 10),
      60,
    );
  }

  void _drawText(
    Canvas canvas,
    String text,
    Offset offset,
    TextStyle style,
    double maxWidth,
  ) {
    final tp = TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: TextDirection.ltr,
      maxLines: 1,
    )..layout(maxWidth: maxWidth);
    tp.paint(canvas, offset);
  }

  static String _formatRate(int bytesPerSec) {
    if (bytesPerSec >= 1048576) {
      return '${(bytesPerSec / 1048576).toStringAsFixed(1)}M';
    }
    if (bytesPerSec >= 1024) {
      return '${(bytesPerSec / 1024).toStringAsFixed(0)}K';
    }
    return '${bytesPerSec}B';
  }

  @override
  bool shouldRepaint(covariant _ChartPainter oldDelegate) => true;
}
