/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.tencent

import org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.TencentAsrV1Backend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 腾讯实时语音识别 V1 签名的纯 JVM 单测。V1 与 V2 共用 [TencentV2Signing]（同握手地址、同
 * HMAC-SHA1 签名算法），故这里只锁「V1 参数集 + 文档示例签名串格式」，HMAC/Base64 正确性由
 * [TencentV2SigningTest] 覆盖。
 *
 * 文档：https://cloud.tencent.com/document/product/1093/48982
 */
class TencentAsrV1SigningTest {

    /**
     * 用文档「签名生成」一节的示例参数（appid/secretid 文档里即打码）拼接签名原文，
     * 断言与文档给出的签名串逐字一致——锁住「不含 scheme、参数按字典序、k=v 以 & 连接」的格式。
     */
    @Test
    fun signString_matchesDocExample_format() {
        // 文档示例（字典序已排）：engine_model_type, expired, needvad, nonce, secretid, timestamp, voice_format, voice_id
        val s = TencentV2Signing.buildSignString(
            TencentAsrV1Backend.DEFAULT_BASE_URL, "125922***",
            listOf(
                "engine_model_type" to "16k_zh",
                "expired" to "1673494772",
                "needvad" to "1",
                "nonce" to "1673408372",
                "secretid" to "*****Qq1zhZMN8dv0******",
                "timestamp" to "1673408372",
                "voice_format" to "1",
                "voice_id" to "c64385ee-3e5c-4fc5-bbfd-7c71addb35b0"
            )
        )
        assertEquals(
            "asr.cloud.tencent.com/asr/v2/125922***?engine_model_type=16k_zh&expired=1673494772" +
                "&needvad=1&nonce=1673408372&secretid=*****Qq1zhZMN8dv0******&timestamp=1673408372" +
                "&voice_format=1&voice_id=c64385ee-3e5c-4fc5-bbfd-7c71addb35b0",
            s
        )
    }

    /**
     * V1 参数集含 filter_punc：验证其字典序位置（filter_dirty < filter_modal < filter_punc < needvad）
     * 出现在签名串里，避免拼参遗漏或错序导致 4002 鉴权失败。
     */
    @Test
    fun signString_includesFilterPunc_inLexOrder() {
        // 未排序传入（模拟识别器内未排前的 LinkedHashMap 顺序），由识别器 sortedBy 排序后调用；
        // 这里直接给排序后的列表，断言 filter_punc 位置正确
        val sorted = listOf(
            "convert_num_mode" to "1",
            "engine_model_type" to "16k_zh",
            "filter_dirty" to "0",
            "filter_modal" to "0",
            "filter_punc" to "0",
            "needvad" to "1",
            "secretid" to "AKIDx",
            "timestamp" to "1000",
            "voice_format" to "1",
            "voice_id" to "abc"
        )
        val s = TencentV2Signing.buildSignString(TencentAsrV1Backend.DEFAULT_BASE_URL, "1250000", sorted)
        assertTrue("filter_punc 必须参与签名", s.contains("filter_punc=0"))
        assertTrue("filter_modal 必须在 filter_punc 之前", s.indexOf("filter_modal=0") < s.indexOf("filter_punc=0"))
        assertTrue("filter_punc 必须在 needvad 之前", s.indexOf("filter_punc=0") < s.indexOf("needvad=1"))
    }

    /** V1 默认引擎为通用引擎 16k_zh（区别于 V2 的大模型引擎）。 */ @Test
    fun defaultEngineIsGeneral() {
        assertEquals("16k_zh", TencentAsrV1Backend.DEFAULT_ENGINE_MODEL_TYPE)
    }

    @Test
    fun buildUrl_usesV1DefaultBaseUrl() {
        val sorted = listOf("secretid" to "AKIDx", "timestamp" to "1000")
        val sig = TencentV2Signing.signature(
            "k", TencentV2Signing.buildSignString(TencentAsrV1Backend.DEFAULT_BASE_URL, "1", sorted)
        )
        val url = TencentV2Signing.buildUrl(TencentAsrV1Backend.DEFAULT_BASE_URL, "1", sorted, sig)
        assertTrue(url.startsWith("wss://asr.cloud.tencent.com/asr/v2/1?"))
        assertTrue(url.contains("&signature="))
    }
}
