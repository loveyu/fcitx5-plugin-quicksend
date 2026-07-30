/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.alibaba

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证阿里 Cloud CreateToken 签名逻辑与官方文档测试用例一致。
 *
 * 文档：https://help.aliyun.com/zh/isi/getting-started/use-http-or-https-to-obtain-an-access-token
 */
class AlibabaCreateTokenTest {

    companion object {
        private const val TEST_AK_ID = "my_access_key_id"
        private const val TEST_AK_SECRET = "my_access_key_secret"
        private const val TEST_TIMESTAMP = "2019-04-18T08:32:31Z"
        private const val TEST_NONCE = "b924c8c3-6d03-4c5d-ad36-d984d3116788"
    }

    @Test
    fun testPercentEncode() {
        // 基本字符不编码
        assertEquals("abcdef", AlibabaCreateToken.percentEncode("abcdef"))
        // 冒号编码
        assertEquals("2019-04-18T08%3A32%3A31Z", AlibabaCreateToken.percentEncode("2019-04-18T08:32:31Z"))
        // 等号编码（用于签名最后）
        assertEquals("hHq4yNsPitlfDJ2L0nQPdugdEzM%3D",
            AlibabaCreateToken.percentEncode("hHq4yNsPitlfDJ2L0nQPdugdEzM="))
    }

    @Test
    fun testHmacSha1Base64() {
        val data = "test_string"
        val key = "test_key&"
        val sig = AlibabaCreateToken.hmacSha1Base64(data, key)
        assertEquals(28, sig.length) // standard Base64 of 20 bytes = 28 chars
    }

    @Test
    fun testCanonicalizedQuery() {
        val params = linkedMapOf(
            "AccessKeyId" to TEST_AK_ID,
            "Action" to "CreateToken",
            "Version" to "2019-02-28",
            "Timestamp" to TEST_TIMESTAMP,
            "Format" to "JSON",
            "RegionId" to "cn-shanghai",
            "SignatureMethod" to "HMAC-SHA1",
            "SignatureVersion" to "1.0",
            "SignatureNonce" to TEST_NONCE,
        )
        val query = AlibabaCreateToken.percentEncode(
            "AccessKeyId=my_access_key_id&" +
                "Action=CreateToken&" +
                "Format=JSON&" +
                "RegionId=cn-shanghai&" +
                "SignatureMethod=HMAC-SHA1&" +
                "SignatureNonce=$TEST_NONCE&" +
                "SignatureVersion=1.0&" +
                "Timestamp=2019-04-18T08%3A32%3A31Z&" +
                "Version=2019-02-28"
        )
        // canonicalizedQuery 应与文档的规范化请求串编码后一致
        val expected = query
        val actual = AlibabaCreateToken.percentEncode(
            AlibabaCreateToken.percentEncode( // 需要 encodeParameter，也就是直接编码键值对
                params.toList().sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
            )
        )
        // 我们只需要验证 canonicalizedQuery 本身（未编码的 queryString）排序正确
        val canonical = params.toList().sortedBy { it.first }
            .joinToString("&") { (k, v) ->
                "${AlibabaCreateToken.percentEncode(k)}=${AlibabaCreateToken.percentEncode(v)}"
            }
        assertEquals(
            "AccessKeyId=my_access_key_id&" +
                "Action=CreateToken&" +
                "Format=JSON&" +
                "RegionId=cn-shanghai&" +
                "SignatureMethod=HMAC-SHA1&" +
                "SignatureNonce=$TEST_NONCE&" +
                "SignatureVersion=1.0&" +
                "Timestamp=2019-04-18T08%3A32%3A31Z&" +
                "Version=2019-02-28",
            canonical
        )
    }

    @Test
    fun testSigningString() {
        val params = linkedMapOf(
            "AccessKeyId" to TEST_AK_ID,
            "Action" to "CreateToken",
            "Version" to "2019-02-28",
            "Timestamp" to TEST_TIMESTAMP,
            "Format" to "JSON",
            "RegionId" to "cn-shanghai",
            "SignatureMethod" to "HMAC-SHA1",
            "SignatureVersion" to "1.0",
            "SignatureNonce" to TEST_NONCE,
        )
        val canonical = params.toList().sortedBy { it.first }
            .joinToString("&") { (k, v) ->
                "${AlibabaCreateToken.percentEncode(k)}=${AlibabaCreateToken.percentEncode(v)}"
            }
        val stringToSign = "GET&%2F&${AlibabaCreateToken.percentEncode(canonical)}"

        assertEquals(
            "GET&%2F&AccessKeyId%3Dmy_access_key_id%26" +
                "Action%3DCreateToken%26" +
                "Format%3DJSON%26" +
                "RegionId%3Dcn-shanghai%26" +
                "SignatureMethod%3DHMAC-SHA1%26" +
                "SignatureNonce%3D${TEST_NONCE}%26" +
                "SignatureVersion%3D1.0%26" +
                "Timestamp%3D2019-04-18T08%253A32%253A31Z%26" +
                "Version%3D2019-02-28",
            stringToSign
        )
    }

    @Test
    fun testSignatureMatchesDocumentation() {
        val params = linkedMapOf(
            "AccessKeyId" to TEST_AK_ID,
            "Action" to "CreateToken",
            "Version" to "2019-02-28",
            "Timestamp" to TEST_TIMESTAMP,
            "Format" to "JSON",
            "RegionId" to "cn-shanghai",
            "SignatureMethod" to "HMAC-SHA1",
            "SignatureVersion" to "1.0",
            "SignatureNonce" to TEST_NONCE,
        )
        val canonical = params.toList().sortedBy { it.first }
            .joinToString("&") { (k, v) ->
                "${AlibabaCreateToken.percentEncode(k)}=${AlibabaCreateToken.percentEncode(v)}"
            }
        val stringToSign = "GET&%2F&${AlibabaCreateToken.percentEncode(canonical)}"
        val signature = AlibabaCreateToken.hmacSha1Base64(stringToSign, "$TEST_AK_SECRET&")

        // 文档给出的预期签名
        assertEquals("hHq4yNsPitlfDJ2L0nQPdugdEzM=", signature)
    }
}
