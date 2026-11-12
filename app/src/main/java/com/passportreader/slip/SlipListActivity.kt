package com.passportreader.slip

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.passportreader.R
import com.passportreader.ui.applySystemBarPadding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Saved delivery slips: review, fix, import from photos, export as CSV. */
class SlipListActivity : ComponentActivity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private val adapter = Adapter()
    private val worker = Executors.newSingleThreadExecutor()

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) importPhotos(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slip_list)
        findViewById<View>(R.id.root).applySystemBarPadding()
        SlipVision.prewarm()

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        title = findViewById(R.id.title)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> edit(adapter.getItem(pos)) }

        findViewById<View>(R.id.btn_scan).setOnClickListener {
            startActivity(Intent(this, SlipScanActivity::class.java))
        }
        findViewById<View>(R.id.btn_import).setOnClickListener {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.btn_export).setOnClickListener { export() }
        findViewById<View>(R.id.btn_clear).setOnClickListener {
            val n = SlipStore.all(this).size
            if (n == 0) return@setOnClickListener
            AlertDialog.Builder(this, R.style.Theme_PassportReader_Dialog)
                .setMessage(getString(R.string.slips_clear_confirm, n))
                .setPositiveButton(R.string.delete) { _, _ -> SlipStore.clear(this); refresh() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun refresh() {
        adapter.items = SlipStore.all(this)
        adapter.notifyDataSetChanged()
        title.text = "${getString(R.string.slips_title)} (${adapter.items.size})"
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ import

    private fun importPhotos(uris: List<Uri>) {
        val dp = resources.displayMetrics.density
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = uris.size }
        val label = TextView(this).apply { text = getString(R.string.slips_importing, 1, uris.size); textSize = 15f }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
            addView(label); addView(bar)
        }
        val progress = AlertDialog.Builder(this, R.style.Theme_PassportReader_Dialog).setView(box).setCancelable(false).show()
        worker.execute {
            var added = 0; var dupes = 0; var failed = 0
            uris.forEachIndexed { i, uri ->
                runOnUiThread { bar.progress = i; label.text = getString(R.string.slips_importing, i + 1, uris.size) }
                val read = SlipVision.readPhoto(this, uri)
                if (read == null || read.fields.size < 2) { failed++; return@forEachIndexed }
                if (SlipStore.add(this, DeliverySlip.from(read.fields, source = "photo"))) added++ else dupes++
            }
            runOnUiThread {
                progress.dismiss()
                refresh()
                AlertDialog.Builder(this, R.style.Theme_PassportReader_Dialog)
                    .setMessage(getString(R.string.slips_import_done, added, dupes, failed))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    // ------------------------------------------------------------------ export

    private fun export() {
        val slips = SlipStore.all(this)
        if (slips.isEmpty()) return
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // don't leave old exports lying around
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(dir, "delivery-slips-$stamp.csv").apply { writeText(SlipCsv.build(slips)) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.slips_export)))
    }

    // ------------------------------------------------------------------ edit

    private fun edit(slip: DeliverySlip) {
        val dp = resources.displayMetrics.density
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0) }
        val inputs = SlipField.entries.associateWith { f ->
            TextView(this).apply {
                text = f.label + if (f in slip.needsCheck) "  • check" else ""
                textSize = 12f
                setTextColor(getColor(if (f in slip.needsCheck) R.color.warn else R.color.text_secondary))
                setPadding(0, (10 * dp).toInt(), 0, 0)
            }.also { form.addView(it) }
            EditText(this).apply {
                setText(slip.value(f))
                inputType = when (f) {
                    SlipField.APPLICATION_ID, SlipField.NID, SlipField.BARCODE -> InputType.TYPE_CLASS_NUMBER
                    SlipField.COLLECTION_DATE, SlipField.DATE_OF_BIRTH -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                }
            }.also { form.addView(it) }
        }
        val scroll = android.widget.ScrollView(this).apply { addView(form) }
        AlertDialog.Builder(this, R.style.Theme_PassportReader_Dialog)
            .setTitle(R.string.slip_edit_title)
            .setView(scroll)
            .setPositiveButton(R.string.save) { _, _ ->
                fun v(f: SlipField) = inputs.getValue(f).text.toString().trim()
                // Saving means a person has looked at it: nothing left to check.
                val updated = slip.copy(
                    applicationId = v(SlipField.APPLICATION_ID), name = v(SlipField.NAME).uppercase(),
                    passportType = v(SlipField.PASSPORT_TYPE).uppercase(), deliveryType = v(SlipField.DELIVERY_TYPE).uppercase(),
                    collectionDate = v(SlipField.COLLECTION_DATE),
                    nid = v(SlipField.NID), dateOfBirth = v(SlipField.DATE_OF_BIRTH), barcode = v(SlipField.BARCODE),
                    needsCheck = emptySet(),
                )
                SlipStore.update(this, slip, updated)
                refresh()
            }
            .setNeutralButton(R.string.delete) { _, _ -> SlipStore.remove(this, slip); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        var items: List<DeliverySlip> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_slip, parent, false)
            val s = items[position]
            v.findViewById<TextView>(R.id.name).text = s.name.ifBlank { "(no name)" }
            v.findViewById<TextView>(R.id.check).visibility = if (s.needsCheck.isEmpty()) View.GONE else View.VISIBLE
            v.findViewById<TextView>(R.id.line1).text = "ID ${s.applicationId.ifBlank { "—" }}  ·  NID ${s.nid.ifBlank { "—" }}"
            v.findViewById<TextView>(R.id.line2).text = listOf(
                s.passportType.ifBlank { null }, s.deliveryType.ifBlank { null }, s.collectionDate.ifBlank { null }?.let { "Collect $it" },
                s.dateOfBirth.ifBlank { null }?.let { "DOB $it" },
            ).filterNotNull().joinToString("  ·  ")
            return v
        }
    }
}
