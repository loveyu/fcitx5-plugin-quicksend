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

    // ===== 语音输入 =====

    /** 模型下载 base URL（默认 HuggingFace，可改镜像） */
    const val VOICE_MODEL_BASE_URL = "voice_model_base_url"

    /** 代理 */
    const val VOICE_PROXY_ENABLED = "voice_proxy_enabled"
    const val VOICE_PROXY_TYPE = "voice_proxy_type" // "HTTP" | "SOCKS"
    const val VOICE_PROXY_HOST = "voice_proxy_host"
    const val VOICE_PROXY_PORT = "voice_proxy_port"
    const val VOICE_PROXY_USER = "voice_proxy_user"
    const val VOICE_PROXY_PASS = "voice_proxy_pass"

    /** 模型文件名（默认 zh 14M int8，可改以适配其它流式模型） */
    const val VOICE_NAME_ENCODER = "voice_name_encoder"
    const val VOICE_NAME_DECODER = "voice_name_decoder"
    const val VOICE_NAME_JOINER = "voice_name_joiner"
    const val VOICE_NAME_TOKENS = "voice_name_tokens"
}
