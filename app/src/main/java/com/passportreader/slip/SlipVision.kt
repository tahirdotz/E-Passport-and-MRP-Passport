package com.passportreader.slip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * One shared OCR engine and barcode scanner for the whole app. Creating them (and loading their
 * models) is the slowest step of a first scan, so they are built once and warmed up early.
 */
object SlipVision {

    val text: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** 1D formats only: fewer formats to try per frame = faster. */
    val barcodes: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR,
                )
                .build()
        )
    }

    private val warmer = Executors.newSingleThreadExecutor()
    @Volatile private var warmed = false

    /** Loads both models in the background so the first real frame is fast. */
    fun prewarm() {
        if (warmed) return
        warmed = true
        warmer.execute {
            runCatching {
                val img = InputImage.fromBitmap(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888), 0)
                Tasks.await(text.process(img)); Tasks.await(barcodes.process(img))
            }
        }
    }

    /** Runs barcode and text recognition on the same image at the same time. */
    fun processBoth(image: InputImage): Pair<Task<Text>, Task<List<Barcode>>> = text.process(image) to barcodes.process(image)

    fun toLines(text: Text): List<OcrLine> = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
        val b = line.boundingBox ?: return@mapNotNull null
        OcrLine(line.text, b.left, b.top, b.right, b.bottom)
    }

    fun barcodeValues(list: List<Barcode>): List<String> = list.mapNotNull { it.rawValue }

    // ------------------------------------------------------------------ photos / scans

    /**
     * Reads one photo or scan (blocking - call off the main thread). Retries rotated if the
     * picture is sideways, which is common for scans of a landscape slip.
     */
    fun readPhoto(context: Context, uri: Uri): SlipRead? {
        val bitmap = decode(context, uri) ?: return null
        var best: SlipRead? = null
        for (rotation in intArrayOf(0, 90, 270, 180)) {
            val image = InputImage.fromBitmap(bitmap, rotation)
            val (t, b) = processBoth(image)
            val lines = runCatching { toLines(Tasks.await(t)) }.getOrDefault(emptyList())
            val codes = runCatching { barcodeValues(Tasks.await(b)) }.getOrDefault(emptyList())
            val read = SlipParser.parse(lines, codes)
            if (best == null || read.fields.size > best.fields.size) best = read
            // Three or more fields means we have the right orientation.
            if (read.fields.size >= 3) break
        }
        return best
    }

    /** Decodes with EXIF orientation applied, capped at ~3200 px (enough for the barcode's thin bars). */
    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val maxSide = 3200
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        }
    }.getOrNull()
}
