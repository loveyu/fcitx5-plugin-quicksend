/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.net

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * OkHttp 客户端工厂。模型下载与后续在线 Provider / AI 润色统一复用。
 * 代理随 [ProxyConfig] 构建；OkHttp 自动跟随重定向并处理代理鉴权。
 */
/** 把代理配置应用到 OkHttp Builder（HTTP / SOCKS5，含可选鉴权）。模型下载与远端识别器复用。 */
fun OkHttpClient.Builder.applyProxy(proxy: ProxyConfig): OkHttpClient.Builder {
    if (proxy.enabled && proxy.host.isNotBlank()) {
        val type = if (proxy.type == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
        proxy(Proxy(type, InetSocketAddress(proxy.host, proxy.port)))
        if (proxy.user.isNotBlank()) {
            val credential = Credentials.basic(proxy.user, proxy.pass)
            proxyAuthenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
    }
    return this
}

object VoiceHttp {

    fun client(proxy: ProxyConfig): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .applyProxy(proxy)
            .build()
}
