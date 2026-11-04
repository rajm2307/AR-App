package com.example.arkit

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ARCore session + GL camera background + measuring logic.
 * Modes: "distance" (2 points), "area" (polygon), "volume" (box).
 * Commands from Flutter are queued and executed on the GL thread.
 */
class ArMeasureView(private val activity: Activity) :
    FrameLayout(activity), GLSurfaceView.Renderer, DefaultLifecycleObserver {

    private val glView = GLSurfaceView(activity)
    private val overlay = MeasureOverlayView(activity)
    private val background = BackgroundRenderer()

    private val lock = Any()
    private val commands = ConcurrentLinkedQueue<String>()

    // ---- GL-thread state ----
    private val anchors = mutableListOf<Anchor>()
    private var mode = ArMeasureBridge.mode
    private var areaClosed = false
    private var heightFixed: Float? = null

    @Volatile private var fmt = Fmt(ArMeasureBridge.unit == "in")

    private var session: Session? = null
    private var installRequested = false
    private var textureSet = false
    private var viewportChanged = false
    private var vw = 1
    private var vh = 1

    private val viewM = FloatArray(16)
    private val projM = FloatArray(16)
    private val vpM = FloatArray(16)
    private var lastKey = ""
    private var lastEmit = 0L

    init {
        glView.preserveEGLContextOnPause = true
        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.setRenderer(this)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView.setWillNotDraw(false)
        addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val owner = activity as? LifecycleOwner
        if (owner != null) owner.lifecycle.addObserver(this) else resumeSession()
    }

    // ---- called from Flutter ---------------------------------------------------
    fun command(c: String) { commands.add(c) }
    fun setUnit(u: String) { fmt = Fmt(u == "in") }

    // ---- lifecycle -------------------------------------------------------------
    override fun onResume(owner: LifecycleOwner) = resumeSession()
    override fun onPause(owner: LifecycleOwner) = pauseSession()

    fun release() {
        (activity as? LifecycleOwner)?.lifecycle?.removeObserver(this)
        glView.onPause()
        synchronized(lock) {
            anchors.clear()
            session?.close()
            session = null
        }
    }

    private fun resumeSession() {
        synchronized(lock) {
            if (session == null) {
                try {
                    val status = ArCoreApk.getInstance().requestInstall(activity, !installRequested)
                    if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                        installRequested = true
                        return
                    }
                    val s = Session(activity)
                    val config = Config(s)
                    config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL)
                    config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
                    config.setFocusMode(Config.FocusMode.AUTO)
                    if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        config.setDepthMode(Config.DepthMode.AUTOMATIC)
                    }
                    s.configure(config)
                    session = s
                    textureSet = false
                } catch (e: Exception) {
                    ArMeasureBridge.emit(mapOf("error" to "ARCore unavailable: ${e.javaClass.simpleName}"))
                    return
                }
            }
            try {
                session?.resume()
            } catch (e: CameraNotAvailableException) {
                ArMeasureBridge.emit(mapOf("error" to "Camera not available"))
                session = null
                return
            }
        }
        glView.onResume()
    }

    private fun pauseSession() {
        glView.onPause()
        synchronized(lock) { session?.pause() }
    }

    // ---- GL renderer -----------------------------------------------------------
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        textureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        vw = width
        vh = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        synchronized(lock) {
            val s = session ?: return
            if (!textureSet) {
                s.setCameraTextureName(background.textureId)
                textureSet = true
            }
            if (viewportChanged) {
                @Suppress("DEPRECATION")
                val rotation = activity.windowManager.defaultDisplay.rotation
                s.setDisplayGeometry(rotation, vw, vh)
                viewportChanged = false
            }
            val frame = try { s.update() } catch (e: CameraNotAvailableException) { return }
            background.draw(frame)
            step(frame)
        }
    }

    private fun step(frame: Frame) {
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        val pose = camera.displayOrientedPose
        val camPos = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
        val z = pose.zAxis
        val camFwd = floatArrayOf(-z[0], -z[1], -z[2]) // camera looks along -Z

        val hit = if (tracking) pickHit(frame) else null
        runCommands(hit, camPos, camFwd)

        val pts = anchors
            .filter { it.trackingState != TrackingState.STOPPED }
            .map { floatArrayOf(it.pose.tx(), it.pose.ty(), it.pose.tz()) }
        val reticle = hit?.hitPose?.let { floatArrayOf(it.tx(), it.ty(), it.tz()) }
        val f = fmt

        val b: Built = if (!tracking) {
            Built(hint = "Move the phone slowly…")
        } else when (mode) {
            "area" -> Builder.area(pts, reticle, areaClosed, f)
            "volume" -> Builder.volume(pts, reticle, camPos, camFwd, heightFixed, f)
            else -> Builder.twoPoint(pts, reticle, f)
        }

        if (tracking) {
            camera.getViewMatrix(viewM, 0)
            camera.getProjectionMatrix(projM, 0, 0.05f, 100f)
            Matrix.multiplyMM(vpM, 0, projM, 0, viewM, 0)
        }
        overlay.update(
            OverlayState(vpM.copyOf(), tracking, tracking && hit != null, b.dots, b.segs, b.polys, b.labels)
        )

        // Tell Flutter only when something changed (max ~12 times/second).
        val key = "$mode|${pts.size}|${b.summary}|${b.hint}|${hit != null}|$tracking|${b.canAdd}|${b.canFinish}"
        val now = System.currentTimeMillis()
        if (key != lastKey && now - lastEmit > 80) {
            lastKey = key
            lastEmit = now
            ArMeasureBridge.emit(
                mapOf(
                    "points" to pts.size,
                    "tracking" to tracking,
                    "hasHit" to (hit != null),
                    "summary" to b.summary,
                    "hint" to b.hint,
                    "canAdd" to (tracking && b.canAdd),
                    "canFinish" to b.canFinish,
                )
            )
        }
    }

    /** First hit under the crosshair that is a real surface (plane, depth or oriented point). */
    private fun pickHit(frame: Frame): HitResult? {
        for (h in frame.hitTest(vw / 2f, vh / 2f)) {
            val t = h.trackable
            val ok = (t is Plane && t.isPoseInPolygon(h.hitPose)) ||
                (t is Point && t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) ||
                t is DepthPoint
            if (ok) return h
        }
        return null
    }

    // ---- commands ---------------------------------------------------------------
    private fun runCommands(hit: HitResult?, camPos: FloatArray, camFwd: FloatArray) {
        while (true) {
            val c = commands.poll() ?: break
            when {
                c.startsWith("mode:") -> { mode = c.removePrefix("mode:"); clearAll() }
                c == "reset" -> clearAll()
                c == "finish" -> if (mode == "area" && anchors.size >= 3) areaClosed = true
                c == "undo" -> undo()
                c == "add" -> add(hit, camPos, camFwd)
            }
        }
    }

    private fun add(hit: HitResult?, camPos: FloatArray, camFwd: FloatArray) {
        when (mode) {
            "area" -> {
                if (areaClosed) clearAll()
                addAnchor(hit)
            }
            "volume" -> {
                if (heightFixed != null) {            // box finished -> start a new one
                    clearAll()
                    addAnchor(hit)
                } else if (anchors.size < 3) {
                    addAnchor(hit)
                } else {                              // 4th tap fixes the height
                    val p = anchors[0].pose
                    val h = heightFromRay(floatArrayOf(p.tx(), p.ty(), p.tz()), camPos, camFwd)
                    if (h != null && h > 0.005f) heightFixed = h
                }
            }
            else -> {
                if (anchors.size >= 2) clearAll()     // 3rd tap starts a new measurement
                addAnchor(hit)
            }
        }
    }

    private fun undo() {
        when {
            mode == "volume" && heightFixed != null -> heightFixed = null
            mode == "area" && areaClosed -> areaClosed = false
            anchors.isNotEmpty() -> anchors.removeAt(anchors.lastIndex).detach()
        }
    }

    private fun addAnchor(hit: HitResult?) {
        if (hit == null) return
        try { anchors.add(hit.createAnchor()) } catch (_: Exception) {}
    }

    private fun clearAll() {
        anchors.forEach { it.detach() }
        anchors.clear()
        areaClosed = false
        heightFixed = null
    }
}