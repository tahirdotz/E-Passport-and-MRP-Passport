package com.passportreader

import android.graphics.Bitmap
import com.passportreader.chip.ChipData
import com.passportreader.mrz.MrzData

/** Where the MRZ values came from. */
enum class MrzSource { CAMERA, MANUAL }

/**
 * In-memory hand-off between screens for one passport. Bitmaps are too large for Intents and
 * nothing here should ever be written to disk.
 */
object Session {
    var mrz: MrzData? = null
    var mrzSource: MrzSource = MrzSource.CAMERA
    /** Camera frame of the data page at the moment the MRZ was accepted. */
    var pageImage: Bitmap? = null
    /** Portrait cut out of [pageImage] (used when there is no chip photo). */
    var pageFace: Bitmap? = null
    var chip: ChipData? = null

    fun startNew() {
        mrz = null; mrzSource = MrzSource.CAMERA; pageImage = null; pageFace = null; chip = null
    }
}
