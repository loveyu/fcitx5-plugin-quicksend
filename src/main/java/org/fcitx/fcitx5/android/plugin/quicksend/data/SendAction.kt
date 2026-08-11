package org.fcitx.fcitx5.android.plugin.quicksend.data

import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry

/**
 * 发送动作。由 [ContentSegment] 列表按 [QuickSendEntry.sendMode] 合并而来。
 */
sealed class SendAction {

    /** 组合键：先按下所有 modifiers，按下并释放 mainKey，再逆序释放 modifiers。 */
    data class KeyCombination(val modifiers: List<Int>, val mainKey: Int) : SendAction()

    /** 按下并释放单个键。 */
    data class KeyPress(val keyCode: Int) : SendAction()

    /** 提交一段文本。 */
    data class Text(val text: String) : SendAction()

    /** 暂停指定时间，不向目标应用发送任何内容。 */
    data class Delay(val millis: Long) : SendAction()
}

/**
 * 将内容段列表按发送模式合并为 [SendAction] 序列。纯算法，不涉及实际发送。
 */
object SendActionBuilder {

    fun build(segments: List<ContentSegment>, sendMode: Int): List<SendAction> {
        return if (sendMode == QuickSendEntry.MODE_SEQUENCE) {
            buildSequence(segments)
        } else {
            buildCombination(segments)
        }
    }

    /**
     * COMBINATION 模式（组合键序列）：
     * - 每组连续 modifier type=1 段与紧接的一个主键合为组合键
     * - 单字符文本也可作为组合键主键，兼容已有的 `[CTRL]c` 条目
     * - 其余内容严格保持原有顺序；延迟段会切断修饰键组
     */
    fun buildCombination(segments: List<ContentSegment>): List<SendAction> {
        val actions = mutableListOf<SendAction>()
        var index = 0
        while (index < segments.size) {
            val segment = segments[index]
            if (segment.type == ContentSegment.TYPE_KEY && KeyNameMapping.isModifier(segment.content)) {
                val modifiers = mutableListOf<Int>()
                while (index < segments.size) {
                    val modifier = segments[index]
                    val code = KeyNameMapping.keyCodeOf(modifier.content)
                    if (modifier.type != ContentSegment.TYPE_KEY || code == null || !KeyNameMapping.isModifier(modifier.content)) break
                    modifiers += code
                    index++
                }
                val main = segments.getOrNull(index)
                val mainKey = when {
                    main?.type == ContentSegment.TYPE_KEY -> KeyNameMapping.keyCodeOf(main.content)
                        ?.takeUnless { KeyNameMapping.isModifier(main.content) }
                    main?.type == ContentSegment.TYPE_TEXT && main.content.length == 1 ->
                        KeyNameMapping.keyCodeOfChar(main.content[0])
                    else -> null
                }
                if (mainKey != null) {
                    actions += SendAction.KeyCombination(modifiers, mainKey)
                    index++
                } else {
                    // 只有修饰键时无法组成组合键，保持兼容地逐个发送。
                    modifiers.forEach { actions += SendAction.KeyPress(it) }
                }
                continue
            }

            when (segment.type) {
                ContentSegment.TYPE_TEXT -> actions += SendAction.Text(segment.content)
                ContentSegment.TYPE_KEY -> KeyNameMapping.keyCodeOf(segment.content)
                    ?.let { actions += SendAction.KeyPress(it) }
                ContentSegment.TYPE_DELAY -> ContentSegment.delayMillis(segment.content)
                    ?.let { actions += SendAction.Delay(it) }
            }
            index++
        }
        return actions
    }

    /**
     * SEQUENCE 模式：
     * - type=1 段：单个 KeyPress（down + up）
     * - type=0 段：整段 Text
     * - type=2 段：Delay
     */
    fun buildSequence(segments: List<ContentSegment>): List<SendAction> {
        val actions = mutableListOf<SendAction>()
        for (seg in segments) {
            when (seg.type) {
                ContentSegment.TYPE_TEXT -> {
                    actions.add(SendAction.Text(seg.content))
                }
                ContentSegment.TYPE_KEY -> {
                    KeyNameMapping.keyCodeOf(seg.content)
                        ?.let { actions.add(SendAction.KeyPress(it)) }
                }
                ContentSegment.TYPE_DELAY -> ContentSegment.delayMillis(seg.content)
                    ?.let { actions.add(SendAction.Delay(it)) }
            }
        }
        return actions
    }
}
