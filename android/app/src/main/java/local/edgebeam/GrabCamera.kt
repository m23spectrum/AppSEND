package local.edgebeam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.Executors

internal data class HandFocus(val x: Float, val y: Float, val aspect: Float)

/** All recognition and gate state live on one bounded worker; camera images never leave RAM. */
internal class GrabCamera(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val state: (String) -> Unit,
    private val send: () -> Unit,
    private val focus: (HandFocus?) -> Unit = {},
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = ContextCompat.getMainExecutor(context)
    private val gate = GrabGate()
    private var recognizer: GestureRecognizer? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    @Volatile private var closed = false
    @Volatile private var resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private var lastFrame = 0L
    private var lastFocus = 0L
    private var measuredFrames = 0
    private var processingNanos = 0L
    private var reported = ""
    private val observer = LifecycleEventObserver { _, event ->
        resumed =
            event == Lifecycle.Event.ON_RESUME ||
                (event != Lifecycle.Event.ON_PAUSE &&
                    event != Lifecycle.Event.ON_STOP &&
                    owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (!closed)
            executor.execute {
                gate.reset(requireRelease = false)
                report("grabFist")
            }
    }

    private fun report(key: String) {
        if (key == reported || closed) return
        reported = key
        Log.i("AppSENDGrab", key)
        main.execute { if (!closed) state(key) }
    }

    fun resetGrip() {
        if (!closed)
            executor.execute {
                gate.reset()
                report("grabFist")
            }
    }

    fun start() {
        owner.lifecycle.addObserver(observer)
        executor.execute {
            try {
                recognizer =
                    GestureRecognizer.createFromOptions(
                        context,
                        GestureRecognizer.GestureRecognizerOptions.builder()
                            .setBaseOptions(
                                BaseOptions.builder()
                                    .setModelAssetPath("gesture_recognizer.task")
                                    .build()
                            )
                            .setRunningMode(RunningMode.VIDEO)
                            .setNumHands(1)
                            .setMinHandDetectionConfidence(.55f)
                            .setMinHandPresenceConfidence(.55f)
                            .setMinTrackingConfidence(.6f)
                            .build(),
                    )
                if (closed) return@execute
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    {
                        if (closed) return@addListener
                        try {
                            val cameras = future.get()
                            provider = cameras
                            check(cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                            val displayRotation =
                                previewView.display?.rotation ?: Surface.ROTATION_0
                            val p = Preview.Builder().setTargetRotation(displayRotation).build()
                            p.setSurfaceProvider(previewView.surfaceProvider)
                            @Suppress("DEPRECATION")
                            val a =
                                ImageAnalysis.Builder()
                                    .setTargetResolution(Size(640, 480))
                                    .setTargetRotation(displayRotation)
                                    .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                    )
                                    .setOutputImageFormat(
                                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                                    )
                                    .build()
                            preview = p
                            analysis = a
                            a.setAnalyzer(executor) { frame ->
                                try {
                                    if (!resumed || closed || gate.phase == GrabGate.Phase.SENT)
                                        return@setAnalyzer
                                    val time = SystemClock.uptimeMillis()
                                    if (time <= lastFrame) return@setAnalyzer
                                    lastFrame = time
                                    val processingStart = SystemClock.elapsedRealtimeNanos()
                                    val bitmap = frame.toBitmap()
                                    val matrix =
                                        Matrix().apply {
                                            postRotate(frame.imageInfo.rotationDegrees.toFloat())
                                        }
                                    val upright =
                                        Bitmap.createBitmap(
                                            bitmap,
                                            0,
                                            0,
                                            bitmap.width,
                                            bitmap.height,
                                            matrix,
                                            false,
                                        )
                                    val image = BitmapImageBuilder(upright).build()
                                    try {
                                        val result = recognizer!!.recognizeForVideo(image, time)
                                        val points = result.landmarks().firstOrNull()
                                        val gesture =
                                            result.gestures().firstOrNull()?.maxByOrNull {
                                                it.score()
                                            }
                                        val hand =
                                            points?.let {
                                                GrabGate.Hand(
                                                    gesture?.categoryName() ?: "None",
                                                    gesture?.score() ?: 0f,
                                                    (it[0].x() + it[5].x() + it[17].x()) / 3,
                                                    (it[0].y() + it[5].y() + it[17].y()) / 3,
                                                )
                                            }
                                        if (!resumed || closed) {
                                            gate.reset()
                                            return@setAnalyzer
                                        }
                                        val fired = gate.sample(time, hand)
                                        processingNanos +=
                                            SystemClock.elapsedRealtimeNanos() - processingStart
                                        if (++measuredFrames == 90) {
                                            Log.i(
                                                "AppSENDGrab",
                                                "analysis-average-ms=${processingNanos / measuredFrames / 1_000_000} frames=$measuredFrames",
                                            )
                                            measuredFrames = 0
                                            processingNanos = 0
                                        }
                                        if (time - lastFocus >= 65) {
                                            lastFocus = time
                                            val position =
                                                hand?.let {
                                                    HandFocus(
                                                        1 - it.x,
                                                        it.y,
                                                        upright.width.toFloat() / upright.height,
                                                    )
                                                }
                                            main.execute { if (!closed && resumed) focus(position) }
                                        }
                                        report(
                                            when (gate.phase) {
                                                GrabGate.Phase.FIST -> "grabFist"
                                                GrabGate.Phase.HELD,
                                                GrabGate.Phase.RELEASE,
                                                GrabGate.Phase.EXIT -> "grabHeld"
                                                GrabGate.Phase.SENT -> "grabSending"
                                            }
                                        )
                                        if (fired) main.execute { if (!closed && resumed) send() }
                                    } finally {
                                        image.close()
                                        if (upright !== bitmap) upright.recycle()
                                        bitmap.recycle()
                                    }
                                } catch (e: Exception) {
                                    gate.reset()
                                    report("grabCameraError")
                                    Log.e("AppSENDGrab", "Recognition failed", e)
                                } finally {
                                    frame.close()
                                }
                            }
                            cameras.bindToLifecycle(
                                owner,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                p,
                                a,
                            )
                            Log.i("AppSENDGrab", "front-camera-bound")
                        } catch (e: Exception) {
                            report("grabCameraError")
                            Log.e("AppSENDGrab", "Camera failed", e)
                        }
                    },
                    main,
                )
            } catch (e: Exception) {
                report("grabModelError")
                Log.e("AppSENDGrab", "Model failed", e)
            }
        }
    }

    override fun close() {
        closed = true
        owner.lifecycle.removeObserver(observer)
        analysis?.clearAnalyzer()
        val useCases = listOfNotNull(preview, analysis).toTypedArray()
        if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        executor.execute {
            recognizer?.close()
            recognizer = null
            gate.reset()
        }
        executor.shutdown()
    }
}
