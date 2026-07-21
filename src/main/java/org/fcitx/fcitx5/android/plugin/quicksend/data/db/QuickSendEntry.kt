package org.fcitx.fcitx5.android.plugin.quicksend.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.fcitx.fcitx5.android.plugin.quicksend.data.ContentSegment

/**
 * 快捷发送条目。
 *
 * @property label 显示名称（选填），为空时列表按 [segments] 渲染
 * @property segments 内容段列表（JSON 存储）
 * @property sendMode 0=一起发送(COMBINATION), 1=单个发送(SEQUENCE)
 * @property useCount 使用次数（排序权重）
 */
@Entity(tableName = "quicksend")
data class QuickSendEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String = "",
    val segments: List<ContentSegment>,
    val sendMode: Int = MODE_COMBINATION,
    val useCount: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
) {
    companion object {
        const val MODE_COMBINATION = 0
        const val MODE_SEQUENCE = 1
    }
}
