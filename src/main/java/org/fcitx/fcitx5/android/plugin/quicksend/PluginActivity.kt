package org.fcitx.fcitx5.android.plugin.quicksend

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry
import org.fcitx.fcitx5.android.plugin.quicksend.databinding.ActivityPluginBinding
import org.fcitx.fcitx5.android.plugin.quicksend.ui.ColorPickerDialog
import org.fcitx.fcitx5.android.plugin.quicksend.ui.EditEntryDialog
import org.fcitx.fcitx5.android.plugin.quicksend.ui.QuickSendAdapter
import org.fcitx.fcitx5.android.plugin.quicksend.voice.VoiceSettingsActivity

/**
 * 插件设置入口（响应 `${fcitxAppId}.plugin.MANIFEST`）。
 *
 * 展示全量条目（按使用次数倒序），点击条目立即发送，编辑/删除条目，添加新条目。
 * 底部开关启用悬浮发送按钮（需 SYSTEM_ALERT_WINDOW 权限）。
 */
class PluginActivity : Activity() {

    private lateinit var binding: ActivityPluginBinding
    private lateinit var adapter: QuickSendAdapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences(QuickSendPrefs.FILE, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPluginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = QuickSendAdapter(
            onSend = { entry -> sendEntry(entry) },
            onEdit = { entry -> EditEntryDialog.show(this, entry) },
            onDelete = { entry -> confirmDelete(entry) }
        )
        binding.entriesList.layoutManager = LinearLayoutManager(this)
        binding.entriesList.adapter = adapter

        binding.backButton.setOnClickListener { finish() }

        // 标题栏「更多」：语音输入设置等子项入口
        binding.moreButton.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menuInflater.inflate(R.menu.plugin_more, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_voice_settings -> {
                        startActivity(Intent(this, VoiceSettingsActivity::class.java))
                        true
                    }
                }
                false
            }
            popup.show()
        }

        binding.addButton.setOnClickListener { EditEntryDialog.show(this, null) }

        // 先恢复开关状态再挂监听，避免初始化触发
        binding.overlaySwitch.isChecked = prefs.getBoolean(QuickSendPrefs.OVERLAY_ENABLED, false)
        binding.overlaySwitch.setOnCheckedChangeListener { button, checked ->
            if (checked && !Settings.canDrawOverlays(this)) {
                button.isChecked = false
                Toast.makeText(this, R.string.overlay_permission_rationale, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else {
                prefs.edit().putBoolean(QuickSendPrefs.OVERLAY_ENABLED, checked).apply()
                toggleOverlay(checked)
                Toast.makeText(
                    this,
                    if (checked) R.string.overlay_enabled_hint else R.string.overlay_disabled_hint,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 悬浮按钮文字：自定义悬浮按钮上显示的字符（单个字符观感最佳），输入即时保存。
        binding.buttonTextInput.setText(
            prefs.getString(QuickSendPrefs.BUTTON_TEXT, QuickSendPrefs.BUTTON_TEXT_DEFAULT)
        )
        binding.buttonTextInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty().ifBlank { QuickSendPrefs.BUTTON_TEXT_DEFAULT }
                prefs.edit().putString(QuickSendPrefs.BUTTON_TEXT, text).apply()
            }
        })

        initColorSection()

        scope.launch {
            QuickSendManager.items.collect { list -> updateUi(list) }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统授权页返回，同步开关
        if (prefs.getBoolean(QuickSendPrefs.OVERLAY_ENABLED, false) && Settings.canDrawOverlays(this)) {
            if (!binding.overlaySwitch.isChecked) binding.overlaySwitch.isChecked = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun toggleOverlay(enable: Boolean) {
        val intent = Intent(this, QuickSendOverlayService::class.java)
        if (enable) {
            if (!Settings.canDrawOverlays(this)) return
            startService(intent)
        } else {
            intent.action = QuickSendOverlayService.ACTION_HIDE
            startService(intent)
        }
    }

    private fun updateUi(list: List<QuickSendEntry>) {
        adapter.submit(list)
        binding.emptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun sendEntry(entry: QuickSendEntry) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { QuickSendExecutor.execute(entry) }
            if (!ok) {
                Toast.makeText(this@PluginActivity, R.string.send_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(entry: QuickSendEntry) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { QuickSendManager.delete(entry.id) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun initColorSection() {
        val btnText = prefs.getString(QuickSendPrefs.BUTTON_TEXT, QuickSendPrefs.BUTTON_TEXT_DEFAULT) ?: "发"

        setupColorChip(binding.chipBgLight, QuickSendPrefs.OVERLAY_BG_COLOR, QuickSendPrefs.DEFAULT_BG_COLOR) { color ->
            prefs.edit().putInt(QuickSendPrefs.OVERLAY_BG_COLOR, color).apply()
            refreshPreviewLight(btnText)
            refreshColorChip(binding.chipBgLight, color)
        }
        setupColorChip(binding.chipBgDark, QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, QuickSendPrefs.DEFAULT_BG_COLOR) { color ->
            prefs.edit().putInt(QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, color).apply()
            refreshPreviewDark(btnText)
            refreshColorChip(binding.chipBgDark, color)
        }
        setupColorChip(binding.chipTextLight, QuickSendPrefs.OVERLAY_TEXT_COLOR, QuickSendPrefs.DEFAULT_TEXT_COLOR) { color ->
            prefs.edit().putInt(QuickSendPrefs.OVERLAY_TEXT_COLOR, color).apply()
            refreshPreviewLight(btnText)
            refreshColorChip(binding.chipTextLight, color)
        }
        setupColorChip(binding.chipTextDark, QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, QuickSendPrefs.DEFAULT_TEXT_COLOR) { color ->
            prefs.edit().putInt(QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, color).apply()
            refreshPreviewDark(btnText)
            refreshColorChip(binding.chipTextDark, color)
        }

        binding.presetsButton.setOnClickListener { showPresetsDialog(btnText) }

        refreshPreviewLight(btnText)
        refreshPreviewDark(btnText)
    }

    private fun setupColorChip(container: View, key: String, defaultColor: Int, onPicked: (Int) -> Unit) {
        val color = prefs.getInt(key, defaultColor)
        val chipView = View(this).apply {
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = ViewGroup.LayoutParams(size, size)
        }
        (container as ViewGroup).removeAllViews()
        container.addView(chipView)
        refreshColorChip(chipView, color)
        container.setOnClickListener {
            ColorPickerDialog(this, color) { newColor -> onPicked(newColor) }.show()
        }
    }

    private fun refreshColorChip(chip: View, color: Int) {
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(1, Color.argb(80, 128, 128, 128))
        }
    }

    private fun refreshPreviewLight(btnText: String) {
        val bg = prefs.getInt(QuickSendPrefs.OVERLAY_BG_COLOR, QuickSendPrefs.DEFAULT_BG_COLOR)
        val text = prefs.getInt(QuickSendPrefs.OVERLAY_TEXT_COLOR, QuickSendPrefs.DEFAULT_TEXT_COLOR)
        setPreview(binding.btnPreviewLight, bg, text, btnText)
    }

    private fun refreshPreviewDark(btnText: String) {
        val bg = prefs.getInt(QuickSendPrefs.OVERLAY_BG_COLOR_NIGHT, QuickSendPrefs.DEFAULT_BG_COLOR)
        val text = prefs.getInt(QuickSendPrefs.OVERLAY_TEXT_COLOR_NIGHT, QuickSendPrefs.DEFAULT_TEXT_COLOR)
        setPreview(binding.btnPreviewDark, bg, text, btnText)
    }

    private fun setPreview(container: ViewGroup, bgColor: Int, textColor: Int, text: String) {
        container.removeAllViews()
        val bg = View(this).apply {
            val size = (56 * resources.displayMetrics.density).toInt()
            layoutParams = ViewGroup.LayoutParams(size, size)
        }
        bg.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
            setSize((56 * resources.displayMetrics.density).toInt(), (56 * resources.displayMetrics.density).toInt())
        }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        container.addView(bg)
        container.addView(tv)
    }

    private fun showPresetsDialog(btnText: String) {
        val root = android.widget.ScrollView(this).apply {
            setPadding((16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt())
        }
        val gridContainer = android.widget.GridLayout(this).apply {
            columnCount = 5
        }
        root.addView(gridContainer)

        val chipSize = (48 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()

        ColorPickerDialog.PRESETS.forEachIndexed { index, pair ->
            val (name, bgColor) = pair
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
                refreshPreviewLight(btnText)
                refreshPreviewDark(btnText)
                refreshColorChip(
                    (binding.chipBgLight as ViewGroup).getChildAt(0), bgColor)
                refreshColorChip(
                    (binding.chipBgDark as ViewGroup).getChildAt(0), bgColor)
                refreshColorChip(
                    (binding.chipTextLight as ViewGroup).getChildAt(0), textColor)
                refreshColorChip(
                    (binding.chipTextDark as ViewGroup).getChildAt(0), textColor)
            }

            gridContainer.addView(chip)
        }

        AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
