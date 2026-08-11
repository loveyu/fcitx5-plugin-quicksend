package org.fcitx.fcitx5.android.plugin.quicksend.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import org.fcitx.fcitx5.android.plugin.quicksend.R
import org.fcitx.fcitx5.android.plugin.quicksend.data.ContentSegment
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry

/**
 * 渲染条目内容：label 非空时显示 label；否则遍历 segments，
 * type=0 显示原文，type=1 带背景高亮显示 `[KEY]`，type=2 显示为延迟色的 `{毫秒}`。
 */
object SegmentFormatter {

    private const val MAX_LEN = 40
    fun displayLabel(context: Context, entry: QuickSendEntry): CharSequence {
        val label = entry.label.trim()
        if (label.isEmpty()) return formatSegments(context, entry.segments)
        return SpannableStringBuilder(label).append("\n").append(formatSegments(context, entry.segments))
    }

    fun formatSegments(context: Context, segments: List<ContentSegment>): CharSequence {
        val sb = SpannableStringBuilder()
        val delayContainer = context.getColor(R.color.qs_delay_container)
        val delayContent = context.getColor(R.color.qs_delay_content)
        for (seg in segments) {
            if (sb.isNotEmpty()) sb.append(" ")
            when (seg.type) {
                ContentSegment.TYPE_KEY -> {
                    val start = sb.length
                    sb.append("[${seg.content}]")
                    sb.setSpan(BackgroundColorSpan(0x33808080), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ContentSegment.TYPE_DELAY -> {
                    val delay = ContentSegment.delayMillis(seg.content) ?: continue
                    val start = sb.length
                    sb.append("{$delay}")
                    sb.setSpan(BackgroundColorSpan(delayContainer), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(delayContent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> sb.append(seg.content)
            }
        }
        return if (sb.length > MAX_LEN) {
            SpannableStringBuilder(sb, 0, MAX_LEN).append("…")
        } else {
            sb
        }
    }
}
