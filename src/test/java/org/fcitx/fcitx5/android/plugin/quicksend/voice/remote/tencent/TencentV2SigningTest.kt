/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.tencent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯 ASR V2 客户端签名的纯 JVM 单测（验证签名串格式 + HMAC-SHA1/Base64 与 JDK 标准一致）。
 */
class TencentV2SigningTest {

    @Test
    fun signString_format_isCorrect() {
        // 参数需已按 key 字典序传入（识别器内 sortedBy 后调用）
        val s = TencentV2Signing.buildSignString(
            "1250000",
            listOf(
                "engine_model_type" to "16k_zh_en_2.0",
                "secretid" to "AKIDx",
                "timestamp" to "1000",
                "voice_id" to "abc"
            )
        )
        // 不含 wss://、不含 signature；appid 在路径；参数 k=v 以 & 连接
        assertEquals(
            "asr.cloud.tencent.com/asr/v2/1250000?engine_model_type=16k_zh_en_2.0&secretid=AKIDx&timestamp=1000&voice_id=abc",
            s
        )
    }

    @Test
    fun signature_matchesJdkHmacSha1Base64() {
        val key = "mySecretKey"
        val data = TencentV2Signing.buildSignString(
            "1250000", listOf("secretid" to "AKIDx", "timestamp" to "1000")
        )
        val ours = TencentV2Signing.signature(key, data)

        // JDK 参考实现（HMAC-SHA1 + 标准 Base64）
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        }
        val ref = Base64.getEncoder()
            .encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))

        assertEquals(ref, ours)               // 自带 base64 与 JDK 一致
        assertEquals(28, ours.length)          // HMAC-SHA1=20 字节 → base64 28 字符
        assertTrue(ours.endsWith("="))
    }

    @Test
    fun signature_isDeterministic() {
        val key = "k"
        val data = "asr.cloud.tencent.com/asr/v2/1?a=1"
        assertEquals(
            TencentV2Signing.signature(key, data),
            TencentV2Signing.signature(key, data)
        )
    }

    @Test
    fun buildUrl_containsUrlEncodedSignature() {
        val sorted = listOf("secretid" to "AKID x", "timestamp" to "1000")
        // 故意构造会产生特殊字符的签名输入
        val sig = TencentV2Signing.signature("k", TencentV2Signing.buildSignString("1", sorted))
        val url = TencentV2Signing.buildUrl("1", sorted, sig)
        assertTrue(url.startsWith("wss://asr.cloud.tencent.com/asr/v2/1?"))
        // signature 段必须存在且原 sig 经 URL 编码后出现在末尾
        assertTrue(url.contains("&signature="))
        assertTrue(url.endsWith(java.net.URLEncoder.encode(sig, "UTF-8")))
        // 参数值含空格被编码（+）
        assertTrue(url.contains("secretid=AKID+x"))
    }
}
