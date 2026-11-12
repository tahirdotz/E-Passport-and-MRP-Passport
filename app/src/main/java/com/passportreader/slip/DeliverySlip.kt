package com.passportreader.slip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** One scanned delivery slip. [needsCheck] lists fields that no second source confirmed. */
data class DeliverySlip(
    val applicationId: String,
    val name: String,
    val passportType: String,
    val deliveryType: String,
    val collectionDate: String,
    val nid: String,
    val dateOfBirth: String,
    val barcode: String,
    val needsCheck: Set<SlipField>,
    val scannedAt: Long,
    val source: String,
) {
    /** Same slip if the barcode (or, failing that, the application ID) matches. */
    fun sameSlipAs(other: DeliverySlip): Boolean = when {
        barcode.isNotEmpty() && other.barcode.isNotEmpty() -> barcode == other.barcode
        applicationId.isNotEmpty() -> applicationId == other.applicationId
        else -> false
    }

    fun value(field: SlipField): String = when (field) {
        SlipField.APPLICATION_ID -> applicationId
        SlipField.NAME -> name
        SlipField.PASSPORT_TYPE -> passportType
        SlipField.DELIVERY_TYPE -> deliveryType
        SlipField.COLLECTION_DATE -> collectionDate
        SlipField.NID -> nid
        SlipField.DATE_OF_BIRTH -> dateOfBirth
        SlipField.BARCODE -> barcode
    }

    companion object {
        fun from(fields: Map<SlipField, FieldRead>, source: String, now: Long = System.currentTimeMillis()): DeliverySlip {
            fun v(f: SlipField) = fields[f]?.value.orEmpty()
            val unchecked = SlipField.entries.filter { fields[it]?.confirmed != true }.toSet()
            return DeliverySlip(
                v(SlipField.APPLICATION_ID), v(SlipField.NAME), v(SlipField.PASSPORT_TYPE), v(SlipField.DELIVERY_TYPE), v(SlipField.COLLECTION_DATE),
                v(SlipField.NID), v(SlipField.DATE_OF_BIRTH), v(SlipField.BARCODE), unchecked, now, source,
            )
        }
    }
}

object SlipCsv {
    private val header = listOf("Application ID", "Name", "Passport Type", "Delivery Type", "Tentative Collection Date", "NID", "Date of Birth", "Barcode", "Needs check")

    /**
     * CSV that opens correctly in Excel and Google Sheets: UTF-8 BOM, and long numbers written as
     * ="..." so a 17-digit NID isn't turned into 1.98615E+16.
     */
    fun build(slips: List<DeliverySlip>): String {
        val sb = StringBuilder("﻿")
        sb.append(header.joinToString(",")).append("\r\n")
        for (s in slips) {
            val row = listOf(
                number(s.applicationId), text(s.name), text(s.passportType), text(s.deliveryType), text(s.collectionDate),
                number(s.nid), text(s.dateOfBirth), number(s.barcode),
                text(s.needsCheck.joinToString("; ") { it.label }),
            )
            sb.append(row.joinToString(",")).append("\r\n")
        }
        return sb.toString()
    }

    private fun number(v: String) = if (v.isEmpty()) "" else "=\"$v\""
    private fun text(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}

/**
 * Saved slips, kept in the app's private storage so a batch survives the app closing.
 * Never in shared storage; "Clear all" deletes the file.
 */
object SlipStore {
    private const val FILE = "delivery_slips.json"
    private val io = Executors.newSingleThreadExecutor()
    private var slips: MutableList<DeliverySlip>? = null

    @Synchronized fun all(context: Context): List<DeliverySlip> = load(context).toList()

    /** Adds the slip unless it is already saved. Returns false for a duplicate. */
    @Synchronized fun add(context: Context, slip: DeliverySlip): Boolean {
        val list = load(context)
        if (list.any { it.sameSlipAs(slip) }) return false
        list.add(0, slip)
        persist(context)
        return true
    }

    @Synchronized fun update(context: Context, old: DeliverySlip, new: DeliverySlip) {
        val list = load(context)
        val i = list.indexOf(old)
        if (i >= 0) { list[i] = new; persist(context) }
    }

    @Synchronized fun remove(context: Context, slip: DeliverySlip) {
        if (load(context).remove(slip)) persist(context)
    }

    @Synchronized fun clear(context: Context) {
        load(context).clear()
        persist(context)
    }

    private fun load(context: Context): MutableList<DeliverySlip> {
        slips?.let { return it }
        val f = File(context.filesDir, FILE)
        val list = mutableListOf<DeliverySlip>()
        if (f.exists()) runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) list += fromJson(arr.getJSONObject(i))
        }
        slips = list
        return list
    }

    private fun persist(context: Context) {
        val snapshot = JSONArray().apply { slips.orEmpty().forEach { put(toJson(it)) } }.toString()
        val f = File(context.applicationContext.filesDir, FILE)
        io.execute { runCatching { File(f.path + ".tmp").apply { writeText(snapshot) }.renameTo(f) } }
    }

    private fun toJson(s: DeliverySlip) = JSONObject().apply {
        put("applicationId", s.applicationId); put("name", s.name); put("passportType", s.passportType); put("deliveryType", s.deliveryType)
        put("collectionDate", s.collectionDate); put("nid", s.nid); put("dateOfBirth", s.dateOfBirth)
        put("barcode", s.barcode); put("needsCheck", JSONArray(s.needsCheck.map { it.name }))
        put("scannedAt", s.scannedAt); put("source", s.source)
    }

    private fun fromJson(o: JSONObject): DeliverySlip {
        val checks = o.optJSONArray("needsCheck")
        val set = (0 until (checks?.length() ?: 0)).mapNotNull { runCatching { SlipField.valueOf(checks!!.getString(it)) }.getOrNull() }.toSet()
        return DeliverySlip(
            o.optString("applicationId"), o.optString("name"), o.optString("passportType"), o.optString("deliveryType"), o.optString("collectionDate"),
            o.optString("nid"), o.optString("dateOfBirth"), o.optString("barcode"), set,
            o.optLong("scannedAt"), o.optString("source"),
        )
    }
}
