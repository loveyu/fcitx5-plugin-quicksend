package org.fcitx.fcitx5.android.plugin.quicksend

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry
import org.fcitx.fcitx5.android.plugin.quicksend.databinding.ActivityPluginBinding
import org.fcitx.fcitx5.android.plugin.quicksend.ui.EditEntryDialog
import org.fcitx.fcitx5.android.plugin.quicksend.ui.QuickSendAdapter

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
                // 留空时落回默认值，保证悬浮按钮总有文字可显示
                val text = s?.toString().orEmpty().ifBlank { QuickSendPrefs.BUTTON_TEXT_DEFAULT }
                prefs.edit().putString(QuickSendPrefs.BUTTON_TEXT, text).apply()
            }
        })

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
}
