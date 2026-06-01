/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.text.Layout
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.withSave
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A [TextView] that automatically scales its text to fit the available width
 * when the text overflows, instead of truncating or ellipsizing.
 *
 * Supports all [CharSequence] types including [android.text.SpannableString],
 * with spans (colors, styles, sizes, etc.) correctly rendered via [Layout].
 *
 * Design principle: delegates text storage AND layout building to the parent
 * [TextView]. By passing [Int.MAX_VALUE] as the available width to
 * [super.onMeasure], the parent creates a single-line [Layout] (accessible via
 * [getLayout]) that never wraps or truncates. We then apply canvas scaling
 * in [onDraw] if the text overflows the actual view width.
 *
 * This approach eliminates the need for a separate [Layout] — the parent's
 * layout is the single source of truth for both measurement and drawing.
 * Paint state synchronization (textSize, typeface, etc.) is handled
 * automatically by the parent, so we no longer need to override every
 * paint-affecting setter.
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

    private var needsCalculateTransform = true

    private var translateY = 0f
    private var translateX = 0f
    private var textScaleX = 1f
    private var textScaleY = 1f

    /**
     * Visual centering correction for single-codepoint text in wrapContent views.
     *
     * For glyphs like full-width punctuation "，", the advance width (the cell
     * the font allocates) is much wider than the visible ink. When the view
     * center-aligns by advance width, the ink appears off-center (typically
     * left-biased). This value shifts the canvas so that the ink center —
     * rather than the advance center — aligns with the content center.
     *
     * Computed as: advanceCenter - inkCenter (in Layout coordinates).
     * Zero when not applicable (multi-char text or EXACTLY-width views).
     */
    private var visualCenterCorrection = 0f

    // ---- Text management: fully delegated to parent ----
    // Neither setText() nor getText() is overridden.
    // The parent's internal mText and its Layout are the single source of truth.

    // ---- Measurement ----

    /**
     * Call [super.onMeasure] with an "unlimited width" AT_MOST spec so that
     * the parent creates a single-line [Layout] (via [getLayout]) that does not
     * wrap or truncate. Then recalculate the correct [setMeasuredDimension]
     * based on the actual text width and the original constraints.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)

        // Let the parent build a full-width, single-line Layout.
        val wideSpec = MeasureSpec.makeMeasureSpec(Int.MAX_VALUE, MeasureSpec.AT_MOST)
        super.onMeasure(wideSpec, heightMeasureSpec)

        // Now getLayout() is available — the parent has built it.
        val layout = getLayout()
        if (layout != null && layout.lineCount > 0) {
            val charSequence = getText()

            // Compute visual centering correction for single-codepoint wrapContent.
            // This is purely a drawing-time offset — measurement always uses
            // advance width (the Layout's standard metric).
            if (widthMode != MeasureSpec.EXACTLY &&
                charSequence.isNotEmpty() &&
                Character.codePointCount(charSequence, 0, charSequence.length) == 1
            ) {
                val bounds = Rect()
                paint.getTextBounds(charSequence.toString(), 0, charSequence.length, bounds)
                val advanceWidth = layout.getLineWidth(0)
                val inkCenter = bounds.left + bounds.width() / 2f
                val advanceCenter = advanceWidth / 2f
                visualCenterCorrection = advanceCenter - inkCenter
            } else {
                visualCenterCorrection = 0f
            }

            val textWidth = ceil(layout.getLineWidth(0)).toInt() + paddingLeft + paddingRight
            val maxHeight = if (maxHeight >= 0) maxHeight else Int.MAX_VALUE
            val maxWidth = if (maxWidth >= 0) maxWidth else Int.MAX_VALUE
            val desiredWidth = min(max(textWidth, minimumWidth), maxWidth)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val finalWidth = when (widthMode) {
                MeasureSpec.EXACTLY -> widthSize
                MeasureSpec.AT_MOST -> min(desiredWidth, widthSize)
                else -> desiredWidth
            }
            setMeasuredDimension(finalWidth, measuredHeight)
        }

        needsCalculateTransform = true
    }

    // ---- Transform calculation ----

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (needsCalculateTransform || changed) {
            calculateTransform(right - left, bottom - top)
            needsCalculateTransform = false
        }
    }

    private fun calculateTransform(viewWidth: Int, viewHeight: Int) {
        val layout = getLayout() ?: return
        if (layout.lineCount == 0) return

        val contentWidth = viewWidth - paddingLeft - paddingRight
        val contentHeight = viewHeight - paddingTop - paddingBottom
        val textWidth = layout.getLineWidth(0)

        @SuppressLint("RtlHardcoded")
        val shouldAlignLeft = gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT

        if (textWidth > contentWidth) {
            when (scaleMode) {
                Mode.None -> {
                    textScaleX = 1f
                    textScaleY = 1f
                    translateX = if (shouldAlignLeft) {
                        paddingLeft.toFloat()
                    } else {
                        paddingLeft + (contentWidth - textWidth) / 2f
                    }
                }
                Mode.Horizontal -> {
                    textScaleX = contentWidth / textWidth
                    textScaleY = 1f
                    translateX = paddingLeft.toFloat()
                }
                Mode.Proportional -> {
                    val textScale = contentWidth / textWidth
                    textScaleX = textScale
                    textScaleY = textScale
                    translateX = paddingLeft.toFloat()
                }
            }
        } else {
            textScaleX = 1f
            textScaleY = 1f
            translateX = if (shouldAlignLeft) {
                paddingLeft.toFloat()
            } else {
                paddingLeft + (contentWidth - textWidth) / 2f
            }
        }

        val scaledLayoutHeight = layout.height * textScaleY
        translateY = (contentHeight - scaledLayoutHeight) / 2f + paddingTop
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        if (needsCalculateTransform) {
            calculateTransform(width, height)
            needsCalculateTransform = false
        }
        val layout = getLayout()
        if (layout == null || layout.lineCount == 0) return
        canvas.withSave {
            translate(scrollX.toFloat(), scrollY.toFloat())
            translate(translateX, translateY)
            scale(textScaleX, textScaleY, 0f, 0f)
            // Neutralize Layout's internal horizontal offset.
            //
            // The parent builds the Layout with the view's gravity. When gravity
            // is CENTER, the Layout uses ALIGN_CENTER, which offsets the text
            // start via getLineLeft(0) ≈ (layoutWidth - lineWidth) / 2.
            // Since layoutWidth ≈ 1048576 (Layout.MAX_WIDTH cap on Int.MAX_VALUE),
            // this offset can be ~524000px, making the text completely off-screen.
            //
            // We handle horizontal alignment ourselves via translateX, so we
            // subtract getLineLeft(0) to always draw from x=0 in Layout coords.
            // For ALIGN_NORMAL layouts, getLineLeft(0) == 0, so this is a no-op.
            translate(-layout.getLineLeft(0), 0f)
            // Visual centering correction for single-codepoint wrapContent text.
            //
            // Full-width punctuation like "，" has a wide advance cell but a narrow
            // ink that's off-center within that cell. Without this correction, the
            // advance cell is centered (which is geometrically correct), but the
            // visible glyph appears shifted — typically left-biased.
            //
            // This correction shifts the canvas so the ink center aligns with the
            // content center. It's computed in onMeasure as advanceCenter - inkCenter
            // and is zero for multi-char text or EXACTLY-width views.
            translate(visualCenterCorrection, 0f)
            // super.onDraw() explicitly sets paint.color = mCurTextColor before
            // calling layout.draw(). Since we bypass super.onDraw(), we must
            // replicate this synchronization ourselves.
            paint.color = currentTextColor
            layout.draw(this)
        }
    }

    // ---- Public overrides ----

    override fun getTextScaleX(): Float = textScaleX

    override fun getBaseline(): Int {
        val layout = getLayout()
        if (layout != null && layout.lineCount > 0) {
            return (translateY + layout.getLineBaseline(0) * textScaleY).roundToInt()
        }
        return super.getBaseline()
    }

    // ---- Invalidate transform when properties change ----

    /**
     * Mark the transform as stale and request re-measurement.
     *
     * Since we no longer build our own Layout, we only need to invalidate
     * the transform (translate/scale) calculation. The parent's Layout is
     * automatically rebuilt by [super.onMeasure] on the next layout pass.
     *
     * We no longer need to override setTextSize, setTypeface, etc. — the
     * parent handles paint state changes and will rebuild its Layout when
     * remeasured. The requestLayout() here ensures a remeasure happens.
     */
    private fun invalidateTransform() {
        needsCalculateTransform = true
        requestLayout()
        invalidate()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
        invalidateTransform()
    }

    override fun setGravity(gravity: Int) {
        super.setGravity(gravity)
        invalidateTransform()
    }
}
