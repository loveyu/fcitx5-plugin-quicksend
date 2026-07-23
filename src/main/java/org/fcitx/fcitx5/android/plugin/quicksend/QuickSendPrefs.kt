package org.fcitx.fcitx5.android.plugin.quicksend

/**
 * 共享偏好文件名、键名与默认值。设置页与悬浮窗服务共用，
 * 避免键名/默认值分散在多处导致不一致。
 */
object QuickSendPrefs {

    const val FILE = "quicksend_prefs"

    /** 是否启用悬浮发送按钮 */
    const val OVERLAY_ENABLED = "overlay_enabled"

    /** 悬浮按钮上显示的文字（单个字符观感最佳），默认「发」 */
    const val BUTTON_TEXT = "button_text"
    const val BUTTON_TEXT_DEFAULT = "发"
}
