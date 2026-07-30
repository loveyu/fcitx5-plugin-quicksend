/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote.alibaba

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.SimpleTimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云智能语音交互 Token 获取（POP 协议 CreateToken API）。
 *
 * 文档：https://help.aliyun.com/zh/isi/getting-started/use-http-or-https-to-obtain-an-access-token
 *
 * 流程：HMAC-SHA1 签名 → GET nls-meta.cn-shanghai.aliyuncs.com/?... → 返回 Token.Id + ExpireTime。
 * 无 Android 依赖，纯 JVM，可单测。
 */
object AlibabaCreateToken {

    private const val ALGORITHM = "HmacSHA1"
    private const val UTF8 = "UTF-8"
    private const val TOKEN_API = "http://nls-meta.cn-shanghai.aliyuncs.com/"
    private const val ACTION = "CreateToken"
    private const val VERSION = "2019-02-28"

    /**
     * 获取 Token。返回 [TokenResult] 或 null（失败时）。
     *
     * @param accessKeyId     阿里云 AccessKey ID
     * @param accessKeySecret 阿里云 AccessKey Secret
     */
    fun fetchToken(accessKeyId: String, accessKeySecret: String): TokenResult? {
        val params = linkedMapOf(
            "AccessKeyId" to accessKeyId,
            "Action" to ACTION,
            "Version" to VERSION,
            "Timestamp" to iso8601Utc(),
            "Format" to "JSON",
            "RegionId" to "cn-shanghai",
            "SignatureMethod" to "HMAC-SHA1",
            "SignatureVersion" to "1.0",
            "SignatureNonce" to uuid(),
        )
        val queryString = canonicalizedQuery(params)
        val stringToSign = "GET&%2F&${percentEncode(queryString)}"
        val signature = hmacSha1Base64(stringToSign, "$accessKeySecret&")
        val signedUrl = "$TOKEN_API?Signature=${percentEncode(signature)}&$queryString"

        return try {
            val json = java.net.URL(signedUrl).readText()
            val obj = org.json.JSONObject(json)
            val tokenObj = obj.optJSONObject("Token") ?: return null
            val id = tokenObj.optString("Id", "")
            val expireTime = tokenObj.optLong("ExpireTime", 0)
            if (id.isEmpty() || expireTime == 0L) null
            else TokenResult(id, expireTime)
        } catch (e: Exception) {
            null
        }
    }

    private fun canonicalizedQuery(params: Map<String, String>): String {
        val sorted = params.entries.sortedBy { it.key }
        return sorted.joinToString("&") { (k, v) ->
            "${percentEncode(k)}=${percentEncode(v)}"
        }
    }

    internal fun percentEncode(value: String): String {
        return URLEncoder.encode(value, UTF8)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    internal fun hmacSha1Base64(data: String, key: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), ALGORITHM))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    internal fun iso8601Utc(): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = SimpleTimeZone(0, "GMT")
        return df.format(Date())
    }

    internal fun uuid(): String = UUID.randomUUID().toString()

    /** Token 获取结果，id 为空/expireTime 为 0 表示失败。 */
    data class TokenResult(
        val id: String,
        /** Token 过期时间戳（秒）。 */
        val expireTime: Long,
    ) {
        val isValid: Boolean get() = id.isNotEmpty() && expireTime > System.currentTimeMillis() / 1000
    }
}
