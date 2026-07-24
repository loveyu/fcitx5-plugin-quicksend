/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.plugin.quicksend.QuickSendPrefs
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.databinding.ActivityVoiceSettingsBinding
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.ProxyConfig
import org.fcitx.fcitx5.android.plugin.quicksend.voice.net.ProxyType
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
        binding.proxySwitch.isChecked = prefs.getBoolean(QuickSendPrefs.VOICE_PROXY_ENABLED, false)
        val type = prefs.getString(QuickSendPrefs.VOICE_PROXY_TYPE, "HTTP")
        binding.proxyType.setSelection(if (type == "SOCKS") 1 else 0)
        binding.proxyHost.setText(prefs.getString(QuickSendPrefs.VOICE_PROXY_HOST, "127.0.0.1"))
        binding.proxyPort.setText(prefs.getInt(QuickSendPrefs.VOICE_PROXY_PORT, 7890).toString())
        binding.proxyUser.setText(prefs.getString(QuickSendPrefs.VOICE_PROXY_USER, ""))
        binding.proxyPass.setText(prefs.getString(QuickSendPrefs.VOICE_PROXY_PASS, ""))
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

    private fun baseUrl(): String =
        binding.modelUrl.text.toString().trim().ifBlank { VoiceModelManager.DEFAULT_BASE_URL }

    private fun names(): SherpaModelNames = SherpaModelNames(
        encoder = binding.namesEncoder.text.toString().trim().ifBlank { SherpaModelNames.DEFAULT_ENCODER },
        decoder = binding.namesDecoder.text.toString().trim().ifBlank { SherpaModelNames.DEFAULT_DECODER },
        joiner = binding.namesJoiner.text.toString().trim().ifBlank { SherpaModelNames.DEFAULT_JOINER },
        tokens = binding.namesTokens.text.toString().trim().ifBlank { SherpaModelNames.DEFAULT_TOKENS }
    )

    private fun proxy(): ProxyConfig {
        val typeStr = binding.proxyType.selectedItem as? String ?: "HTTP"
        return ProxyConfig(
            enabled = binding.proxySwitch.isChecked,
            type = if (typeStr == "SOCKS") ProxyType.SOCKS else ProxyType.HTTP,
            host = binding.proxyHost.text.toString().trim(),
            port = binding.proxyPort.text.toString().trim().toIntOrNull() ?: 7890,
            user = binding.proxyUser.text.toString().trim(),
            pass = binding.proxyPass.text.toString()
        )
    }

    private fun persistPrefs() {
        val p = proxy()
        val n = names()
        prefs.edit()
            .putString(QuickSendPrefs.VOICE_MODEL_BASE_URL, baseUrl())
            .putBoolean(QuickSendPrefs.VOICE_PROXY_ENABLED, p.enabled)
            .putString(QuickSendPrefs.VOICE_PROXY_TYPE, if (p.type == ProxyType.SOCKS) "SOCKS" else "HTTP")
            .putString(QuickSendPrefs.VOICE_PROXY_HOST, p.host)
            .putInt(QuickSendPrefs.VOICE_PROXY_PORT, p.port)
            .putString(QuickSendPrefs.VOICE_PROXY_USER, p.user)
            .putString(QuickSendPrefs.VOICE_PROXY_PASS, p.pass)
            .putString(QuickSendPrefs.VOICE_NAME_ENCODER, n.encoder)
            .putString(QuickSendPrefs.VOICE_NAME_DECODER, n.decoder)
            .putString(QuickSendPrefs.VOICE_NAME_JOINER, n.joiner)
            .putString(QuickSendPrefs.VOICE_NAME_TOKENS, n.tokens)
            .apply()
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
                binding.modelStatus.text = getString(R.string.voice_downloading, state.percent)
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
