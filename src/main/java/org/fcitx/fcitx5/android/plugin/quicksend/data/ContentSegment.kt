package org.fcitx.fcitx5.android.plugin.quicksend.data

import kotlinx.serialization.Serializable

/**
 * 内容段。
 *
 * @property type 0=普通文本, 1=特殊键, 2/3=预留
 * @property content type=0 时存储文本原文; type=1 时存储特殊键规范化名称（如 "CTRL", "SHIFT", "DEL"）
 */
@Serializable
data class ContentSegment(
    val type: Int,
    val content: String
) {
    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_KEY = 1
    }
}
