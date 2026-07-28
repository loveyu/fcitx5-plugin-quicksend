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

    /** 代理（单个 URI 字符串，如 http://127.0.0.1:7890、socks5://user:pass@host:1080；空=不用） */
    const val VOICE_PROXY_URI = "voice_proxy_uri"

    // 旧版多字段代理（仅用于迁移到 VOICE_PROXY_URI），新代码不再写入
    const val VOICE_PROXY_ENABLED_LEGACY = "voice_proxy_enabled"
    const val VOICE_PROXY_TYPE_LEGACY = "voice_proxy_type"
    const val VOICE_PROXY_HOST_LEGACY = "voice_proxy_host"
    const val VOICE_PROXY_PORT_LEGACY = "voice_proxy_port"
    const val VOICE_PROXY_USER_LEGACY = "voice_proxy_user"
    const val VOICE_PROXY_PASS_LEGACY = "voice_proxy_pass"

    /** 模型文件名（默认 zh 14M int8，可改以适配其它流式模型） */
    const val VOICE_NAME_ENCODER = "voice_name_encoder"
    const val VOICE_NAME_DECODER = "voice_name_decoder"
    const val VOICE_NAME_JOINER = "voice_name_joiner"
    const val VOICE_NAME_TOKENS = "voice_name_tokens"

    // ===== 识别参数 =====

    const val VOICE_DECODING_METHOD = "voice_decoding_method"
    const val VOICE_MAX_ACTIVE_PATHS = "voice_max_active_paths"
    const val VOICE_BLANK_PENALTY = "voice_blank_penalty"
    const val VOICE_ENDPOINT_SILENCE = "voice_endpoint_silence"
    const val VOICE_ENDPOINT_MAX_UTTER = "voice_endpoint_max_utter"
    const val VOICE_NUM_THREADS = "voice_num_threads"
    const val VOICE_PROVIDER = "voice_provider"

    // ===== 远端 ASR =====

    const val VOICE_REMOTE_ENABLED = "voice_remote_enabled"
    const val VOICE_REMOTE_URL = "voice_remote_url"
    const val VOICE_REMOTE_TOKEN = "voice_remote_token"
}
