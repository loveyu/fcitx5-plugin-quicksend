package org.fcitx.fcitx5.android.plugin.quicksend.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.plugin.quicksend.R

class ColorPickerDialog(
    context: Context,
    private val initialColor: Int,
    private val otherColor: Int? = null,
    private val isEditingBackground: Boolean = true,
    private val buttonText: String = "发",
    private val onColorPicked: (Int) -> Unit
) : AlertDialog.Builder(context) {

    private var currentColor = initialColor
    private var updating = false

    private var hueSeek: SeekBar? = null
    private var satSeek: SeekBar? = null
    private var briSeek: SeekBar? = null
    private var alphaSeek: SeekBar? = null
    private var hexInput: EditText? = null
    private var previewFrame: FrameLayout? = null
    private var previewBtn: TextView? = null
    private var presetsGrid: RecyclerView? = null

    private val hsv = FloatArray(3)

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        setView(root)

        hueSeek = root.findViewById(R.id.hue_seek)
        satSeek = root.findViewById(R.id.sat_seek)
        briSeek = root.findViewById(R.id.bri_seek)
        alphaSeek = root.findViewById(R.id.alpha_seek)
        hexInput = root.findViewById(R.id.hex_input)
        previewFrame = root.findViewById(R.id.preview_frame)
        presetsGrid = root.findViewById(R.id.presets_grid)

        previewBtn = OverlayButtonRenderer.createPreviewButton(
            context, initialColor,
            if (isEditingBackground && otherColor != null) otherColor
            else contrastTextColor(initialColor),
            buttonText
        )
        previewFrame?.let { frame ->
            frame.background = OverlayButtonRenderer.createCheckerboardTile()
            frame.removeAllViews()
            frame.addView(previewBtn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER })
        }

        initSliders()
        updateFromColor(initialColor)

        hexInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val hex = s?.toString().orEmpty().removePrefix("#")
                if (hex.length == 8) {
                    runCatching {
                        val c = (hex.toLong(16) and 0xFFFFFFFFL).toInt()
                        updateFromColor(c)
                    }
                }
            }
        })

        presetsGrid?.layoutManager = GridLayoutManager(context, 4)
        presetsGrid?.adapter = PresetAdapter(context) { preset ->
            updateFromColor(preset.color)
        }

        setPositiveButton(android.R.string.ok) { _, _ ->
            onColorPicked(currentColor)
        }
        setNegativeButton(android.R.string.cancel, null)
    }

    private fun initSliders() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updating || !fromUser) return
                applySliders()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        hueSeek?.setOnSeekBarChangeListener(listener)
        satSeek?.setOnSeekBarChangeListener(listener)
        briSeek?.setOnSeekBarChangeListener(listener)
        alphaSeek?.setOnSeekBarChangeListener(listener)
    }

    private fun updateFromColor(color: Int) {
        currentColor = color
        Color.colorToHSV(color, hsv)
        updating = true
        hueSeek?.progress = hsv[0].toInt()
        satSeek?.progress = (hsv[1] * 100).toInt()
        briSeek?.progress = (hsv[2] * 100).toInt()
        alphaSeek?.progress = Color.alpha(color)
        hexInput?.setText(String.format("#%08X", color))
        updating = false
        updatePreview()
    }

    private fun applySliders() {
        val h = hueSeek?.progress?.toFloat() ?: 0f
        val s = (satSeek?.progress ?: 100) / 100f
        val v = (briSeek?.progress ?: 100) / 100f
        val a = alphaSeek?.progress ?: 255
        currentColor = Color.HSVToColor(a, floatArrayOf(h, s, v))
        updating = true
        hexInput?.setText(String.format("#%08X", currentColor))
        updating = false
        updatePreview()
    }

    private fun updatePreview() {
        val bgColor: Int
        val txtColor: Int
        if (otherColor != null) {
            if (isEditingBackground) {
                bgColor = currentColor
                txtColor = otherColor
            } else {
                bgColor = otherColor
                txtColor = currentColor
            }
        } else {
            bgColor = currentColor
            txtColor = contrastTextColor(currentColor)
        }
        val density = previewBtn?.resources?.displayMetrics?.density ?: 3f
        val size = (48 * density).toInt()
        previewBtn?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
            setSize(size, size)
        }
        previewBtn?.setTextColor(txtColor)
    }

    companion object {
        fun contrastTextColor(bg: Int): Int {
            val r = Color.red(bg)
            val g = Color.green(bg)
            val b = Color.blue(bg)
            val a = Color.alpha(bg)
            if (a < 128) return Color.WHITE
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            return if (luminance > 0.5) Color.BLACK else Color.WHITE
        }

        val PRESETS = listOf(
            "经典蓝" to 0xE61976D2.toInt(),
            "珊瑚红" to 0xE6FF5722.toInt(),
            "翡翠绿" to 0xE64CAF50.toInt(),
            "琥珀橙" to 0xE6FF9800.toInt(),
            "紫罗兰" to 0xE69C27B0.toInt(),
            "深洋蓝" to 0xE60096A7.toInt(),
            "靛青" to 0xE63F51B5.toInt(),
            "玫瑰粉" to 0xE6E91E63.toInt(),
            "石墨灰" to 0xE6607D8B.toInt(),
            "冰霜白" to 0xD9FAFAFA.toInt(),
            "柠檬黄" to 0xD9FFEB3B.toInt(),
            "森林绿" to 0xE62E7D32.toInt(),
            "烈焰橙" to 0xE6E64A19.toInt(),
            "宝石蓝" to 0xE60D47A1.toInt(),
            "藤紫" to 0xE68E24AA.toInt(),
            "薄荷青" to 0xE600BCD4.toInt(),
            "暗夜黑" to 0xD9424242.toInt(),
            "桃红" to 0xE6F06292.toInt(),
            "橄榄绿" to 0xE6689F38.toInt(),
            "暮光紫" to 0xE67E57C2.toInt(),
        )
    }
}

private class PresetAdapter(
    private val context: Context,
    private val onClick: (PresetItem) -> Unit
) : RecyclerView.Adapter<PresetAdapter.Holder>() {

    data class PresetItem(val name: String, val color: Int)

    private val items = ColorPickerDialog.PRESETS.map { (name, color) ->
        PresetItem(name, color)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val size = (36 * context.resources.displayMetrics.density).toInt()
        val chip = View(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
        }
        return Holder(chip)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(item.color)
            setStroke(2, OverlayButtonRenderer.chipBorderColor(item.color))
        }
        holder.chip.background = drawable
        holder.chip.setOnClickListener { onClick(item) }
    }

    class Holder(val chip: View) : RecyclerView.ViewHolder(chip)
}
