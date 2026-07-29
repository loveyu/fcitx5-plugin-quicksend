package org.fcitx.fcitx5.android.plugin.quicksend

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.fcitx.fcitx5.android.plugin.quicksend.databinding.ActivityAppearanceBinding
import org.fcitx.fcitx5.android.plugin.quicksend.ui.ColorPickerDialog
import org.fcitx.fcitx5.android.plugin.quicksend.ui.OverlayButtonRenderer

class AppearanceActivity : Activity() {

    private lateinit var binding: ActivityAppearanceBinding
    private val prefs by lazy { getSharedPreferences(QuickSendPrefs.FILE, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.buttonTextInput.setText(
            prefs.getString(QuickSendPrefs.BUTTON_TEXT, QuickSendPrefs.BUTTON_TEXT_DEFAULT)
        )
        binding.buttonTextInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty().ifBlank { QuickSendPrefs.BUTTON_TEXT_DEFAULT }
                prefs.edit().putString(QuickSendPrefs.BUTTON_TEXT, text).apply()
                refreshAllPreviews()
            }
        })

        setupColorChip(
            binding.chipBgLight, QuickSendPrefs.OVERLAY_BG_COLOR, QuickSendPrefs.DEFAULT_BG_COLOR,
            isEditingBackground = true, otherKey = QuickSendPrefs.OVERLAY_TEXT_COLOR, otherDefault = QuickSendPrefs.DEFAULT_TEXT_COLOR,
            onUpdate = { refreshPreviewLight() }
        )
        setupColorChip(
            binding.chipBgDark, QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, QuickSendPrefs.DEFAULT_BG_COLOR,
            isEditingBackground = true, otherKey = QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, otherDefault = QuickSendPrefs.DEFAULT_TEXT_COLOR,
            onUpdate = { refreshPreviewDark() }
        )
        setupColorChip(
            binding.chipTextLight, QuickSendPrefs.OVERLAY_TEXT_COLOR, QuickSendPrefs.DEFAULT_TEXT_COLOR,
            isEditingBackground = false, otherKey = QuickSendPrefs.OVERLAY_BG_COLOR, otherDefault = QuickSendPrefs.DEFAULT_BG_COLOR,
            onUpdate = { refreshPreviewLight() }
        )
        setupColorChip(
            binding.chipTextDark, QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, QuickSendPrefs.DEFAULT_TEXT_COLOR,
            isEditingBackground = false, otherKey = QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, otherDefault = QuickSendPrefs.DEFAULT_BG_COLOR,
            onUpdate = { refreshPreviewDark() }
        )

        binding.presetsLightButton.setOnClickListener { showPresetsDialog(isLight = true) }
        binding.presetsDarkButton.setOnClickListener { showPresetsDialog(isLight = false) }

        refreshPreviewLight()
        refreshPreviewDark()
    }

    private fun setupColorChip(
        container: View,
        key: String,
        defaultColor: Int,
        isEditingBackground: Boolean,
        otherKey: String,
        otherDefault: Int,
        onUpdate: () -> Unit
    ) {
        refreshColorChip(container, prefs.getInt(key, defaultColor))
        container.setOnClickListener {
            val cur = prefs.getInt(key, defaultColor)
            val other = prefs.getInt(otherKey, otherDefault)
            val btnText = prefs.getString(QuickSendPrefs.BUTTON_TEXT, QuickSendPrefs.BUTTON_TEXT_DEFAULT) ?: "发"
            ColorPickerDialog(
                this, cur,
                otherColor = other,
                isEditingBackground = isEditingBackground,
                buttonText = btnText
            ) { newColor ->
                prefs.edit().putInt(key, newColor).apply()
                refreshColorChip(container, newColor)
                onUpdate()
            }.show()
        }
    }

    private fun refreshColorChip(view: View, color: Int) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(2, OverlayButtonRenderer.chipBorderColor(color))
        }
    }

    private fun refreshAllPreviews() {
        refreshPreviewLight()
        refreshPreviewDark()
    }

    private fun refreshPreviewLight() {
        val bg = prefs.getInt(QuickSendPrefs.OVERLAY_BG_COLOR, QuickSendPrefs.DEFAULT_BG_COLOR)
        val text = prefs.getInt(QuickSendPrefs.OVERLAY_TEXT_COLOR, QuickSendPrefs.DEFAULT_TEXT_COLOR)
        setPreview(binding.btnPreviewLight, bg, text)
    }

    private fun refreshPreviewDark() {
        val bg = prefs.getInt(QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, QuickSendPrefs.DEFAULT_BG_COLOR)
        val text = prefs.getInt(QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, QuickSendPrefs.DEFAULT_TEXT_COLOR)
        setPreview(binding.btnPreviewDark, bg, text)
    }

    private fun setPreview(container: ViewGroup, bgColor: Int, textColor: Int) {
        val btnText = prefs.getString(QuickSendPrefs.BUTTON_TEXT, QuickSendPrefs.BUTTON_TEXT_DEFAULT) ?: "发"
        container.removeAllViews()
        val btn = OverlayButtonRenderer.createPreviewButton(this, bgColor, textColor, btnText)
        val wrapped = OverlayButtonRenderer.wrapWithCheckerboard(this, btn, frameSizeDp = 80)
        container.addView(wrapped)
    }

    private fun showPresetsDialog(isLight: Boolean) {
        val bgKey = if (isLight) QuickSendPrefs.OVERLAY_BG_COLOR else QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT
        val textKey = if (isLight) QuickSendPrefs.OVERLAY_TEXT_COLOR else QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT
        val chipBgView = if (isLight) binding.chipBgLight else binding.chipBgDark
        val chipTextView = if (isLight) binding.chipTextLight else binding.chipTextDark

        val btnText = prefs.getString(QuickSendPrefs.BUTTON_TEXT, QuickSendPrefs.BUTTON_TEXT_DEFAULT) ?: "发"
        val density = resources.displayMetrics.density

        val root = android.widget.ScrollView(this).apply {
            setPadding((16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (8 * density).toInt())
        }
        val gridContainer = android.widget.GridLayout(this).apply { columnCount = 5 }
        root.addView(gridContainer)

        val chipSize = (48 * density).toInt()
        val margin = (6 * density).toInt()

        ColorPickerDialog.PRESETS.forEachIndexed { _, pair ->
            val (_, bgColor) = pair
            val textColor = ColorPickerDialog.contrastTextColor(bgColor)

            val chip = android.widget.FrameLayout(this).apply {
                val lp = android.widget.GridLayout.LayoutParams().apply {
                    width = chipSize
                    height = chipSize
                    setMargins(margin, margin, margin, margin)
                }
                layoutParams = lp
            }

            val bg = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(chipSize, chipSize)
            }
            bg.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
            chip.addView(bg)

            val label = TextView(this).apply {
                text = btnText
                setTextColor(textColor)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(chipSize, chipSize)
            }
            chip.addView(label)

            chip.setOnClickListener {
                prefs.edit()
                    .putInt(bgKey, bgColor)
                    .putInt(textKey, textColor)
                    .apply()
                if (isLight) refreshPreviewLight() else refreshPreviewDark()
                refreshColorChip(chipBgView, bgColor)
                refreshColorChip(chipTextView, textColor)
            }

            gridContainer.addView(chip)
        }

        AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
