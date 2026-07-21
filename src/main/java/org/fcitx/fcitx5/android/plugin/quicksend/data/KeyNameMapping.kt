package org.fcitx.fcitx5.android.plugin.quicksend.data

import android.view.KeyEvent

/**
 * 特殊键名 ↔ Android [KeyEvent] KEYCODE 映射。
 *
 * type=1 的 [ContentSegment.content] 统一存储大写规范化名称（如 "CTRL"）。
 * 映射依据见 `docs/.../data-model-proposal.md`。
 */
object KeyNameMapping {

    /** 下拉分组用：每组标题不可选。 */
    data class KeyGroup(val title: String, val keys: List<String>)

    private val keyCodeMap: Map<String, Int> = buildMap {
        // 修饰键
        put("SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT)
        put("RSHIFT", KeyEvent.KEYCODE_SHIFT_RIGHT)
        put("CTRL", KeyEvent.KEYCODE_CTRL_LEFT)
        put("RCTRL", KeyEvent.KEYCODE_CTRL_RIGHT)
        put("ALT", KeyEvent.KEYCODE_ALT_LEFT)
        put("RALT", KeyEvent.KEYCODE_ALT_RIGHT)
        put("META", KeyEvent.KEYCODE_META_LEFT)
        put("RMETA", KeyEvent.KEYCODE_META_RIGHT)
        // 控制键
        put("TAB", KeyEvent.KEYCODE_TAB)
        put("CAPS", KeyEvent.KEYCODE_CAPS_LOCK)
        put("ESC", KeyEvent.KEYCODE_ESCAPE)
        put("ENTER", KeyEvent.KEYCODE_ENTER)
        put("SPACE", KeyEvent.KEYCODE_SPACE)
        put("BACKSPACE", KeyEvent.KEYCODE_DEL)
        put("DEL", KeyEvent.KEYCODE_FORWARD_DEL)
        put("INSERT", KeyEvent.KEYCODE_INSERT)
        put("HOME", KeyEvent.KEYCODE_MOVE_HOME)
        put("END", KeyEvent.KEYCODE_MOVE_END)
        put("PAGEUP", KeyEvent.KEYCODE_PAGE_UP)
        put("PAGEDOWN", KeyEvent.KEYCODE_PAGE_DOWN)
        put("PRINTSCREEN", KeyEvent.KEYCODE_SYSRQ)
        put("SCROLLLOCK", KeyEvent.KEYCODE_SCROLL_LOCK)
        put("PAUSE", KeyEvent.KEYCODE_BREAK)
        put("NUMLOCK", KeyEvent.KEYCODE_NUM_LOCK)
        put("MENU", KeyEvent.KEYCODE_MENU)
        // 导航键
        put("UP", KeyEvent.KEYCODE_DPAD_UP)
        put("DOWN", KeyEvent.KEYCODE_DPAD_DOWN)
        put("LEFT", KeyEvent.KEYCODE_DPAD_LEFT)
        put("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT)
        // 功能键
        for (i in 1..12) put("F$i", KeyEvent.KEYCODE_F1 + i - 1)
        // 符号键
        put("PLUS", KeyEvent.KEYCODE_PLUS)
        put("MINUS", KeyEvent.KEYCODE_MINUS)
        put("EQUAL", KeyEvent.KEYCODE_EQUALS)
        put("COMMA", KeyEvent.KEYCODE_COMMA)
        put("PERIOD", KeyEvent.KEYCODE_PERIOD)
        put("SLASH", KeyEvent.KEYCODE_SLASH)
        put("ASTERISK", KeyEvent.KEYCODE_STAR)
        put("SEMICOLON", KeyEvent.KEYCODE_SEMICOLON)
        put("APOSTROPHE", KeyEvent.KEYCODE_APOSTROPHE)
        put("GRAVE", KeyEvent.KEYCODE_GRAVE)
        put("BACKSLASH", KeyEvent.KEYCODE_BACKSLASH)
        put("LEFTBRACKET", KeyEvent.KEYCODE_LEFT_BRACKET)
        put("RIGHTBRACKET", KeyEvent.KEYCODE_RIGHT_BRACKET)
        put("POUND", KeyEvent.KEYCODE_POUND)
        put("AT", KeyEvent.KEYCODE_AT)
        // 数字键盘
        for (i in 0..9) put("NUMPAD$i", KeyEvent.KEYCODE_NUMPAD_0 + i)
        put("NUMPAD_DIVIDE", KeyEvent.KEYCODE_NUMPAD_DIVIDE)
        put("NUMPAD_MULTIPLY", KeyEvent.KEYCODE_NUMPAD_MULTIPLY)
        put("NUMPAD_SUBTRACT", KeyEvent.KEYCODE_NUMPAD_SUBTRACT)
        put("NUMPAD_ADD", KeyEvent.KEYCODE_NUMPAD_ADD)
        put("NUMPAD_DOT", KeyEvent.KEYCODE_NUMPAD_DOT)
        put("NUMPAD_ENTER", KeyEvent.KEYCODE_NUMPAD_ENTER)
        // 字母
        for (c in 'A'..'Z') put(c.toString(), KeyEvent.KEYCODE_A + (c - 'A'))
        // 数字
        for (i in 0..9) put(i.toString(), KeyEvent.KEYCODE_0 + i)
    }

    /** 修饰键集合（用于 COMBINATION 模式判断）。 */
    val modifierKeys: Set<String> = setOf(
        "SHIFT", "RSHIFT", "CTRL", "RCTRL", "ALT", "RALT", "META", "RMETA"
    )

    /** 别名 → 规范名（下拉搜索容错）。 */
    private val aliasMap: Map<String, String> = buildMap {
        put("LSHIFT", "SHIFT")
        put("LCTRL", "CTRL")
        put("LALT", "ALT")
        put("LMETA", "META")
        put("WIN", "META"); put("LWIN", "META"); put("SUPER", "META")
        put("RWIN", "RMETA")
        put("CAPS_LOCK", "CAPS")
        put("ESCAPE", "ESC")
        put("RETURN", "ENTER")
        put("BS", "BACKSPACE")
        put("DELETE", "DEL")
        put("INS", "INSERT")
        put("PGUP", "PAGEUP")
        put("PGDN", "PAGEDOWN")
        put("PRTSC", "PRINTSCREEN")
        put("SCRLK", "SCROLLLOCK")
        put("BREAK", "PAUSE")
        put("EQUALS", "EQUAL")
        put("DOT", "PERIOD")
        put("STAR", "ASTERISK")
        put("QUOTE", "APOSTROPHE")
        put("BACKTICK", "GRAVE")
        put("[", "LEFTBRACKET")
        put("]", "RIGHTBRACKET")
        put("#", "POUND")
        put("@", "AT")
    }

    /** 规范名 → KEYCODE，支持别名；未找到返回 null。 */
    fun keyCodeOf(name: String): Int? {
        val upper = name.uppercase()
        keyCodeMap[upper]?.let { return it }
        aliasMap[upper]?.let { return keyCodeMap[it] }
        return null
    }

    fun isModifier(name: String): Boolean = name.uppercase() in modifierKeys

    fun isValidKey(name: String): Boolean = keyCodeOf(name) != null

    /** UI 下拉分组（标题不可选）。 */
    val groups: List<KeyGroup> = listOf(
        KeyGroup("修饰键", listOf("SHIFT", "RSHIFT", "CTRL", "RCTRL", "ALT", "RALT", "META", "RMETA")),
        KeyGroup(
            "控制键",
            listOf(
                "TAB", "CAPS", "ESC", "ENTER", "SPACE", "BACKSPACE", "DEL",
                "INSERT", "HOME", "END", "PAGEUP", "PAGEDOWN",
                "PRINTSCREEN", "SCROLLLOCK", "PAUSE", "NUMLOCK", "MENU"
            )
        ),
        KeyGroup("导航键", listOf("UP", "DOWN", "LEFT", "RIGHT")),
        KeyGroup("功能键", (1..12).map { "F$it" }),
        KeyGroup(
            "符号键",
            listOf(
                "PLUS", "MINUS", "EQUAL", "COMMA", "PERIOD", "SLASH", "ASTERISK",
                "SEMICOLON", "APOSTROPHE", "GRAVE", "BACKSLASH",
                "LEFTBRACKET", "RIGHTBRACKET", "POUND", "AT"
            )
        ),
        KeyGroup(
            "数字键盘",
            (0..9).map { "NUMPAD$it" } + listOf(
                "NUMPAD_DIVIDE", "NUMPAD_MULTIPLY", "NUMPAD_SUBTRACT",
                "NUMPAD_ADD", "NUMPAD_DOT", "NUMPAD_ENTER"
            )
        ),
        KeyGroup("字母数字", ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() })
    )
}
