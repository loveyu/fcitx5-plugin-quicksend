/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.plugin.quicksend.BuildConfig
import org.fcitx.fcitx5.android.plugin.quicksend.QuickSendPrefs
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.databinding.ActivityVoiceSettingsBinding
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.ProxyConfig
import org.fcitx.fcitx5.android.plugin.quicksend.voice.sherpa.SherpaModelNames

/**
 * 语音输入设置页：模型下载（自定义 HTTP 地址 + 代理）+ 高级文件名。
 * 进入时申请 RECORD_AUDIO；状态来自 [VoiceModelManager]。
 */
class VoiceSettingsActivity : Activity() {

    private lateinit var binding: ActivityVoiceSettingsBinding
    private val prefs by lazy { getSharedPreferences(QuickSendPrefs.FILE, MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        loadPrefs()

        binding.downloadButton.setOnClickListener {
            persistPrefs()
            VoiceModelManager.download(this, baseUrl(), names(), proxy())
        }
        binding.deleteButton.setOnClickListener {
            VoiceModelManager.delete(this, names())
            updateState(VoiceModelManager.state.value)
        }
        binding.resetButton.setOnClickListener { resetToDefaults() }

        // 调试日志：展示路径 + 分享（便于用户复制上报）
        binding.logPath.text = VoiceLog.path(this)
        binding.shareLogButton.setOnClickListener { shareLog() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_AUDIO)
        }

        scope.launch { VoiceModelManager.state.collect { updateState(it) } }
    }

    override fun onResume() {
        super.onResume()
        VoiceModelManager.refresh(this, names())
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun loadPrefs() {
        binding.modelUrl.setText(
            prefs.getString(QuickSendPrefs.VOICE_MODEL_BASE_URL, VoiceModelManager.DEFAULT_BASE_URL)
        )
        binding.proxyUri.setText(loadProxyUri())
        binding.namesEncoder.setText(
            prefs.getString(QuickSendPrefs.VOICE_NAME_ENCODER, SherpaModelNames.DEFAULT_ENCODER)
        )
        binding.namesDecoder.setText(
            prefs.getString(QuickSendPrefs.VOICE_NAME_DECODER, SherpaModelNames.DEFAULT_DECODER)
        )
        binding.namesJoiner.setText(
            prefs.getString(QuickSendPrefs.VOICE_NAME_JOINER, SherpaModelNames.DEFAULT_JOINER)
        )
        binding.namesTokens.setText(
            prefs.getString(QuickSendPrefs.VOICE_NAME_TOKENS, SherpaModelNames.DEFAULT_TOKENS)
        )
    }

    /** 读取代理 URI；首次升级时把旧版多字段代理迁移成一条 URI。 */
    private fun loadProxyUri(): String {
        prefs.getString(QuickSendPrefs.VOICE_PROXY_URI, null)?.let { return it }
        if (!prefs.getBoolean(QuickSendPrefs.VOICE_PROXY_ENABLED_LEGACY, false)) return ""
        val scheme = if (prefs.getString(QuickSendPrefs.VOICE_PROXY_TYPE_LEGACY, "HTTP") == "SOCKS") "socks5" else "http"
        val host = prefs.getString(QuickSendPrefs.VOICE_PROXY_HOST_LEGACY, "") ?: ""
        val port = prefs.getInt(QuickSendPrefs.VOICE_PROXY_PORT_LEGACY, 7890)
        val user = prefs.getString(QuickSendPrefs.VOICE_PROXY_USER_LEGACY, "") ?: ""
        val pass = prefs.getString(QuickSendPrefs.VOICE_PROXY_PASS_LEGACY, "") ?: ""
        val uri = buildString {
            append(scheme).append("://")
            if (user.isNotEmpty()) {
                append(user)
                if (pass.isNotEmpty()) append(":").append(pass)
                append("@")
            }
            append(host).append(":").append(port)
        }
        prefs.edit().putString(QuickSendPrefs.VOICE_PROXY_URI, uri).apply()
        return uri
    }

    private fun baseUrl(): String =
        binding.modelUrl.text.toString().trim().ifBlank { VoiceModelManager.DEFAULT_BASE_URL }

    private fun names(): SherpaModelNames = SherpaModelNames(
        encoder = binding.namesEncoder.text.toString().trim().ifBlank { SherpaModelNames.DEFAULT_ENCODER },
        decoder = binding.namesDecoder.text.toString().trim().ifBlank { SherpaModelNames.DEFAULT_DECODER },
        joiner = binding.namesJoiner.text.toString().trim().ifBlank { SherpaModelNames.DEFAULT_JOINER },
        tokens = binding.namesTokens.text.toString().trim().ifBlank { SherpaModelNames.DEFAULT_TOKENS }
    )

    private fun proxy(): ProxyConfig =
        ProxyConfig.fromUri(binding.proxyUri.text.toString().trim())

    private fun persistPrefs() {
        val n = names()
        prefs.edit()
            .putString(QuickSendPrefs.VOICE_MODEL_BASE_URL, baseUrl())
            .putString(QuickSendPrefs.VOICE_PROXY_URI, binding.proxyUri.text.toString().trim())
            .putString(QuickSendPrefs.VOICE_NAME_ENCODER, n.encoder)
            .putString(QuickSendPrefs.VOICE_NAME_DECODER, n.decoder)
            .putString(QuickSendPrefs.VOICE_NAME_JOINER, n.joiner)
            .putString(QuickSendPrefs.VOICE_NAME_TOKENS, n.tokens)
            .apply()
    }

    private fun resetToDefaults() {
        prefs.edit()
            .remove(QuickSendPrefs.VOICE_MODEL_BASE_URL)
            .remove(QuickSendPrefs.VOICE_NAME_ENCODER)
            .remove(QuickSendPrefs.VOICE_NAME_DECODER)
            .remove(QuickSendPrefs.VOICE_NAME_JOINER)
            .remove(QuickSendPrefs.VOICE_NAME_TOKENS)
            .apply()
        binding.modelUrl.setText(VoiceModelManager.DEFAULT_BASE_URL)
        binding.namesEncoder.setText(SherpaModelNames.DEFAULT_ENCODER)
        binding.namesDecoder.setText(SherpaModelNames.DEFAULT_DECODER)
        binding.namesJoiner.setText(SherpaModelNames.DEFAULT_JOINER)
        binding.namesTokens.setText(SherpaModelNames.DEFAULT_TOKENS)
        Toast.makeText(this, "已恢复默认配置", Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val file = VoiceLog.file(this)
        if (file == null) {
            Toast.makeText(this, R.string.voice_log_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.voice_log_section))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.voice_log_share_title)))
    }

    private fun updateState(state: DownloadState) {
        when (state) {
            DownloadState.Idle -> {
                binding.modelStatus.text = getString(R.string.voice_model_status_idle)
                binding.modelProgress.visibility = View.GONE
                binding.downloadButton.text = getString(R.string.voice_download)
                binding.downloadButton.isEnabled = true
            }
            is DownloadState.Downloading -> {
                val label = state.label.ifEmpty { "下载中 ${state.percent}%" }
                binding.modelStatus.text = label
                binding.modelProgress.visibility = View.VISIBLE
                binding.modelProgress.progress = if (state.percent < 0) 0 else state.percent
                binding.downloadButton.isEnabled = false
            }
            DownloadState.Ready -> {
                binding.modelStatus.text = getString(R.string.voice_model_status_ready)
                binding.modelProgress.visibility = View.GONE
                binding.downloadButton.text = getString(R.string.voice_redownload)
                binding.downloadButton.isEnabled = true
            }
            is DownloadState.Failed -> {
                binding.modelStatus.text = getString(R.string.voice_download_failed, state.message)
                binding.modelProgress.visibility = View.GONE
                binding.downloadButton.text = getString(R.string.voice_download)
                binding.downloadButton.isEnabled = true
            }
        }
    }

    private companion object {
        const val RC_AUDIO = 0x7e02
    }
}
