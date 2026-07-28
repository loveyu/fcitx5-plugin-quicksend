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

        val btnText = prefs.getString(QuickSendPrefs.BUTTON_TEXT, QuickSendPrefs.BUTTON_TEXT_DEFAULT) ?: "发"

        setupColorChip(binding.chipBgLight, QuickSendPrefs.OVERLAY_BG_COLOR, QuickSendPrefs.DEFAULT_BG_COLOR) { color ->
            prefs.edit().putInt(QuickSendPrefs.OVERLAY_BG_COLOR, color).apply()
            refreshPreviewLight()
        }
        setupColorChip(binding.chipBgDark, QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, QuickSendPrefs.DEFAULT_BG_COLOR) { color ->
            prefs.edit().putInt(QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, color).apply()
            refreshPreviewDark()
        }
        setupColorChip(binding.chipTextLight, QuickSendPrefs.OVERLAY_TEXT_COLOR, QuickSendPrefs.DEFAULT_TEXT_COLOR) { color ->
            prefs.edit().putInt(QuickSendPrefs.OVERLAY_TEXT_COLOR, color).apply()
            refreshPreviewLight()
        }
        setupColorChip(binding.chipTextDark, QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, QuickSendPrefs.DEFAULT_TEXT_COLOR) { color ->
            prefs.edit().putInt(QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, color).apply()
            refreshPreviewDark()
        }

        binding.presetsButton.setOnClickListener { showPresetsDialog() }

        refreshPreviewLight()
        refreshPreviewDark()
    }

    private fun setupColorChip(container: View, key: String, defaultColor: Int, onPicked: (Int) -> Unit) {
        refreshColorChip(container, prefs.getInt(key, defaultColor))
        container.setOnClickListener {
            val cur = prefs.getInt(key, defaultColor)
            ColorPickerDialog(this, cur) { newColor ->
                refreshColorChip(container, newColor)
                onPicked(newColor)
            }.show()
        }
    }

    private fun refreshColorChip(view: View, color: Int) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(1, Color.argb(80, 128, 128, 128))
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
        val density = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = btnText
            setTextColor(textColor)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
        }
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
            setSize((48 * density).toInt(), (48 * density).toInt())
        }
        container.addView(
            tv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun showPresetsDialog() {
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
                    .putInt(QuickSendPrefs.OVERLAY_BG_COLOR, bgColor)
                    .putInt(QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, bgColor)
                    .putInt(QuickSendPrefs.OVERLAY_TEXT_COLOR, textColor)
                    .putInt(QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, textColor)
                    .apply()
                refreshAllPreviews()
                refreshColorChip(binding.chipBgLight, bgColor)
                refreshColorChip(binding.chipBgDark, bgColor)
                refreshColorChip(binding.chipTextLight, textColor)
                refreshColorChip(binding.chipTextDark, textColor)
            }

            gridContainer.addView(chip)
        }

        AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
