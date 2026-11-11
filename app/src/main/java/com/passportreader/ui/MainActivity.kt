package com.passportreader.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.passportreader.MrzSource
import com.passportreader.R
import com.passportreader.Session
import com.passportreader.chip.ChipActivity
import com.passportreader.mrz.MrzData
import com.passportreader.scan.ScanActivity

class MainActivity : ComponentActivity() {

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (result.resultCode) {
            RESULT_OK -> afterScan()
            ScanActivity.RESULT_MANUAL -> askManual()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).applySystemBarPadding()

        findViewById<View>(R.id.btn_scan).setOnClickListener {
            Session.startNew()
            scan.launch(Intent(this, ScanActivity::class.java))
        }
        findViewById<View>(R.id.btn_manual).setOnClickListener { askManual() }
        findViewById<View>(R.id.nfc_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = NfcAdapter.getDefaultAdapter(this)
        val status = findViewById<TextView>(R.id.nfc_status)
        val settings = findViewById<View>(R.id.nfc_settings)
        when {
            adapter == null -> { status.setText(R.string.nfc_missing); settings.visibility = View.GONE }
            !adapter.isEnabled -> { status.setText(R.string.nfc_off); settings.visibility = View.VISIBLE }
            else -> { status.setText(R.string.nfc_ready); settings.visibility = View.GONE }
        }
    }

    /** MRZ scanned: go to the chip if this phone can read one, else straight to the MRZ result. */
    private fun afterScan() {
        if (nfcUsable()) startActivity(Intent(this, ChipActivity::class.java))
        else startActivity(Intent(this, ResultActivity::class.java))
    }

    private fun askManual() {
        if (!nfcUsable()) {
            Toast.makeText(this, R.string.nfc_needed, Toast.LENGTH_LONG).show()
            return
        }
        ManualEntry.show(this, null) { doc, dob, exp ->
            Session.startNew()
            Session.mrz = MrzData.forChipAccess(doc, dob, exp)
            Session.mrzSource = MrzSource.MANUAL
            startActivity(Intent(this, ChipActivity::class.java))
        }
    }

    private fun nfcUsable(): Boolean = NfcAdapter.getDefaultAdapter(this)?.isEnabled == true
}
