/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.plugin.quicksend.QuickSendPrefs
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.databinding.ActivityVoiceSettingsBinding
import org.fcitx.fcitx5.android.plugin.quicksend.log.LogSettingsActivity
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
    private val paramEdits = mutableMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        buildParamRows()
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

        binding.remoteSettingsButton.setOnClickListener {
            persistPrefs()
            startActivity(Intent(this, RemoteVoiceSettingsActivity::class.java))
        }

        binding.logSettingsButton.setOnClickListener {
            startActivity(Intent(this, LogSettingsActivity::class.java))
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

    override fun onPause() {
        super.onPause()
        persistPrefs()
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
        loadParamPrefs()
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
        val editor = prefs.edit()
            .putString(QuickSendPrefs.VOICE_MODEL_BASE_URL, baseUrl())
            .putString(QuickSendPrefs.VOICE_PROXY_URI, binding.proxyUri.text.toString().trim())
            .putString(QuickSendPrefs.VOICE_NAME_ENCODER, n.encoder)
            .putString(QuickSendPrefs.VOICE_NAME_DECODER, n.decoder)
            .putString(QuickSendPrefs.VOICE_NAME_JOINER, n.joiner)
            .putString(QuickSendPrefs.VOICE_NAME_TOKENS, n.tokens)
        saveParamPrefs(editor)
        editor.apply()
    }

    private fun saveParamPrefs(editor: android.content.SharedPreferences.Editor) {
        val paramPrefKeys = mapOf(
            "decodingMethod" to QuickSendPrefs.VOICE_DECODING_METHOD,
            "maxActivePaths" to QuickSendPrefs.VOICE_MAX_ACTIVE_PATHS,
            "blankPenalty" to QuickSendPrefs.VOICE_BLANK_PENALTY,
            "endpointSilence" to QuickSendPrefs.VOICE_ENDPOINT_SILENCE,
            "endpointMaxUtterance" to QuickSendPrefs.VOICE_ENDPOINT_MAX_UTTER,
            "numThreads" to QuickSendPrefs.VOICE_NUM_THREADS,
            "provider" to QuickSendPrefs.VOICE_PROVIDER
        )
        for ((key, prefKey) in paramPrefKeys) {
            paramEdits[key]?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                editor.putString(prefKey, it)
            } ?: editor.remove(prefKey)
        }
    }

    private fun loadParamPrefs() {
        val paramDefaults = mapOf(
            "decodingMethod" to RecognitionConfig.DEFAULT_DECODING_METHOD,
            "maxActivePaths" to RecognitionConfig.DEFAULT_MAX_ACTIVE_PATHS.toString(),
            "blankPenalty" to RecognitionConfig.DEFAULT_BLANK_PENALTY.toString(),
            "endpointSilence" to RecognitionConfig.DEFAULT_ENDPOINT_SILENCE.toString(),
            "endpointMaxUtterance" to RecognitionConfig.DEFAULT_ENDPOINT_MAX_UTTERANCE.toString(),
            "numThreads" to RecognitionConfig.DEFAULT_NUM_THREADS.toString(),
            "provider" to RecognitionConfig.DEFAULT_PROVIDER
        )
        val paramPrefKeys = mapOf(
            "decodingMethod" to QuickSendPrefs.VOICE_DECODING_METHOD,
            "maxActivePaths" to QuickSendPrefs.VOICE_MAX_ACTIVE_PATHS,
            "blankPenalty" to QuickSendPrefs.VOICE_BLANK_PENALTY,
            "endpointSilence" to QuickSendPrefs.VOICE_ENDPOINT_SILENCE,
            "endpointMaxUtterance" to QuickSendPrefs.VOICE_ENDPOINT_MAX_UTTER,
            "numThreads" to QuickSendPrefs.VOICE_NUM_THREADS,
            "provider" to QuickSendPrefs.VOICE_PROVIDER
        )
        for ((key, prefKey) in paramPrefKeys) {
            paramEdits[key]?.setText(prefs.getString(prefKey, null) ?: paramDefaults[key])
        }
    }

    private fun buildParamRows() {
        val container = binding.recognitionParams
        val paramItemDefs = listOf(
            Triple("decodingMethod", R.string.voice_param_title_decoding, R.string.voice_param_desc_decoding),
            Triple("maxActivePaths", R.string.voice_param_title_paths, R.string.voice_param_desc_paths),
            Triple("blankPenalty", R.string.voice_param_title_blank, R.string.voice_param_desc_blank),
            Triple("endpointSilence", R.string.voice_param_title_silence, R.string.voice_param_desc_silence),
            Triple("endpointMaxUtterance", R.string.voice_param_title_maxutter, R.string.voice_param_desc_maxutter),
            Triple("numThreads", R.string.voice_param_title_threads, R.string.voice_param_desc_threads),
            Triple("provider", R.string.voice_param_title_provider, R.string.voice_param_desc_provider)
        )
        val density = resources.displayMetrics.density
        for ((key, titleRes, descRes) in paramItemDefs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
            }
            val label = TextView(this).apply {
                text = getString(titleRes)
                setTextColor(ContextCompat.getColor(this@VoiceSettingsActivity, R.color.qs_text_primary))
                textSize = 13f
            }
            row.addView(label)
            val help = TextView(this).apply {
                text = "?"
                setTextColor(ContextCompat.getColor(this@VoiceSettingsActivity, R.color.qs_accent_overlay_bg))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                setOnClickListener {
                    val helpInfo = recognitionParamHelps.firstOrNull { it.key == key }
                    AlertDialog.Builder(this@VoiceSettingsActivity)
                        .setTitle(getString(titleRes))
                        .setMessage(buildString {
                            append(getString(descRes)).append("\n\n")
                            append(getString(R.string.voice_param_default, helpInfo?.default ?: "-")).append("\n")
                            append(getString(R.string.voice_param_recommended, helpInfo?.recommended ?: "-"))
                        })
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            row.addView(help)
            val edit = EditText(this).apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = (8 * density).toInt()
                layoutParams = lp
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                maxLines = 1
                textSize = 13f
            }
            row.addView(edit)
            container.addView(row)
            paramEdits[key] = edit
        }
    }

    private fun resetToDefaults() {
        val editor = prefs.edit()
            .remove(QuickSendPrefs.VOICE_MODEL_BASE_URL)
            .remove(QuickSendPrefs.VOICE_NAME_ENCODER)
            .remove(QuickSendPrefs.VOICE_NAME_DECODER)
            .remove(QuickSendPrefs.VOICE_NAME_JOINER)
            .remove(QuickSendPrefs.VOICE_NAME_TOKENS)
            .remove(QuickSendPrefs.VOICE_DECODING_METHOD)
            .remove(QuickSendPrefs.VOICE_MAX_ACTIVE_PATHS)
            .remove(QuickSendPrefs.VOICE_BLANK_PENALTY)
            .remove(QuickSendPrefs.VOICE_ENDPOINT_SILENCE)
            .remove(QuickSendPrefs.VOICE_ENDPOINT_MAX_UTTER)
            .remove(QuickSendPrefs.VOICE_NUM_THREADS)
            .remove(QuickSendPrefs.VOICE_PROVIDER)
            editor.apply()
        binding.modelUrl.setText(VoiceModelManager.DEFAULT_BASE_URL)
        binding.namesEncoder.setText(SherpaModelNames.DEFAULT_ENCODER)
        binding.namesDecoder.setText(SherpaModelNames.DEFAULT_DECODER)
        binding.namesJoiner.setText(SherpaModelNames.DEFAULT_JOINER)
        binding.namesTokens.setText(SherpaModelNames.DEFAULT_TOKENS)
        loadParamPrefs()
        Toast.makeText(this, "已恢复默认配置", Toast.LENGTH_SHORT).show()
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
