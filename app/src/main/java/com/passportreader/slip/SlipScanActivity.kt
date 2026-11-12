package com.passportreader.slip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.passportreader.R
import com.passportreader.scan.MrzGuideView
import com.passportreader.ui.applySystemBarPadding
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Continuous delivery-slip scanner built for throughput: barcode + OCR run together on every
 * frame, cross-checked fields are accepted from a single frame, and each finished slip is saved
 * automatically (beep + vibrate) so the next slip can be scanned straight away.
 */
class SlipScanActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var guide: MrzGuideView
    private lateinit var status: TextView
    private lateinit var fieldsBox: LinearLayout
    private lateinit var savedCount: TextView
    private lateinit var saveAnyway: View
    private lateinit var torch: ImageButton

    private val analysis = Executors.newSingleThreadExecutor()
    private val results = Executor { r -> if (!analysis.isShutdown) runCatching { analysis.execute(r) } }
    private val main = Handler(Looper.getMainLooper())
    private val accumulator = SlipAccumulator()
    private val valueViews = HashMap<SlipField, TextView>()
    private val markViews = HashMap<SlipField, TextView>()

    private var camera: Camera? = null
    private var torchOn = false
    private var tone: ToneGenerator? = null
    @Volatile private var pausedUntil = 0L
    @Volatile private var firstFieldAt = 0L
    @Volatile private var closed = false
    private var saved = 0

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slip_scan)
        findViewById<View>(R.id.top).applySystemBarPadding(bottom = false)
        findViewById<View>(R.id.panel).applySystemBarPadding(top = false)
        SlipVision.prewarm()

        previewView = findViewById(R.id.preview)
        guide = findViewById<MrzGuideView>(R.id.guide).apply {
            aspect = 1.414f          // delivery slips are landscape, roughly A-series proportions
            showMrzBand = false
            topFraction = 0.11f
        }
        status = findViewById(R.id.status)
        fieldsBox = findViewById(R.id.fields)
        savedCount = findViewById(R.id.saved_count)
        saveAnyway = findViewById(R.id.btn_save_anyway)
        torch = findViewById(R.id.btn_torch)
        buildFieldRows()
        updateCount()

        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_done).setOnClickListener {
            startActivity(Intent(this, SlipListActivity::class.java)); finish()
        }
        saveAnyway.setOnClickListener {
            analysis.execute { val best = accumulator.bestEffort(); main.post { save(best) } }
        }
        torch.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            torch.setImageResource(if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }
        setupTapToFocus()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        closed = true
        analysis.shutdown()
        tone?.release()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // 4:3 at ~1440p: in portrait the landscape slip gets ~1440 px across, enough for the barcode's thin bars.
            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(resolution).build()
            preview.surfaceProvider = previewView.surfaceProvider
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(analysis, ::analyze)
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                torch.visibility = if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.INVISIBLE
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.camera_failed, e.message), Toast.LENGTH_LONG).show(); finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (closed || media == null || SystemClock.elapsedRealtime() < pausedUntil) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val (textTask, barcodeTask) = SlipVision.processBoth(image)
        Tasks.whenAllComplete(textTask, barcodeTask).addOnCompleteListener(results) {
            try {
                if (closed || SystemClock.elapsedRealtime() < pausedUntil) return@addOnCompleteListener
                val lines = if (textTask.isSuccessful) SlipVision.toLines(textTask.result) else emptyList()
                val codes = if (barcodeTask.isSuccessful) SlipVision.barcodeValues(barcodeTask.result) else emptyList()
                val read = SlipParser.parse(lines, codes)
                if (read.fields.isEmpty()) return@addOnCompleteListener
                accumulator.add(read)
                if (firstFieldAt == 0L) firstFieldAt = SystemClock.elapsedRealtime()
                val snapshot = LinkedHashMap(accumulator.fields)
                val best = accumulator.bestEffort()
                val complete = accumulator.isComplete
                main.post { if (!closed) render(snapshot, best, complete) }
            } finally {
                proxy.close()
            }
        }
    }

    private fun render(accepted: Map<SlipField, FieldRead>, best: Map<SlipField, FieldRead>, complete: Boolean) {
        guide.locked = accepted.isNotEmpty()
        for (f in SlipField.entries) {
            val a = accepted[f]
            val b = best[f]
            valueViews[f]?.apply {
                text = a?.value ?: b?.value ?: "…"
                setTextColor(if (a != null) Color.WHITE else Color.argb(140, 255, 255, 255))
            }
            markViews[f]?.apply {
                text = if (a != null) (if (a.confirmed) "✓" else "✓?") else "○"
                setTextColor(if (a != null) Color.rgb(76, 217, 100) else Color.argb(140, 255, 255, 255))
            }
        }
        if (complete) {
            save(accepted)
        } else {
            status.text = "Reading… ${accepted.size} of ${SlipField.entries.size}"
            // Offer a manual save if one field just won't come (e.g. covered by a stamp).
            val stuck = firstFieldAt != 0L && SystemClock.elapsedRealtime() - firstFieldAt > 5_000 && best.size >= 3
            saveAnyway.visibility = if (stuck) View.VISIBLE else View.GONE
        }
    }

    private fun save(fields: Map<SlipField, FieldRead>) {
        if (fields.isEmpty()) return
        val slip = DeliverySlip.from(fields, source = "camera")
        val added = SlipStore.add(this, slip)
        pausedUntil = SystemClock.elapsedRealtime() + 900   // time to swap in the next slip
        analysis.execute { accumulator.reset(); firstFieldAt = 0L }
        saveAnyway.visibility = View.GONE
        if (added) {
            saved++
            updateCount()
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            fieldsBox.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            status.text = getString(R.string.slip_saved, slip.name.ifBlank { slip.applicationId })
        } else {
            status.setText(R.string.slip_duplicate)
        }
        main.postDelayed({
            if (closed) return@postDelayed
            render(emptyMap(), emptyMap(), false)
            status.setText(R.string.slip_next)
            guide.locked = false
        }, 900)
    }

    private fun updateCount() {
        val total = SlipStore.all(this).size
        savedCount.text = if (saved > 0) "+$saved · $total saved" else "$total saved"
    }

    private fun buildFieldRows() {
        val dp = resources.displayMetrics.density
        for (f in SlipField.entries) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt()) }
            val mark = TextView(this).apply { text = "○"; textSize = 14f; setTextColor(Color.argb(140, 255, 255, 255)); width = (26 * dp).toInt() }
            val label = TextView(this).apply { text = f.label; textSize = 13f; setTextColor(Color.argb(180, 255, 255, 255)); width = (150 * dp).toInt() }
            val value = TextView(this).apply {
                text = "…"; textSize = 14f; setTextColor(Color.argb(140, 255, 255, 255)); typeface = Typeface.DEFAULT_BOLD
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(mark); row.addView(label)
            row.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fieldsBox.addView(row)
            markViews[f] = mark; valueViews[f] = value
        }
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
}
