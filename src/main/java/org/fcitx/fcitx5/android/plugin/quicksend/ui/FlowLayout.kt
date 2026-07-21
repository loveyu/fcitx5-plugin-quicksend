package org.fcitx.fcitx5.android.plugin.quicksend.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * 简单的横向换行布局，用于段编辑器中展示 [org.fcitx.fcitx5.android.plugin.quicksend.data.ContentSegment] chip。
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val hSpacing = dp(4)
    private val vSpacing = dp(6)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x + cw > maxWidth && x > 0) {
                x = 0
                y += rowHeight + vSpacing
                rowHeight = 0
            }
            x += cw + hSpacing
            rowHeight = maxOf(rowHeight, ch)
        }
        val totalHeight = y + rowHeight + paddingTop + paddingBottom
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rightLimit = r - l - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x + cw > rightLimit && x > paddingLeft) {
                x = paddingLeft
                y += rowHeight + vSpacing
                rowHeight = 0
            }
            child.layout(x, y, x + cw, y + ch)
            x += cw + hSpacing
            rowHeight = maxOf(rowHeight, ch)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
