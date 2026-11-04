import 'dart:async';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import 'ar_measure_view.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'AR Measure',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.blue, brightness: Brightness.dark),
        home: const MeasurePage(),
      );
}

class MeasurePage extends StatefulWidget {
  const MeasurePage({super.key});

  @override
  State<MeasurePage> createState() => _MeasurePageState();
}

class _MeasurePageState extends State<MeasurePage> {
  StreamSubscription<MeasureState>? _sub;
  MeasureState _state = const MeasureState();
  bool _granted = false;
  bool _inch = false;
  String _mode = 'distance';

  @override
  void initState() {
    super.initState();
    // Subscribe before the AR view exists so no early event is missed.
    _sub = ArMeasure.states.listen((s) {
      if (mounted) setState(() => _state = s);
    });
    _askPermission();
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _askPermission() async {
    final status = await Permission.camera.request();
    if (mounted) setState(() => _granted = status.isGranted);
  }

  @override
  Widget build(BuildContext context) {
    if (!_granted) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Camera permission is required for AR.'),
              const SizedBox(height: 16),
              FilledButton(onPressed: _askPermission, child: const Text('Grant permission')),
              TextButton(onPressed: openAppSettings, child: const Text('Open settings')),
            ],
          ),
        ),
      );
    }

    final s = _state;
    final pill = s.error ?? s.summary ?? s.hint;
    final subHint = (s.error == null && s.summary != null) ? s.hint : '';

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          const Positioned.fill(child: ArMeasureView()),
          // top: result / hint pill, unit toggle
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
                          decoration: BoxDecoration(
                            color: Colors.black.withOpacity(0.7),
                            borderRadius: BorderRadius.circular(22),
                          ),
                          child: Text(pill,
                              textAlign: TextAlign.center,
                              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
                        ),
                        if (subHint.isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.only(top: 6),
                            child: Text(subHint,
                                textAlign: TextAlign.center,
                                style: const TextStyle(fontSize: 13, color: Colors.white70)),
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  SegmentedButton<bool>(
                    showSelectedIcon: false,
                    segments: const [
                      ButtonSegment(value: false, label: Text('cm')),
                      ButtonSegment(value: true, label: Text('in')),
                    ],
                    selected: {_inch},
                    onSelectionChanged: (v) {
                      setState(() => _inch = v.first);
                      ArMeasure.setUnit(_inch ? 'in' : 'cm');
                    },
                  ),
                ],
              ),
            ),
          ),
          // bottom: mode selector + controls
          Align(
            alignment: Alignment.bottomCenter,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: SegmentedButton<String>(
                    showSelectedIcon: false,
                    segments: const [
                      ButtonSegment(value: 'distance', label: Text('Distance')),
                      ButtonSegment(value: 'area', label: Text('Area')),
                      ButtonSegment(value: 'volume', label: Text('Volume')),
                    ],
                    selected: {_mode},
                    onSelectionChanged: (v) {
                      setState(() => _mode = v.first);
                      ArMeasure.setMode(_mode);
                    },
                  ),
                ),
                Container(
                  height: 110,
                  color: Colors.black.withOpacity(0.65),
                  child: SafeArea(
                    top: false,
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                      children: [
                        IconButton(
                          iconSize: 34,
                          color: Colors.lightBlueAccent,
                          onPressed: ArMeasure.undo,
                          icon: const Icon(Icons.undo),
                          tooltip: 'Undo',
                        ),
                        if (s.canFinish)
                          IconButton(
                            iconSize: 38,
                            color: Colors.greenAccent,
                            onPressed: ArMeasure.finish,
                            icon: const Icon(Icons.check_circle_outline),
                            tooltip: 'Done',
                          ),
                        GestureDetector(
                          onTap: s.canAdd ? ArMeasure.addPoint : null,
                          child: Opacity(
                            opacity: s.canAdd ? 1 : 0.4,
                            child: Container(
                              width: 72,
                              height: 72,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                border: Border.all(color: Colors.lightBlueAccent, width: 4),
                              ),
                              child: const Icon(Icons.add, size: 40, color: Colors.lightBlueAccent),
                            ),
                          ),
                        ),
                        IconButton(
                          iconSize: 34,
                          color: Colors.lightBlueAccent,
                          onPressed: ArMeasure.reset,
                          icon: const Icon(Icons.refresh),
                          tooltip: 'Reset',
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}