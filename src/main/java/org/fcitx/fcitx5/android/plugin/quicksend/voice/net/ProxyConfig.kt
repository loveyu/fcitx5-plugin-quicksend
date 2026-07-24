/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.net

import java.net.URI

/**
 * 下载/网络代理配置。
 *
 * 设置页以单个 URI 字符串（DSN/连接串风格）录入，由 [fromUri] 解析为该结构供 [VoiceHttp] 使用。
 *
 * 支持 scheme：`http`/`https` → HTTP 代理；`socks`/`socks4`/`socks5` → SOCKS 代理。
 * 形如：`http://127.0.0.1:7890`、`socks5://user:pass@host:1080`。空串或不合法 → [NONE]（不使用代理）。
 */
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val pass: String = ""
) {
    companion object {
        val NONE = ProxyConfig()

        /** 解析代理 URI；为空或非法返回 [NONE]。 */
        fun fromUri(uri: String): ProxyConfig {
            val s = uri.trim()
            if (s.isEmpty()) return NONE
            return try {
                val u = URI(s)
                val scheme = u.scheme?.lowercase().orEmpty()
                if (scheme.isEmpty()) return NONE
                val type = if (scheme.startsWith("socks")) ProxyType.SOCKS else ProxyType.HTTP
                val host = u.host.orEmpty()
                if (host.isEmpty()) return NONE
                val port = u.port.takeIf { it > 0 }
                    ?: if (type == ProxyType.SOCKS) 1080 else 8080
                val (user, pass) = u.userInfo?.let { info ->
                    val idx = info.indexOf(':')
                    if (idx >= 0) info.substring(0, idx) to info.substring(idx + 1)
                    else info to ""
                } ?: "" to ""
                ProxyConfig(enabled = true, type = type, host = host, port = port, user = user, pass = pass)
            } catch (_: Exception) {
                NONE
            }
        }
    }
}

enum class ProxyType { HTTP, SOCKS }
