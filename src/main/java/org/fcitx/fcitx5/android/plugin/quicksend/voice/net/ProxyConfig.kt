/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.net

/**
 * 下载/网络代理配置。默认预填本机常用代理端口（127.0.0.1:7890），
 * enabled=false；用户在设置页启用后即可直接用。
 */
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "127.0.0.1",
    val port: Int = 7890,
    val user: String = "",
    val pass: String = ""
)

enum class ProxyType { HTTP, SOCKS }
