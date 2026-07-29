/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import org.fcitx.fcitx5.android.plugin.quicksend.QuickSendPrefs
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.databinding.ActivityRemoteVoiceSettingsBinding

class RemoteVoiceSettingsActivity : Activity() {

    private lateinit var binding: ActivityRemoteVoiceSettingsBinding
    private val prefs by lazy { getSharedPreferences(QuickSendPrefs.FILE, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteVoiceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        val remoteEnabled = prefs.getBoolean(QuickSendPrefs.VOICE_REMOTE_ENABLED, false)
        binding.remoteEnabledCheck.isChecked = remoteEnabled
        binding.remoteUrl.setText(prefs.getString(QuickSendPrefs.VOICE_REMOTE_URL, "") ?: "")
        binding.remoteToken.setText(prefs.getString(QuickSendPrefs.VOICE_REMOTE_TOKEN, "") ?: "")
        val vis = if (remoteEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.remoteUrl.visibility = vis
        binding.remoteToken.visibility = vis

        binding.remoteEnabledCheck.setOnCheckedChangeListener { _, checked ->
            val v = if (checked) android.view.View.VISIBLE else android.view.View.GONE
            binding.remoteUrl.visibility = v
            binding.remoteToken.visibility = v
            prefs.edit().putBoolean(QuickSendPrefs.VOICE_REMOTE_ENABLED, checked).apply()
        }

        val remoteTextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit()
                    .putString(QuickSendPrefs.VOICE_REMOTE_URL, binding.remoteUrl.text.toString().trim())
                    .putString(QuickSendPrefs.VOICE_REMOTE_TOKEN, binding.remoteToken.text.toString().trim())
                    .apply()
            }
        }
        binding.remoteUrl.addTextChangedListener(remoteTextWatcher)
        binding.remoteToken.addTextChangedListener(remoteTextWatcher)
    }
}
