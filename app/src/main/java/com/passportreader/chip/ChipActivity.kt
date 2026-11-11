package com.passportreader.chip

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.passportreader.MrzSource
import com.passportreader.R
import com.passportreader.Session
import com.passportreader.mrz.MrzData
import com.passportreader.ui.ManualEntry
import com.passportreader.ui.ResultActivity
import com.passportreader.ui.applySystemBarPadding
import java.io.IOException

/**
 * Waits for the passport chip using NFC reader mode and reads it with [ChipReader].
 * The read runs on the NFC callback thread, which is already off the main thread.
 */
class ChipActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var adapter: NfcAdapter? = null
    private lateinit var status: TextView
    private lateinit var statusBox: View
    private lateinit var progress: ProgressBar
    private lateinit var docLabel: TextView
    @Volatile private var reading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chip)
        findViewById<View>(R.id.root).applySystemBarPadding()

        if (Session.mrz == null) { finish(); return }
        adapter = NfcAdapter.getDefaultAdapter(this)
        status = findViewById(R.id.status)
        statusBox = findViewById(R.id.status_box)
        progress = findViewById(R.id.progress)
        docLabel = findViewById(R.id.chip_doc)
        showDocLabel()

        val skip = findViewById<View>(R.id.btn_skip)
        // "No chip" only makes sense when there is scanned MRZ data to fall back to.
        skip.visibility = if (Session.mrzSource == MrzSource.CAMERA) View.VISIBLE else View.GONE
        skip.setOnClickListener {
            startActivity(Intent(this, ResultActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.btn_edit).setOnClickListener {
            ManualEntry.show(this, Session.mrz) { doc, dob, exp ->
                val old = Session.mrz
                // Keep scanned names etc., but use the corrected access values.
                Session.mrz = old?.copy(documentNumber = doc, birthDate = dob, expiryDate = exp)
                    ?: MrzData.forChipAccess(doc, dob, exp)
                showDocLabel()
                showWaiting()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000) }
        adapter?.enableReaderMode(this, this, flags, extras)
    }

    override fun onPause() {
        super.onPause()
        adapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        if (reading) return
        val mrz = Session.mrz ?: return
        reading = true
        vibrate(40)
        runOnUiThread { showBusy("Passport detected…") }
        try {
            val data = ChipReader { msg -> runOnUiThread { showBusy(msg) } }
                .read(tag, mrz.documentNumber, mrz.birthDate, mrz.expiryDate)
            Session.chip = data
            vibrate(120)
            runOnUiThread {
                startActivity(Intent(this, ResultActivity::class.java))
                finish()
            }
        } catch (e: Throwable) {
            android.util.Log.w("ChipActivity", "Chip read failed", e)
            val message = when {
                e is AccessDeniedException -> getString(R.string.chip_denied)
                e.causeChain().any { it is TagLostException || it is IOException } ||
                    e.message?.contains("lost", ignoreCase = true) == true -> getString(R.string.chip_lost)
                else -> getString(R.string.chip_error, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { showError(message) }
        } finally {
            reading = false
        }
    }

    private fun showDocLabel() {
        val m = Session.mrz ?: return
        docLabel.text = "Doc ${m.documentNumber} · DOB ${m.birthDate} · EXP ${m.expiryDate}"
    }

    private fun showWaiting() {
        progress.visibility = View.VISIBLE
        statusBox.setBackgroundResource(R.drawable.card)
        status.setTextColor(ContextCompat.getColor(this, R.color.text))
        status.setText(R.string.chip_waiting)
    }

    private fun showBusy(message: String) {
        progress.visibility = View.VISIBLE
        statusBox.setBackgroundResource(R.drawable.card)
        status.setTextColor(ContextCompat.getColor(this, R.color.text))
        status.text = message
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        statusBox.setBackgroundResource(R.drawable.card_bad)
        status.setTextColor(ContextCompat.getColor(this, R.color.bad))
        status.text = "$message\n\nHold the passport to the phone again to retry."
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }
}
