package org.fcitx.fcitx5.android.plugin.quicksend.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.plugin.quicksend.data.ContentSegment

/** 将 [ContentSegment] 列表序列化为 JSON 字符串存储到 Room。 */
class QuickSendConverters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @TypeConverter
    fun fromSegmentList(segments: List<ContentSegment>): String =
        json.encodeToString(segments)

    @TypeConverter
    fun toSegmentList(value: String): List<ContentSegment> =
        json.decodeFromString(value)
}
