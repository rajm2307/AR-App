import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

/// State streamed from the native AR view.
class MeasureState {
  final int points;
  final bool tracking;
  final bool hasHit;
  final String? summary; // main result, already formatted in the chosen unit
  final String hint; // what to do next
  final bool canAdd;
  final bool canFinish;
  final String? error;

  const MeasureState({
    this.points = 0,
    this.tracking = false,
    this.hasHit = false,
    this.summary,
    this.hint = '',
    this.canAdd = false,
    this.canFinish = false,
    this.error,
  });

  factory MeasureState.fromMap(Map m) => MeasureState(
        points: (m['points'] as int?) ?? 0,
        tracking: (m['tracking'] as bool?) ?? false,
        hasHit: (m['hasHit'] as bool?) ?? false,
        summary: m['summary'] as String?,
        hint: (m['hint'] as String?) ?? '',
        canAdd: (m['canAdd'] as bool?) ?? false,
        canFinish: (m['canFinish'] as bool?) ?? false,
        error: m['error'] as String?,
      );
}

/// Commands to the native AR view.
class ArMeasure {
  static const _control = MethodChannel('ar_measure/control');
  static const _events = EventChannel('ar_measure/events');

  static Stream<MeasureState> get states =>
      _events.receiveBroadcastStream().map((e) => MeasureState.fromMap(e as Map));

  static Future<void> addPoint() => _control.invokeMethod('addPoint');
  static Future<void> undo() => _control.invokeMethod('undo');
  static Future<void> reset() => _control.invokeMethod('reset');
  static Future<void> finish() => _control.invokeMethod('finish');
  static Future<void> setUnit(String unit) => _control.invokeMethod('setUnit', unit);

  /// mode: 'distance' | 'area' | 'volume'
  static Future<void> setMode(String mode) => _control.invokeMethod('setMode', mode);
}

/// The native ARCore camera view (Android, hybrid composition).
class ArMeasureView extends StatelessWidget {
  const ArMeasureView({super.key});

  static const _viewType = 'ar_measure_view';

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return const Center(
        child: Text('Phase 1 is Android only.\niOS comes in a later phase.',
            textAlign: TextAlign.center, style: TextStyle(color: Colors.white)),
      );
    }
    return PlatformViewLink(
      viewType: _viewType,
      surfaceFactory: (context, controller) => AndroidViewSurface(
        controller: controller as AndroidViewController,
        gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
        hitTestBehavior: PlatformViewHitTestBehavior.transparent,
      ),
      onCreatePlatformView: (params) {
        return PlatformViewsService.initExpensiveAndroidView(
          id: params.id,
          viewType: _viewType,
          layoutDirection: TextDirection.ltr,
          creationParams: null,
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        )
          ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
          ..create();
      },
    );
  }
}