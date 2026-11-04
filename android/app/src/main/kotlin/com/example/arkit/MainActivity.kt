package com.example.arkit

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        flutterEngine.platformViewsController.registry
            .registerViewFactory("ar_measure_view", ArMeasureFactory(this))

        MethodChannel(messenger, "ar_measure/control").setMethodCallHandler { call, result ->
            val view = ArMeasureBridge.active
            when (call.method) {
                "addPoint" -> view?.command("add")
                "undo" -> view?.command("undo")
                "reset" -> view?.command("reset")
                "finish" -> view?.command("finish")
                "setMode" -> {
                    val m = call.arguments as? String ?: "distance"
                    ArMeasureBridge.mode = m
                    view?.command("mode:$m")
                }
                "setUnit" -> {
                    val u = call.arguments as? String ?: "cm"
                    ArMeasureBridge.unit = u
                    view?.setUnit(u)
                }
                else -> {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
            }
            result.success(null)
        }

        EventChannel(messenger, "ar_measure/events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                ArMeasureBridge.sink = events
            }

            override fun onCancel(arguments: Any?) {
                ArMeasureBridge.sink = null
            }
        })
    }
}