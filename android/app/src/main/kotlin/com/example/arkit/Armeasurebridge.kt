package com.example.arkit

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/** Glue between the Flutter channels and the single active AR view. */
object ArMeasureBridge {
    @Volatile var active: ArMeasureView? = null
    @Volatile var sink: EventChannel.EventSink? = null
    @Volatile var unit: String = "cm"
    @Volatile var mode: String = "distance"

    private val main = Handler(Looper.getMainLooper())

    fun emit(data: Map<String, Any?>) {
        main.post { sink?.success(data) }
    }
}

class ArMeasureFactory(private val activity: Activity) :
    PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val view = ArMeasureView(activity)
        ArMeasureBridge.active = view
        return object : PlatformView {
            override fun getView(): View = view
            override fun dispose() {
                view.release()
                if (ArMeasureBridge.active === view) ArMeasureBridge.active = null
            }
        }
    }
}