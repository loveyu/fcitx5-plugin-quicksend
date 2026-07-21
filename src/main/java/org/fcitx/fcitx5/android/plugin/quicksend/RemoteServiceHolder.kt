package org.fcitx.fcitx5.android.plugin.quicksend

import org.fcitx.fcitx5.android.common.ipc.IQuickSendService

/**
 * 持有与 fcitx5-android 主程序 [IQuickSendService] 的连接。
 *
 * 由 [MainService] 在主程序绑定本插件时反向绑定主程序并赋值，
 * [QuickSendExecutor] 通过它执行实际发送。
 */
object RemoteServiceHolder {
    @Volatile
    var service: IQuickSendService? = null

    val isConnected: Boolean get() = service != null
}
