package com.example.faceblurcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraEffect
import androidx.camera.core.ImageAnalysis
import androidx.camera.effects.OverlayEffect
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FaceBlurCam"

        /**
         * true  = real GPU blur (FaceBlurProcessor)
         * false = simple solid-oval mask via OverlayEffect (fallback if the GL path
         *         misbehaves on your device)
         */
        private const val USE_GL_BLUR = true
    }

    private lateinit var controller: LifecycleCameraController
    private lateinit var recordButton: Button
    private var recording: Recording? = null
    private var processor: FaceBlurProcessor? = null

    // Used only by the fallback overlay path.
    @Volatile private var overlayBoxes: List<RectF> = emptyList()
    @Volatile private var overlayLastSeen = 0L
    private val maskPaint = Paint().apply {
        color = Color.argb(255, 40, 40, 40)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startCamera()
        else Toast.makeText(this, "Camera and microphone permissions are required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val previewView = PreviewView(this)
        recordButton = Button(this).apply {
            text = "Record"
            setOnClickListener { toggleRecording() }
        }
        setContentView(
            FrameLayout(this).apply {
                addView(previewView)
                addView(
                    recordButton,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    ).apply { bottomMargin = 96 }
                )
            }
        )

        controller = LifecycleCameraController(this)
        previewView.controller = controller

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            permissionLauncher.launch(needed)
        }
    }

    override fun onDestroy() {
        processor?.release()
        super.onDestroy()
    }

    private fun startCamera() {
        controller.setEnabledUseCases(
            CameraController.IMAGE_ANALYSIS or CameraController.VIDEO_CAPTURE
        )

        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.05f)
                .build()
        )
        val mainExecutor = ContextCompat.getMainExecutor(this)

        controller.setImageAnalysisAnalyzer(
            mainExecutor,
            MlKitAnalyzer(listOf(detector), ImageAnalysis.COORDINATE_SYSTEM_SENSOR, mainExecutor) { result ->
                val faces: List<Face>? = result.getValue(detector)
                if (!faces.isNullOrEmpty()) {
                    // Pad 30% so lag between detection and video doesn't expose an edge.
                    val boxes = faces.map { padded(RectF(it.boundingBox), 0.30f) }
                    processor?.updateFaces(boxes)
                    overlayBoxes = boxes
                    overlayLastSeen = System.nanoTime()
                }
            }
        )

        val effect: CameraEffect = if (USE_GL_BLUR) buildGlBlurEffect() else buildOverlayFallback()
        controller.setEffects(setOf(effect))
    }

    private fun buildGlBlurEffect(): CameraEffect {
        val p = FaceBlurProcessor()
        processor = p
        return FaceBlurEffect(p)
    }

    private fun buildOverlayFallback(): CameraEffect {
        val thread = HandlerThread("overlay").apply { start() }
        val overlay = OverlayEffect(
            CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
            3,
            Handler(thread.looper),
            Consumer { t -> Log.e(TAG, "Overlay error", t) }
        )
        overlay.setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.setMatrix(frame.sensorToBufferTransform)
            if (System.nanoTime() - overlayLastSeen < 400_000_000L) {
                for (box in overlayBoxes) canvas.drawOval(box, maskPaint)
            }
            true
        }
        return overlay
    }

    private fun padded(r: RectF, fraction: Float): RectF {
        val dx = r.width() * fraction
        val dy = r.height() * fraction
        return RectF(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
    }

    private fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "faceblur_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FaceBlurCam")
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        recording = controller.startRecording(
            output,
            AudioConfig.create(true),
            ContextCompat.getMainExecutor(this)
        ) { event ->
            when (event) {
                is VideoRecordEvent.Start -> recordButton.text = "Stop"
                is VideoRecordEvent.Finalize -> {
                    recordButton.text = "Record"
                    val msg = if (event.hasError()) "Recording error ${event.error}"
                    else "Saved to Movies/FaceBlurCam"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** CameraEffect's constructor is protected in CameraX 1.4, so the effect is a tiny subclass. */
class FaceBlurEffect(processor: FaceBlurProcessor) : CameraEffect(
    FaceBlurProcessor.TARGETS,
    processor.glExecutor,
    processor,
    Consumer<Throwable> { t -> Log.e("FaceBlurCam", "Blur effect error", t) }
)

