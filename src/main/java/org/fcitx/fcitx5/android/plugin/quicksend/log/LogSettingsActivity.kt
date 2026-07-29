/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.log

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.quicksend.AppLog
import org.fcitx.fcitx5.android.plugin.quicksend.BuildConfig
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.databinding.ActivityLogSettingsBinding
import java.util.Locale

/**
 * 调试日志设置页（独立）：DEBUG 开关 / 当前级别 / 路径与大小 / 清空 / 分享 / 末尾预览。
 *
 * - 默认仅 WARN 及以上落盘；开启 DEBUG 后追加 INFO/DEBUG（同时写 logcat）。
 * - 从语音设置页右上角进入。
 */
class LogSettingsActivity : Activity() {

    private lateinit var binding: ActivityLogSettingsBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.debugSwitch.isChecked = AppLog.isDebugEnabled()
        binding.debugSwitch.setOnCheckedChangeListener { _, checked ->
            AppLog.setDebugEnabled(this, checked)
            refreshLevel()
        }

        binding.clearButton.setOnClickListener { confirmClear() }
        binding.shareButton.setOnClickListener { shareLog() }

        binding.logPath.text = AppLog.path(this)
        refreshLevel()
    }

    override fun onResume() {
        super.onResume()
        loadPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun refreshLevel() {
        val level = if (AppLog.isDebugEnabled()) {
            getString(R.string.log_level_debug)
        } else {
            getString(R.string.log_level_warn)
        }
        binding.currentLevel.text = getString(R.string.log_current_level, level)
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.log_clear)
            .setMessage(R.string.log_clear_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                AppLog.clear(this)
                Toast.makeText(this, R.string.log_cleared_toast, Toast.LENGTH_SHORT).show()
                loadPreview()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareLog() {
        val file = AppLog.file(this)
        if (file == null) {
            Toast.makeText(this, R.string.log_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_settings_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.log_share_title)))
    }

    private fun loadPreview() {
        scope.launch {
            val (sizeText, tailText) = withContext(Dispatchers.IO) {
                val f = AppLog.file(this@LogSettingsActivity)
                val size = formatSize(f?.length() ?: 0L)
                val tail = runCatching {
                    if (f == null || f.length() == 0L) {
                        getString(R.string.log_preview_empty)
                    } else {
                        f.readText().lineSequence().toList()
                            .takeLast(MAX_PREVIEW_LINES)
                            .joinToString("\n")
                    }
                }.getOrDefault(getString(R.string.log_preview_empty))
                size to tail
            }
            binding.logSize.text = getString(R.string.log_size_label, sizeText)
            binding.previewTitle.text = getString(R.string.log_preview_section, MAX_PREVIEW_LINES)
            binding.logPreview.text = tailText
        }
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024.0) {
            String.format(Locale.US, "%.1f KB", kb)
        } else {
            String.format(Locale.US, "%.2f MB", kb / 1024.0)
        }
    }

    private companion object {
        const val MAX_PREVIEW_LINES = 200
    }
}
