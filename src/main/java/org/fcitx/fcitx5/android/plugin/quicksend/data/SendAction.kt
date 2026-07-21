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
     * COMBINATION 模式：
     * - 连续 modifier type=1 段合为修饰键列表
     * - 首个非 modifier type=1 段作主键
     * - 后续 type=1 段回退为单个 KeyPress
     * - 所有 type=0 段合并为一个 Text（放在最后）
     */
    fun buildCombination(segments: List<ContentSegment>): List<SendAction> {
        val trailingKeyPresses = mutableListOf<SendAction>()
        val modifiers = mutableListOf<Int>()
        var mainKey: Int? = null

        for (seg in segments) {
            if (seg.type != ContentSegment.TYPE_KEY) continue
            val code = KeyNameMapping.keyCodeOf(seg.content) ?: continue // 不在映射表，跳过
            when {
                mainKey == null && KeyNameMapping.isModifier(seg.content) -> modifiers.add(code)
                mainKey == null -> mainKey = code
                else -> trailingKeyPresses.add(SendAction.KeyPress(code))
            }
        }

        val textBuilder = StringBuilder()
        for (seg in segments) {
            if (seg.type == ContentSegment.TYPE_TEXT) textBuilder.append(seg.content)
        }

        val actions = mutableListOf<SendAction>()
        when {
            mainKey != null -> actions.add(SendAction.KeyCombination(modifiers.toList(), mainKey))
            modifiers.isNotEmpty() -> {
                // 仅有修饰键、无主键：每个修饰键单独按下（回退）
                modifiers.forEach { actions.add(SendAction.KeyPress(it)) }
            }
        }
        actions.addAll(trailingKeyPresses)
        if (textBuilder.isNotEmpty()) {
            actions.add(SendAction.Text(textBuilder.toString()))
        }
        return actions
    }

    /**
     * SEQUENCE 模式：
     * - type=1 段：单个 KeyPress（down + up）
     * - type=0 段：逐字符拆为 Text
     */
    fun buildSequence(segments: List<ContentSegment>): List<SendAction> {
        val actions = mutableListOf<SendAction>()
        for (seg in segments) {
            when (seg.type) {
                ContentSegment.TYPE_TEXT -> {
                    seg.content.forEach { ch -> actions.add(SendAction.Text(ch.toString())) }
                }
                ContentSegment.TYPE_KEY -> {
                    KeyNameMapping.keyCodeOf(seg.content)
                        ?.let { actions.add(SendAction.KeyPress(it)) }
                }
            }
        }
        return actions
    }
}
