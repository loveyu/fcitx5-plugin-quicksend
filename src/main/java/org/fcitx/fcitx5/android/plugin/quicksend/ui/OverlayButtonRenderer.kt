package org.fcitx.fcitx5.android.plugin.quicksend.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

object OverlayButtonRenderer {

    fun createPreviewButton(
        context: Context,
        bgColor: Int,
        textColor: Int,
        text: String
    ): TextView {
        val density = context.resources.displayMetrics.density
        val size = (48 * density).toInt()
        return TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
                setSize(size, size)
            }
        }
    }

    fun createCheckerboardTile(): BitmapDrawable {
        val density = 3f
        val cell = (4 * density).toInt()
        val tile = cell * 2
        val bitmap = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint()
        val c1 = 0xFFCCCCCC.toInt()
        val c2 = 0xFFFFFFFF.toInt()
        p.color = c1
        canvas.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
        canvas.drawRect(cell.toFloat(), cell.toFloat(), tile.toFloat(), tile.toFloat(), p)
        p.color = c2
        canvas.drawRect(cell.toFloat(), 0f, tile.toFloat(), cell.toFloat(), p)
        canvas.drawRect(0f, cell.toFloat(), cell.toFloat(), tile.toFloat(), p)
        return BitmapDrawable(android.content.res.Resources.getSystem(), bitmap).apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
    }

    fun wrapWithCheckerboard(context: Context, view: View, frameSizeDp: Int = 80): FrameLayout {
        val density = context.resources.displayMetrics.density
        val frameSize = (frameSizeDp * density).toInt()
        val frame = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(frameSize, frameSize)
            background = createCheckerboardTile()
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.CENTER
        frame.addView(view, lp)
        return frame
    }

    fun chipBorderColor(chipColor: Int): Int {
        val luminance =
            (0.299 * Color.red(chipColor) + 0.587 * Color.green(chipColor) + 0.114 * Color.blue(chipColor)) / 255.0
        val alpha = Color.alpha(chipColor)
        return if (luminance > 0.7 && alpha > 180) {
            Color.argb(200, 60, 60, 60)
        } else {
            Color.argb(80, 160, 160, 160)
        }
    }
}
