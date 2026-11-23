package io.github.offlineglass.hook.adapters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.rendering.NativeBackdropBlur
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.function.ObjIntConsumer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Shared zero-copy GPU window-strip capture for adapters whose scenes include
 * SurfaceView (map/video) or WebView layers via ScreenCapture.captureLayers.
 */
internal class WindowGpuBackdropCapture(
    private val host: GlassHostLayout,
    private val tag: String,
    private val downscale: Float = 2f,
    private val activeIntervalMs: Long = 16L,
    private val idleIntervalMs: Long = 160L,
) {
    private data class Api(
        val capture: Class<*>,
        val builder: Class<*>,
        val base: Class<*>,
        val listener: Class<*>,
    )

    private data class Request(
        val root: SurfaceControl,
        val overlay: SurfaceControl,
        val crop: Rect,
        val width: Int,
        val height: Int,
    )

    internal data class CapturedGeometry(
        val sourceRect: Rect,
        val hostLeft: Int,
        val hostTop: Int,
        val hostWidth: Int,
        val hostHeight: Int,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var frameObserver: ViewTreeObserver? = null
    private var frameRoot: View? = null
    private var frameCommitRunnable: Runnable? = null
    private var frameFallbackRunnable: Runnable? = null

    private val hostLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private var windowRoot: View? = null
    private var windowControl: SurfaceControl? = null

    private var captureApi: Api? = null
    private var argsKey: Request? = null
    private var captureArgs: Any? = null

    private var pending = false
    @Volatile private var serial = 0L
    private var lastSubmitAt = 0L
    private var retryAt = 0L
    private var motionUntil = SystemClock.uptimeMillis() + 2_000L
    private var activeEpoch = 0L
    @Volatile private var listener: Any? = null

    var isPermanentlyUnsupported: Boolean = false
        private set

    private var pendingGeometry: CapturedGeometry? = null
    private var publishedGeometry: CapturedGeometry? = null

    private val drawSrcRect = Rect()
    private val drawDstRect = RectF()

    private val timeoutRunnable = Runnable {
        if (pending) fail("callback timeout")
    }

    private val wakeupRunnable = Runnable {
        if (!isPermanentlyUnsupported && host.isAttachedToWindow) {
            request(activeEpoch)
        }
    }

    private fun worker(): Handler {
        workerHandler?.let { return it }
        val thread = HandlerThread("LiquidTab-$tag-Cap").also { it.start() }
        workerThread = thread
        return Handler(thread.looper).also { workerHandler = it }
    }

    fun notifyMotion(durationMs: Long = 1_200L, epoch: Long? = null) {
        val now = SystemClock.uptimeMillis()
        motionUntil = maxOf(motionUntil, now + durationMs)
        if (epoch != null) {
            activeEpoch = epoch
            request(epoch)
        }
    }

    fun request(epoch: Long) {
        activeEpoch = epoch
        if (isPermanentlyUnsupported || pending || !host.isAttachedToWindow) return
        if (!host.isShown || !host.hasWindowFocus()) return
        if (host.alpha <= 0.001f || !host.opticalSurfaceRunningForAdapter) {
            scheduleWakeup(60L)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now < retryAt) {
            scheduleWakeup((retryAt - now).coerceAtLeast(16L))
            return
        }

        val moving = now < motionUntil || host.adapterNavigationAnimationRunning || host.rootView.isDirty
        val interval = if (moving) activeIntervalMs else idleIntervalMs
        val elapsed = now - lastSubmitAt
        if (lastSubmitAt > 0L && elapsed < interval) {
            scheduleWakeup((interval - elapsed).coerceAtLeast(1L))
            return
        }

        val root = host.rootView
        scheduleAfterFrameCommit(root) {
            submit(root, activeEpoch)
        }
    }

    private fun scheduleWakeup(delayMs: Long) {
        mainHandler.removeCallbacks(wakeupRunnable)
        mainHandler.postDelayed(wakeupRunnable, delayMs)
    }

    private fun scheduleAfterFrameCommit(view: View, action: () -> Unit) {
        if (!view.isHardwareAccelerated || !view.viewTreeObserver.isAlive) {
            action()
            return
        }
        if (frameCommitRunnable != null) return
        frameRoot = view
        val tree = view.viewTreeObserver
        frameObserver = tree
        lateinit var submitAction: Runnable
        submitAction = Runnable {
            if (frameCommitRunnable !== submitAction) return@Runnable
            cancelFrameCommit()
            if (view.isAttachedToWindow) action()
        }
        frameCommitRunnable = submitAction
        val fallback = Runnable { submitAction.run() }
        frameFallbackRunnable = fallback
        tree.registerFrameCommitCallback(submitAction)
        val refresh = view.display?.refreshRate?.takeIf { it.isFinite() && it > 0f } ?: 60f
        view.postDelayed(fallback, ceil(2000.0 / refresh).toLong().coerceAtLeast(16L))
    }

    private fun cancelFrameCommit() {
        frameCommitRunnable?.let { cb ->
            if (frameObserver?.isAlive == true) frameObserver?.unregisterFrameCommitCallback(cb)
        }
        frameFallbackRunnable?.let { frameRoot?.removeCallbacks(it) }
        frameCommitRunnable = null
        frameFallbackRunnable = null
        frameObserver = null
        frameRoot = null
    }

    private fun submit(root: View, epoch: Long) {
        if (isPermanentlyUnsupported || pending || !host.isAttachedToWindow ||
            !host.isShown || !host.hasWindowFocus()
        ) return
        if (host.alpha <= 0.001f || !host.opticalSurfaceRunningForAdapter) {
            scheduleWakeup(60L)
            return
        }
        val overlay = host.opticalSurfaceViewForAdapter?.surfaceControl
        if (overlay == null || !overlay.isValid) {
            scheduleWakeup(60L)
            return
        }

        host.getLocationInWindow(hostLocation)
        root.getLocationInWindow(rootLocation)
        val left = hostLocation[0] - rootLocation[0]
        val top = hostLocation[1] - rootLocation[1]
        val hostW = host.width
        val hostH = host.height
        if (hostW <= 0 || hostH <= 0 || root.width <= 0 || root.height <= 0) {
            scheduleWakeup(60L)
            return
        }

        val density = host.resources.displayMetrics.density
        val padding = NativeBackdropBlur.padding(
            host.config.blurRadius * density,
            24f * density * host.config.refractionScale.coerceAtLeast(1f),
        ).roundToInt()

        val sourceRect = Rect(
            (left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0),
            (left + hostW + padding).coerceAtMost(root.width),
            (top + hostH + padding).coerceAtMost(root.height),
        )
        if (sourceRect.isEmpty) {
            scheduleWakeup(60L)
            return
        }

        val targetWidth = (sourceRect.width() / downscale).roundToInt().coerceAtLeast(1)
        val targetHeight = (sourceRect.height() / downscale).roundToInt().coerceAtLeast(1)
        val barSource = Rect(0, 0, targetWidth, targetHeight)

        val window = windowSurface(root)
        if (window == null || !window.isValid || window === overlay) {
            if (!isPermanentlyUnsupported) scheduleWakeup(60L)
            return
        }

        val req = Request(window, overlay, sourceRect, targetWidth, targetHeight)
        val geometry = CapturedGeometry(Rect(sourceRect), left, top, hostW, hostH)

        pending = true
        lastSubmitAt = SystemClock.uptimeMillis()
        val ticket = ++serial
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, 350L)

        val captureWorker = worker()
        captureWorker.post capture@{
            if (ticket != serial) return@capture
            Trace.beginSection("LiquidTab.$tag.captureGpuStrip")
            try {
                val api = captureApi ?: resolve().also { captureApi = it }
                val args = argsFor(api, req)
                val consumer = ObjIntConsumer<Any?> { result, status ->
                    val bitmap = if (status == 0 && result != null) {
                        runCatching { wrap(result) }.getOrNull()
                    } else null
                    mainHandler.post publish@{
                        if (ticket != serial) {
                            bitmap?.recycle()
                            return@publish
                        }
                        if (bitmap == null) {
                            fail("capture status $status")
                        } else {
                            finish()
                            pendingGeometry = geometry
                            if (!host.publishHardwareOpticalFrameForAdapter(bitmap, barSource, epoch)) {
                                pendingGeometry = null
                                bitmap.recycle()
                                if (host.isAttachedToWindow && host.isShown && host.hasWindowFocus()) {
                                    scheduleWakeup(60L)
                                }
                            } else {
                                val now = SystemClock.uptimeMillis()
                                val moving = now < motionUntil || host.adapterNavigationAnimationRunning || host.rootView.isDirty
                                scheduleWakeup(if (moving) activeIntervalMs else idleIntervalMs)
                            }
                        }
                    }
                }
                val callback = HiddenApiBypass.newInstance(api.listener, consumer)
                listener = callback
                val status = HiddenApiBypass.invoke(api.capture, null, "captureLayers", args, callback) as Int
                if (status != 0) {
                    mainHandler.post {
                        if (ticket == serial) fail("submission status $status", permanent = status == -1)
                    }
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    if (ticket == serial) fail(error.toString(), permanent = true)
                }
            } finally {
                Trace.endSection()
            }
        }
    }

    fun commitPublishedGeometry() {
        pendingGeometry?.let {
            publishedGeometry = it
            pendingGeometry = null
        }
    }

    fun drawBackdrop(canvas: Canvas, bitmap: Bitmap, destination: RectF, paint: Paint): Boolean {
        val geo = publishedGeometry ?: return false
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 ||
            geo.sourceRect.width() <= 0 || geo.sourceRect.height() <= 0
        ) return false

        val destPadX = (destination.width() - geo.hostWidth) * 0.5f
        val destPadY = (destination.height() - geo.hostHeight) * 0.5f
        val dstLeft = destination.left + destPadX + (geo.sourceRect.left - geo.hostLeft)
        val dstTop = destination.top + destPadY + (geo.sourceRect.top - geo.hostTop)
        val dstRight = dstLeft + geo.sourceRect.width()
        val dstBottom = dstTop + geo.sourceRect.height()

        val bw = bitmap.width
        val bh = bitmap.height

        // Edge-clamp overhanging margins so screen-boundary crops never bleed dark borders into blur.
        if (dstTop > destination.top) {
            drawSrcRect.set(0, 0, bw, 1)
            drawDstRect.set(destination.left, destination.top, destination.right, dstTop)
            canvas.drawBitmap(bitmap, drawSrcRect, drawDstRect, paint)
        }
        if (dstBottom < destination.bottom) {
            drawSrcRect.set(0, bh - 1, bw, bh)
            drawDstRect.set(destination.left, dstBottom, destination.right, destination.bottom)
            canvas.drawBitmap(bitmap, drawSrcRect, drawDstRect, paint)
        }
        if (dstLeft > destination.left) {
            drawSrcRect.set(0, 0, 1, bh)
            drawDstRect.set(destination.left, dstTop, dstLeft, dstBottom)
            canvas.drawBitmap(bitmap, drawSrcRect, drawDstRect, paint)
        }
        if (dstRight < destination.right) {
            drawSrcRect.set(bw - 1, 0, bw, bh)
            drawDstRect.set(dstRight, dstTop, destination.right, dstBottom)
            canvas.drawBitmap(bitmap, drawSrcRect, drawDstRect, paint)
        }

        drawSrcRect.set(0, 0, bw, bh)
        drawDstRect.set(dstLeft, dstTop, dstRight, dstBottom)
        canvas.drawBitmap(bitmap, drawSrcRect, drawDstRect, paint)
        return true
    }

    private fun windowSurface(root: View): SurfaceControl? {
        if (windowRoot === root) windowControl?.takeIf { it.isValid }?.let { return it }
        return runCatching {
            val viewRoot = HiddenApiBypass.invoke(View::class.java, root, "getViewRootImpl") ?: return null
            (HiddenApiBypass.invoke(viewRoot.javaClass, viewRoot, "getSurfaceControl") as? SurfaceControl)
                ?.takeIf { it.isValid }?.also {
                    windowRoot = root
                    windowControl = it
                }
        }.getOrElse {
            fail(it.toString(), permanent = true)
            null
        }
    }

    private fun resolve(): Api {
        val capture = runCatching { Class.forName("android.window.ScreenCaptureInternal") }
            .getOrElse { Class.forName("android.window.ScreenCapture") }
        return Api(
            capture,
            Class.forName(capture.name + "\$LayerCaptureArgs\$Builder"),
            Class.forName(capture.name + "\$CaptureArgs\$Builder"),
            Class.forName(capture.name + "\$ScreenCaptureListener"),
        )
    }

    private fun argsFor(api: Api, request: Request): Any {
        if (argsKey == request) captureArgs?.let { return it }
        val builder = HiddenApiBypass.newInstance(api.builder, request.root)
        HiddenApiBypass.invoke(api.builder, builder, "setChildrenOnly", false)
        HiddenApiBypass.invoke(api.base, builder, "setSourceCrop", request.crop)
        HiddenApiBypass.invoke(
            api.base,
            builder,
            "setFrameScale",
            request.width.toFloat() / request.crop.width(),
            request.height.toFloat() / request.crop.height(),
        )
        HiddenApiBypass.invoke(api.base, builder, "setUid", Process.myUid().toLong())
        HiddenApiBypass.invoke(api.base, builder, "setExcludeLayers", arrayOf(request.overlay))
        return HiddenApiBypass.invoke(api.builder, builder, "build")!!.also {
            argsKey = request
            captureArgs = it
        }
    }

    private fun wrap(result: Any): Bitmap? {
        val buffer = result.javaClass.getMethod("getHardwareBuffer").invoke(result) as HardwareBuffer
        return buffer.use {
            val space = result.javaClass.getMethod("getColorSpace").invoke(result) as? ColorSpace
            Bitmap.wrapHardwareBuffer(buffer, space)
        }
    }

    private fun finish() {
        mainHandler.removeCallbacks(timeoutRunnable)
        pending = false
        listener = null
    }

    private fun fail(reason: String, permanent: Boolean = false) {
        ++serial
        finish()
        if (permanent) isPermanentlyUnsupported = true
        retryAt = SystemClock.uptimeMillis() + 1_000L
        XposedBridge.log("LiquidTab $tag GPU backdrop unavailable: $reason")
        if (!permanent && host.isAttachedToWindow && host.isShown && host.hasWindowFocus()) {
            scheduleWakeup(1_000L)
        }
    }

    fun cancel() {
        mainHandler.removeCallbacks(wakeupRunnable)
        cancelFrameCommit()
        ++serial
        finish()
        pendingGeometry = null
        windowRoot = null
        windowControl = null
    }

    fun dispose() {
        cancel()
        publishedGeometry = null
        argsKey = null
        captureArgs = null
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }
}
