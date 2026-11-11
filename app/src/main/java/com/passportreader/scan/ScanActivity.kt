package com.passportreader.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.passportreader.MrzSource
import com.passportreader.R
import com.passportreader.Session
import com.passportreader.mrz.MrzAccumulator
import com.passportreader.mrz.MrzParser
import com.passportreader.ui.applySystemBarPadding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Camera screen that reads the passport MRZ. Finishes with RESULT_OK once two frames agree on a
 * fully check-digit-valid MRZ; the result is left in [Session].
 */
class ScanActivity : ComponentActivity() {

    companion object {
        /** Result code when the user prefers to type the details. */
        const val RESULT_MANUAL = 2
    }

    private lateinit var previewView: PreviewView
    private lateinit var guide: MrzGuideView
    private lateinit var status: TextView
    private lateinit var torchButton: ImageButton

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    /** OCR results can arrive after the screen is closed; drop them instead of crashing. */
    private val resultExecutor = java.util.concurrent.Executor { r ->
        if (!analysisExecutor.isShutdown) runCatching { analysisExecutor.execute(r) }
    }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val accumulator = MrzAccumulator(required = 2)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var done = false
    private var camera: Camera? = null
    private var torchOn = false

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val slowHint = Runnable {
        if (!done) status.setText(if (accumulator.hasPartial) R.string.scan_hold_still else R.string.scan_slow_hint)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        findViewById<View>(R.id.scan_controls).applySystemBarPadding()
        findViewById<View>(R.id.scan_top).applySystemBarPadding(bottom = false)

        previewView = findViewById(R.id.preview)
        guide = findViewById(R.id.guide)
        status = findViewById(R.id.scan_status)
        torchButton = findViewById(R.id.btn_torch)

        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_manual).setOnClickListener {
            setResult(RESULT_MANUAL); finish()
        }
        torchButton.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            torchButton.setImageResource(if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }
        setupTapToFocus()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        main.postDelayed(slowHint, 12_000)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(slowHint)
    }

    override fun onDestroy() {
        super.onDestroy()
        done = true
        analysisExecutor.shutdown()
        recognizer.close()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // MRZ characters are small: ask for ~1080p so OCR gets enough pixels per character.
            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val preview = Preview.Builder().setResolutionSelector(resolution).build()
            preview.surfaceProvider = previewView.surfaceProvider
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, ::analyze)
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                torchButton.visibility = if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.camera_failed, e.message), Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (done || media == null) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        recognizer.process(InputImage.fromMediaImage(media, rotation))
            .addOnCompleteListener(resultExecutor) { task ->
                try {
                    if (!task.isSuccessful || done) return@addOnCompleteListener
                    val read = MrzParser.findInRows(rowsOf(task.result)) ?: return@addOnCompleteListener
                    main.post { if (!done) { guide.locked = true; status.setText(R.string.scan_hold_still) } }
                    val agreed = accumulator.add(read) ?: return@addOnCompleteListener
                    done = true
                    val page = runCatching { proxy.toBitmap().rotated(rotation) }.getOrNull()
                    main.post { finishWith(agreed, page) }
                } finally {
                    proxy.close()
                }
            }
    }

    private fun finishWith(mrz: com.passportreader.mrz.MrzData, page: Bitmap?) {
        guide.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
        Session.mrz = mrz
        Session.mrzSource = MrzSource.CAMERA
        Session.pageImage = page
        Session.pageFace = null
        Session.chip = null
        setResult(RESULT_OK)
        finish()
    }

    /**
     * Groups recognised lines into visual rows, top to bottom. ML Kit sometimes splits one MRZ
     * line into several pieces side by side; joining by row puts them back together.
     */
    private fun rowsOf(text: Text): List<String> {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.boundingBox!!.centerY() }
        val heights = sorted.map { it.boundingBox!!.height() }.sorted()
        val tolerance = (heights[heights.size / 2] * 0.5f).coerceAtLeast(4f)
        val rows = mutableListOf<MutableList<Text.Line>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            val y = line.boundingBox!!.centerY()
            if (row != null && abs(y - row.last().boundingBox!!.centerY()) < tolerance) row += line
            else rows += mutableListOf(line)
        }
        return rows.map { row -> row.sortedBy { it.boundingBox!!.left }.joinToString("") { it.text } }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        previewView.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
            }
            true
        }
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap =
        if (degrees == 0) this
        else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}
