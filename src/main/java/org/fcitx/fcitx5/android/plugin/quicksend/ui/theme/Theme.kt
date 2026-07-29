/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 应用级 Material3 主题：随系统深色模式切换 light/dark baseline 配色。
 *
 * 全应用所有 Compose 页面共用，保证亮/暗模式一致。悬浮窗模块
 * （QuickSendOverlayService / VoiceOverlayService）仍为 View 实现，不经过此主题。
 */
@Composable
fun QuickSendTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
