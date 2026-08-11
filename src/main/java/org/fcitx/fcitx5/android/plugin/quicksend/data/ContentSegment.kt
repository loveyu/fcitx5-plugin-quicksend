package org.fcitx.fcitx5.android.plugin.quicksend.data

import kotlinx.serialization.Serializable

/**
 * 内容段。
 *
 * @property type 0=普通文本, 1=特殊键, 2=延迟
 * @property content type=0 时存储文本原文；type=1 时存储特殊键规范化名称；type=2 时存储延迟毫秒数
 */
@Serializable
data class ContentSegment(
    val type: Int,
    val content: String
) {
    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_KEY = 1
        const val TYPE_DELAY = 2

        const val MIN_DELAY_MILLIS = 1L
        const val MAX_DELAY_MILLIS = 5_000L

        fun delayMillis(content: String): Long? = content.toLongOrNull()
            ?.takeIf { it in MIN_DELAY_MILLIS..MAX_DELAY_MILLIS }
    }
}
