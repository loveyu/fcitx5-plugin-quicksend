/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote

/**
 * 纯 JVM 的 RFC 4648 标准 Base64 编码（无换行）。
 *
 * 不依赖 API 26 才可用的 [java.util.Base64]，保证远端签名逻辑兼容 minSdk 24，
 * 同时保持纯 JVM 单元测试能力。
 */
internal object Rfc4648Base64 {
    private val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

    fun encode(bytes: ByteArray): String {
        val result = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xFF
            val second = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else -1
            val third = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else -1
            result.append(table[first ushr 2])
            result.append(table[((first and 0x03) shl 4) or if (second < 0) 0 else second ushr 4])
            result.append(if (second < 0) '=' else table[((second and 0x0F) shl 2) or if (third < 0) 0 else third ushr 6])
            result.append(if (third < 0) '=' else table[third and 0x3F])
            index += 3
        }
        return result.toString()
    }
}
