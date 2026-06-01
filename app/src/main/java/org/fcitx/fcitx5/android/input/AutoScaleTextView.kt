/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.withSave
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A [TextView] that automatically scales its text to fit the available width
 * when the text overflows, instead of truncating or ellipsizing.
 *
 * Three scaling modes:
 * - [Mode.None]: no scaling, text overflows freely
 * - [Mode.Horizontal]: compress horizontally only (text appears "condensed")
 * - [Mode.Proportional]: scale both axes proportionally, vertically centered
 *
 * ## Transform geometry
 *
 * Drawing uses a translate-then-scale model that keeps the math derivable:
 *
 * ```
 * translate(translateX, translateY)   // move to the text drawing origin
 * scale(textScaleX, textScaleY, 0, 0) // scale around that origin
 * drawText(text, 0, 0, paint)         // draw at the scaled origin
 * ```
 *
 * - [translateX] is the horizontal position of the text's left edge in
 *   view coordinates, accounting for padding and gravity alignment.
 * - [translateY] is the vertical position of the text baseline in view
 *   coordinates, accounting for padding and vertical centering. It is
 *   **independent of the scale factor** because scaling happens after the
 *   translation, with pivot at (0, 0).
 */
@SuppressLint("AppCompatCustomView")
class AutoScaleTextView @JvmOverloads constructor(
    context: Context?,
    attributeSet: AttributeSet? = null
) : TextView(context, attributeSet) {

    enum class Mode {
        /**
         * do not scale or ellipse text, overflow when cannot fit width
         */
        None,
        /**
         * only scale in X axis, makes text looks "condensed" or "slim"
         */
        Horizontal,
        /**
         * scale both in X and Y axis, align center vertically
         */
        Proportional
    }

    var scaleMode = Mode.None

    private lateinit var text: String

    private var needsMeasureText = true
    private val fontMetrics = Paint.FontMetrics()
    private val textBounds = Rect()

    private var needsCalculateTransform = true

    /** Horizontal position of the text left edge in view coordinates (after padding & alignment). */
    private var translateX = 0f

    /** Vertical position of the text baseline in view coordinates (after padding & centering). */
    private var translateY = 0f

    private var textScaleX = 1f
    private var textScaleY = 1f

    override fun setText(charSequence: CharSequence?, bufferType: BufferType) {
        // setText can be called in super constructor
        if (!::text.isInitialized || charSequence == null || !text.contentEquals(charSequence)) {
            needsMeasureText = true
            needsCalculateTransform = true
            text = charSequence?.toString() ?: ""
            requestLayout()
            invalidate()
        }
    }

    override fun getText(): CharSequence {
        return text
    }

    // ---- Measurement ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val width = measureTextBounds().width() + paddingLeft + paddingRight
        val height = ceil(fontMetrics.bottom - fontMetrics.top + paddingTop + paddingBottom).toInt()
        val maxHeight = if (maxHeight >= 0) maxHeight else Int.MAX_VALUE
        val maxWidth = if (maxWidth >= 0) maxWidth else Int.MAX_VALUE
        setMeasuredDimension(
            measure(widthMode, widthSize, min(max(width, minimumWidth), maxWidth)),
            measure(heightMode, heightSize, min(max(height, minimumHeight), maxHeight))
        )
    }

    private fun measure(specMode: Int, specSize: Int, calculatedSize: Int): Int = when (specMode) {
        MeasureSpec.EXACTLY -> specSize
        MeasureSpec.AT_MOST -> min(calculatedSize, specSize)
        else -> calculatedSize
    }

    private fun measureTextBounds(): Rect {
        if (needsMeasureText) {
            val paint = paint
            paint.getFontMetrics(fontMetrics)
            val codePointCount = Character.codePointCount(text, 0, text.length)
            if (codePointCount == 1) {
                // use actual text bounds when there is only one "character",
                // eg. full-width punctuation
                paint.getTextBounds(text, 0, text.length, textBounds)
            } else {
                textBounds.set(
                    /* left = */ 0,
                    /* top = */ floor(fontMetrics.top).toInt(),
                    /* right = */ ceil(paint.measureText(text)).toInt(),
                    /* bottom = */ ceil(fontMetrics.bottom).toInt()
                )
            }
            needsMeasureText = false
        }
        return textBounds
    }

    // ---- Transform calculation ----

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (needsCalculateTransform || changed) {
            calculateTransform(right - left, bottom - top)
            needsCalculateTransform = false
        }
    }

    /**
     * Compute [translateX], [translateY], [textScaleX], [textScaleY] such that:
     *
     * ```
     * canvas.translate(translateX, translateY)
     * canvas.scale(textScaleX, textScaleY, 0f, 0f)
     * canvas.drawText(text, 0f, 0f, paint)
     * ```
     *
     * renders the text correctly aligned and scaled within the view.
     *
     * Key invariant: [translateY] positions the **baseline** in view coordinates
     * and must not contain any scale compensation. Scale is applied **after**
     * translation with pivot at (0, 0), so the two are geometrically independent.
     */
    private fun calculateTransform(viewWidth: Int, viewHeight: Int) {
        val contentWidth = viewWidth - paddingLeft - paddingRight
        val contentHeight = viewHeight - paddingTop - paddingBottom
        measureTextBounds()
        val textWidth = textBounds.width()

        @SuppressLint("RtlHardcoded")
        val shouldAlignLeft = gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT

        // --- Scale factors ---
        if (textWidth > contentWidth) {
            when (scaleMode) {
                Mode.None -> {
                    textScaleX = 1f
                    textScaleY = 1f
                }
                Mode.Horizontal -> {
                    textScaleX = contentWidth.toFloat() / textWidth.toFloat()
                    textScaleY = 1f
                }
                Mode.Proportional -> {
                    val textScale = contentWidth.toFloat() / textWidth.toFloat()
                    textScaleX = textScale
                    textScaleY = textScale
                }
            }
        } else {
            textScaleX = 1f
            textScaleY = 1f
        }

        // --- Horizontal translation (text left edge in view coords) ---
        //
        // textBounds.left can be non-zero for single-char getTextBounds results
        // (e.g. full-width punctuation where the ink starts right of x=0).
        // We offset by -textBounds.left so that drawing at x=0 places the ink
        // at the visual left edge of the text.
        val textLeftOffset = -textBounds.left.toFloat()
        translateX = if (shouldAlignLeft) {
            paddingLeft.toFloat() + textLeftOffset
        } else {
            // Scale the text width for centering calculation when text overflows
            // and we are in a scaling mode. In Mode.None with overflow, the text
            // simply extends beyond the content area from the center point.
            val effectiveTextWidth = textWidth * textScaleX
            paddingLeft + (contentWidth - effectiveTextWidth) / 2f + textLeftOffset
        }

        // --- Vertical translation (text baseline in view coords) ---
        //
        // The baseline sits at -fontMetrics.top below the top of the font's
        // bounding box. To center the font vertically within contentHeight:
        //   topOfFont = paddingTop + (contentHeight - fontHeight) / 2
        //   baseline  = topOfFont + (-fontMetrics.top)
        //
        // Since scale pivot is at (0, 0), the baseline position in view
        // coordinates is independent of textScaleY. The scale then compresses
        // the text around this baseline point, which naturally keeps it centered.
        val fontHeight = fontMetrics.bottom - fontMetrics.top
        val scaledFontHeight = fontHeight * textScaleY
        val topOfFont = paddingTop + (contentHeight - scaledFontHeight) / 2f
        translateY = topOfFont - fontMetrics.top
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        if (needsCalculateTransform) {
            calculateTransform(width, height)
            needsCalculateTransform = false
        }
        val paint = paint
        paint.color = currentTextColor
        canvas.withSave {
            translate(scrollX.toFloat(), scrollY.toFloat())
            // Step 1: Move to the text drawing origin in view coordinates.
            // After this, (0, 0) in the current coordinate system is where
            // the text baseline starts.
            translate(translateX, translateY)
            // Step 2: Scale around (0, 0) — the drawing origin we just
            // moved to. This ensures scaling compresses text toward its
            // baseline/left-edge rather than toward some arbitrary pivot.
            scale(textScaleX, textScaleY, 0f, 0f)
            // Step 3: Draw text at the scaled origin.
            drawText(text, 0f, 0f, paint)
        }
    }

    // ---- Public overrides ----

    override fun getTextScaleX(): Float {
        return textScaleX
    }

    override fun getBaseline(): Int {
        // Baseline in view coordinates, accounting for vertical scale.
        // translateY positions the baseline for an unscaled font; after
        // scale, the actual baseline position scales from the top-of-font.
        val fontHeight = fontMetrics.bottom - fontMetrics.top
        val scaledFontHeight = fontHeight * textScaleY
        val topOfFont = paddingTop + (measuredHeight - paddingTop - paddingBottom - scaledFontHeight) / 2f
        return (topOfFont - fontMetrics.top * textScaleY).roundToInt()
    }
}
