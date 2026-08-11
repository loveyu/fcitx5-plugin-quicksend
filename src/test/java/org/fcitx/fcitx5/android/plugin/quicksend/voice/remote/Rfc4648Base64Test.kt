/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.quicksend.voice.remote

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class Rfc4648Base64Test {
    @Test
    fun encode_matchesJdkForPaddingAndBinaryData() {
        val samples = listOf(
            byteArrayOf(),
            byteArrayOf(0),
            byteArrayOf(0, 1),
            byteArrayOf(0, 1, 2),
            byteArrayOf(0, 1, 2, 3),
            byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x7F),
            "quicksend".toByteArray(Charsets.UTF_8),
        )

        samples.forEach { bytes ->
            assertEquals(Base64.getEncoder().encodeToString(bytes), Rfc4648Base64.encode(bytes))
        }
    }
}
