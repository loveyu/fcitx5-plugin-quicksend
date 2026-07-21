package org.fcitx.fcitx5.android.plugin.quicksend

import android.util.Log
import android.view.KeyEvent
import org.fcitx.fcitx5.android.common.ipc.IQuickSendService
import org.fcitx.fcitx5.android.plugin.quicksend.data.QuickSendManager
import org.fcitx.fcitx5.android.plugin.quicksend.data.SendAction
import org.fcitx.fcitx5.android.plugin.quicksend.data.SendActionBuilder
import org.fcitx.fcitx5.android.plugin.quicksend.data.db.QuickSendEntry

private const val TAG = "QuickSendExecutor"

/**
 * 执行快捷发送：将条目段合并为 [SendAction] 序列，通过 [IQuickSendService]
 * 调用 fcitx5-android 主程序的发送能力，并自增使用计数。
 *
 * 连接来源：���认取 [RemoteServiceHolder.service]（由 MainService 反向绑定填入）；
 * 调用方（如悬浮窗）也可显式传入自己已建立的连接，避免依赖输入法主动加载插件。
 *
 * ⚠️ 发送依赖主项目 [IQuickSendService] 提供 commitText / sendKeyDownUpKey /
 * sendKeyCombination。主项目未实现时，远程调用会失败。
 */
object QuickSendExecutor {

    /**
     * 执行一条快捷发送。返回是否成功送达主程序。
     *
     * @param service 显式传入的连接（如悬浮窗自身绑定）；为空则回退到 [RemoteServiceHolder]。
     */
    suspend fun execute(
        entry: QuickSendEntry,
        service: IQuickSendService? = RemoteServiceHolder.service
    ): Boolean {
        val actions = SendActionBuilder.build(entry.segments, entry.sendMode)
        val ok = executeActions(actions, service ?: RemoteServiceHolder.service)
        if (ok) {
            QuickSendManager.incrementUse(entry.id)
        } else {
            Log.w(TAG, "Send failed (remote service unavailable or unsupported)")
        }
        return ok
    }

    private fun executeActions(actions: List<SendAction>, remote: IQuickSendService?): Boolean {
        if (remote == null) {
            Log.w(TAG, "Remote service not connected")
            return false
        }
        return try {
            for (action in actions) {
                when (action) {
                    is SendAction.KeyCombination -> {
                        val (alt, ctrl, shift, meta) = decodeModifiers(action.modifiers)
                        remote.sendKeyCombination(action.mainKey, alt, ctrl, shift, meta)
                    }
                    is SendAction.KeyPress -> {
                        remote.sendKeyDownUpKey(action.keyCode, 0)
                    }
                    is SendAction.Text -> {
                        remote.commitText(action.text, -1)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "execute failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** 将修饰键 KEYCODE 列表解码为 alt/ctrl/shift/meta 四个布尔（忽略左右）。 */
    private fun decodeModifiers(modifiers: List<Int>): Modifiers {
        var alt = false
        var ctrl = false
        var shift = false
        var meta = false
        for (code in modifiers) {
            when (code) {
                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> alt = true
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> ctrl = true
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> shift = true
                KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> meta = true
            }
        }
        return Modifiers(alt, ctrl, shift, meta)
    }

    private data class Modifiers(val alt: Boolean, val ctrl: Boolean, val shift: Boolean, val meta: Boolean)
}
