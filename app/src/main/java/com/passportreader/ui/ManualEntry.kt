package com.passportreader.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.passportreader.R
import com.passportreader.mrz.MrzData
import com.passportreader.mrz.MrzDates

/**
 * Asks for the three values that unlock a chip (document number, birth date, expiry date).
 * Used when the MRZ can't be scanned, or when the chip rejected a scanned value.
 */
object ManualEntry {

    fun show(activity: Activity, prefill: MrzData?, onDone: (documentNumber: String, birthYymmdd: String, expiryYymmdd: String) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_manual, null)
        val doc = view.findViewById<EditText>(R.id.doc)
        val dob = view.findViewById<EditText>(R.id.dob)
        val exp = view.findViewById<EditText>(R.id.exp)
        prefill?.let {
            doc.setText(it.documentNumber)
            dob.setText(toDisplay(it.birthDate, true))
            exp.setText(toDisplay(it.expiryDate, false))
        }
        val dialog = AlertDialog.Builder(activity, R.style.Theme_PassportReader_Dialog)
            .setTitle(R.string.manual_title)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val d = doc.text.toString().uppercase().filter { it.isLetterOrDigit() }
                val b = toYymmdd(dob.text.toString())
                val e = toYymmdd(exp.text.toString())
                if (d.isEmpty() || b == null || e == null) {
                    Toast.makeText(activity, R.string.manual_invalid, Toast.LENGTH_LONG).show()
                } else {
                    dialog.dismiss()
                    onDone(d, b, e)
                }
            }
        }
        dialog.show()
    }

    private fun toDisplay(yymmdd: String, birth: Boolean): String {
        val date = MrzDates.parse(yymmdd, birth) ?: return ""
        return "%02d/%02d/%04d".format(date.dayOfMonth, date.monthValue, date.year)
    }

    /** DD/MM/YYYY (any separator) to the MRZ's YYMMDD. */
    internal fun toYymmdd(input: String): String? {
        val digits = input.filter { it.isDigit() }
        if (digits.length != 8) return null
        val dd = digits.substring(0, 2); val mm = digits.substring(2, 4); val yy = digits.substring(6, 8)
        val result = yy + mm + dd
        return if (MrzDates.isValid(result)) result else null
    }
}
