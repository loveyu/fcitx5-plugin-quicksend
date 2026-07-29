/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.tencent

import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯实时语音识别 V2 的客户端签名（纯 JVM，无 Android 依赖，便于单测）。
 * 文档：https://cloud.tencent.com/document/api/1093/131127
 *
 * - 签名串 = `asr.cloud.tencent.com/asr/v2/<appid>?<参数按 key 字典序 k=v&k=v>`（不含 wss://、不含 signature）
 * - signature = Base64(HMAC_SHA1(签名串, SecretKey))，再 URL 编码后追加到 URL
 *
 * 自带标准 Base64 编码（不依赖 android.util.Base64，避免 minSdk 24 与 java.util.Base64 需 API 26 的冲突）。
 */
internal object TencentV2Signing {

    const val HOST_PATH_PREFIX = "asr.cloud.tencent.com/asr/v2/"

    /** 拼接签名串（参数需已按 key 字典序排列，且不含 signature）。 */
    fun buildSignString(appId: String, sortedParams: List<Pair<String, String>>): String {
        val query = sortedParams.joinToString("&") { (k, v) -> "$k=$v" }
        return "$HOST_PATH_PREFIX$appId?$query"
    }

    /** 对签名串计算 HMAC-SHA1 并做标准 Base64 编码（无换行）。 */
    fun signature(secretKey: String, signString: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return base64(mac.doFinal(signString.toByteArray(Charsets.UTF_8)))
    }

    /** 拼最终 wss URL：参数值 URL 编码，signature 也 URL 编码。 */
    fun buildUrl(appId: String, sortedParams: List<Pair<String, String>>, signature: String): String {
        val query = sortedParams.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "wss://$HOST_PATH_PREFIX$appId?$query&signature=${URLEncoder.encode(signature, "UTF-8")}"
    }

    /** RFC 4648 标准 Base64 编码（无换行）。 */
    private fun base64(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(table[b0 ushr 2])
            sb.append(table[((b0 and 0x03) shl 4) or if (b1 < 0) 0 else b1 ushr 4])
            sb.append(if (b1 < 0) '=' else table[((b1 and 0x0F) shl 2) or if (b2 < 0) 0 else b2 ushr 6])
            sb.append(if (b2 < 0) '=' else table[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }
}
