package com.passportreader.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Adds system-bar (and cutout) insets to this view's existing padding (edge-to-edge is enforced on Android 15+). */
fun View.applySystemBarPadding(top: Boolean = true, bottom: Boolean = true) {
    val start = paddingLeft; val t = paddingTop; val end = paddingRight; val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.updatePadding(
            left = start + bars.left,
            top = if (top) t + bars.top else t,
            right = end + bars.right,
            bottom = if (bottom) b + bars.bottom else b,
        )
        insets
    }
}
