# 单条数据结构方案 (v2)

## 数据模型

```kotlin
@Entity(tableName = "quicksend")
data class QuickSendEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 用户自定义显示名称，为空时列表自动按 segments 渲染显示 */
    val label: String = "",

    /** 内容段列表，JSON 序列化后存储 */
    val segments: List<ContentSegment>,

    /** 0=一起发送(COMBINATION), 1=单个发送(SEQUENCE) */
    val sendMode: Int = 0,

    val useCount: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/**
 * @param type 0=普通文本, 1=特殊键, 2=预留, 3=预留
 * @param content  type=0 时存储文本原文; type=1 时存储特殊键的规范化名称(如 "CTRL", "SHIFT", "DEL")
 */
data class ContentSegment(
    val type: Int,
    val content: String
)
```

Room TypeConverter 使用 `kotlinx.serialization` 将 `List<ContentSegment>` 序列化为 JSON 字符串存储。

---

## 显示策略

| 条件 | 列表显示 |
|------|---------|
| `label` 非空 | 显示 `label` |
| `label` 为空 | 遍历 `segments` 渲染: type=0 直接显示文本; type=1 带边框显示键名 (如 `[CTRL]`) |
| 右侧 | 始终显示使用次数 + 发送模式图标 |

---

## 特殊键映射表 (type=1)

统一使用**大写**作为 content 存储值。映射依据 `FcitxKeyMapping` + `KeyEvent`。

### 修饰键 (Modifiers)

| content 存储值 | 别名 (下拉可搜) | 对应的 Android KeyEvent | 说明 |
|---|---|---|---|
| `SHIFT` | `LSHIFT` | `KEYCODE_SHIFT_LEFT` | 不区分左右时默认左 |
| `RSHIFT` | — | `KEYCODE_SHIFT_RIGHT` | 明确指定右 Shift |
| `CTRL` | `LCTRL` | `KEYCODE_CTRL_LEFT` | 不区分左右时默认左 |
| `RCTRL` | — | `KEYCODE_CTRL_RIGHT` | 明确指定右 Ctrl |
| `ALT` | `LALT` | `KEYCODE_ALT_LEFT` | 不区分左右时默认左 |
| `RALT` | — | `KEYCODE_ALT_RIGHT` | 明确指定右 Alt |
| `META` | `LMETA`, `WIN`, `LWIN`, `SUPER` | `KEYCODE_META_LEFT` | Win / Super 键, 默认左 |
| `RMETA` | `RWIN` | `KEYCODE_META_RIGHT` | 右侧 Win 键 |

### 控制键 (Control Keys)

| content 存储值 | 别名 | KeyEvent |
|---|---|---|
| `TAB` | — | `KEYCODE_TAB` |
| `CAPS` | `CAPS_LOCK` | `KEYCODE_CAPS_LOCK` |
| `ESC` | `ESCAPE` | `KEYCODE_ESCAPE` |
| `ENTER` | `RETURN` | `KEYCODE_ENTER` |
| `SPACE` | — | `KEYCODE_SPACE` |
| `BACKSPACE` | `BS` | `KEYCODE_DEL` |
| `DEL` | `DELETE` | `KEYCODE_FORWARD_DEL` (删除键 Del) |
| `INSERT` | `INS` | `KEYCODE_INSERT` |
| `HOME` | — | `KEYCODE_MOVE_HOME` |
| `END` | — | `KEYCODE_MOVE_END` |
| `PAGEUP` | `PGUP` | `KEYCODE_PAGE_UP` |
| `PAGEDOWN` | `PGDN` | `KEYCODE_PAGE_DOWN` |
| `PRINTSCREEN` | `PRTSC` | `KEYCODE_SYSRQ` |
| `SCROLLLOCK` | `SCRLK` | `KEYCODE_SCROLL_LOCK` |
| `PAUSE` | `BREAK` | `KEYCODE_BREAK` |
| `NUMLOCK` | — | `KEYCODE_NUM_LOCK` |
| `MENU` | — | `KEYCODE_MENU` (上下文菜单) |

### 导航键

| content 存储值 | KeyEvent |
|---|---|
| `UP` | `KEYCODE_DPAD_UP` |
| `DOWN` | `KEYCODE_DPAD_DOWN` |
| `LEFT` | `KEYCODE_DPAD_LEFT` |
| `RIGHT` | `KEYCODE_DPAD_RIGHT` |

### 功能键

| content 存储值 | KeyEvent |
|---|---|
| `F1` ~ `F12` | `KEYCODE_F1` ~ `KEYCODE_F12` |

### 符号键 (用于发送真正的按键事件而非提交字符)

| content 存储值 | 别名 | KeyEvent |
|---|---|---|
| `PLUS` | — | `KEYCODE_PLUS` |
| `MINUS` | — | `KEYCODE_MINUS` |
| `EQUAL` | `EQUALS` | `KEYCODE_EQUALS` |
| `COMMA` | — | `KEYCODE_COMMA` |
| `PERIOD` | `DOT` | `KEYCODE_PERIOD` |
| `SLASH` | — | `KEYCODE_SLASH` |
| `ASTERISK` | `STAR` | `KEYCODE_STAR` |
| `SEMICOLON` | — | `KEYCODE_SEMICOLON` |
| `APOSTROPHE` | `QUOTE` | `KEYCODE_APOSTROPHE` |
| `GRAVE` | `BACKTICK` | `KEYCODE_GRAVE` |
| `BACKSLASH` | — | `KEYCODE_BACKSLASH` |
| `LEFTBRACKET` | `[` | `KEYCODE_LEFT_BRACKET` |
| `RIGHTBRACKET` | `]` | `KEYCODE_RIGHT_BRACKET` |
| `POUND` | `#` | `KEYCODE_POUND` |
| `AT` | `@` | `KEYCODE_AT` |

### 数字键盘 (Numpad)

| content 存储值 | KeyEvent |
|---|---|
| `NUMPAD0` ~ `NUMPAD9` | `KEYCODE_NUMPAD_0` ~ `KEYCODE_NUMPAD_9` |
| `NUMPAD_DIVIDE` | `KEYCODE_NUMPAD_DIVIDE` |
| `NUMPAD_MULTIPLY` | `KEYCODE_NUMPAD_MULTIPLY` |
| `NUMPAD_SUBTRACT` | `KEYCODE_NUMPAD_SUBTRACT` |
| `NUMPAD_ADD` | `KEYCODE_NUMPAD_ADD` |
| `NUMPAD_DOT` | `KEYCODE_NUMPAD_DOT` |
| `NUMPAD_ENTER` | `KEYCODE_NUMPAD_ENTER` |

### 字母/数字 (作为虚拟键发送)

A~Z, 0~9 也可作为 type=1 的特殊键直接映射为对应 `KEYCODE_A`~`KEYCODE_Z`, `KEYCODE_0`~`KEYCODE_9`。

> **type=0 vs type=1 的 A~Z 区别**: type=0 的 `"A"` 提交字符 `A`；type=1 的 `"A"` 发送 KeyEvent down/up of `KEYCODE_A`，应用层可能因键盘布局产生不同输出。

---

## 段合并与发送逻辑

### 将段按 sendMode 合并为发送序列

```kotlin
sealed class SendAction {
    /** 组合键同时按下: 先按下所有 modifiers，按下+释放 mainKey，再释放所有 modifiers */
    data class KeyCombination(val modifiers: List<Int>, val mainKey: Int) : SendAction()

    /** 按下并释放一个键 */
    data class KeyPress(val keyCode: Int) : SendAction()

    /** 提交一段文本 */
    data class Text(val text: String) : SendAction()
}
```

### COMBINATION 模式 (一起发送)

合并规则: **所有 type=1 的 modifier 键合为 modifiers 列表，最后一个非 modifier 的 type=1 作为 mainKey，所有 type=0 合并为一个 Text 放在最后**

```
segments = [CTRL, SHIFT, DEL] → KeyCombination([CTRL, SHIFT], DEL)
segments = [CTRL, PLUS]      → KeyCombination([CTRL], PLUS)
segments = [你好世界]          → Text("你好世界")
segments = [CTRL, SHIFT, DEL, 确认删除] → KeyCombination([CTRL,SHIFT], DEL) + Text("确认删除")
segments = [CTRL, A]          → KeyCombination([CTRL], A)
segments = [TAB]              → KeyCombination([], TAB)  // 无修饰键的组合
```

**执行**:
1. 按下所有 modifiers 的 down 事件
2. 按下 mainKey down + mainKey up
3. 逆序释放所有 modifiers 的 up 事件
4. 提交 Text 段

### SEQUENCE 模式 (单个发送)

合并规则: **所有段依次逐个发送，不作组合**

```
segments = [CTRL, SHIFT, DEL] → KeyPress(CTRL), KeyPress(SHIFT), KeyPress(DEL)
segments = [CTRL, PLUS]      → KeyPress(CTRL), KeyPress(PLUS)
segments = [你好世界]          → Text("你"), Text("好"), Text("世"), Text("界")
segments = [CTRL, SHIFT, DEL, 确认] → KeyPress(CTRL), KeyPress(SHIFT), KeyPress(DEL), Text("确"), Text("认")
segments = [Hi]               → Text("H"), Text("i")
```

**执行**:
- 每个 type=1 的段: down + up
- 每个 type=0 的段: **逐字符** commitText

---

## 编辑 UI 交互

### 编辑器布局 (横向 Flow 布局)

```
┌─────────────────────────────────────────────┐
│ 显示名称(选填): [_________________________] │
│                                              │
│ 发送内容:                                    │
│ ┌──────────────────────────────────────────┐ │
│ │ [CTRL] [SHIFT] [DEL]  确认删除    [+特殊键] │ │
│ └──────────────────────────────────────────┘ │
│                                              │
│ 发送模式: ○ 一起发送  ● 单个发送             │
│ 使用次数: [___3___]                          │
│                                              │
│       [取消]                    [保存]        │
└─────────────────────────────────────────────┘
```

- type=0 段: 无样式边框，直接显示文本
- type=1 段: 带边框显示 (如 `[CTRL]`)，边框颜色可配置
- 尾部的 `[+特殊键]` 按钮: 点击弹出分组下拉选择特殊键
- 特殊键选中后: 在光标位置插入一个 type=1 段
- 键盘直接输入: 在当前光标位置的 type=0 段中插入字符，或新建 type=0 段
- 允许删除单个段（每个段右上角 × 删除按钮或选中段后 Backspace 删除）

### 特殊键下拉分组展示

下拉菜单按以下分组展示（每组标题不可选）:

```
┌─────────────────────┐
│ 【修饰键】          │
│  SHIFT  RSHIFT      │
│  CTRL   RCTRL       │
│  ALT    RALT        │
│  META   RMETA       │
│─────────────────────│
│ 【控制键】          │
│  TAB  CAPS  ESC     │
│  ENTER  SPACE       │
│  BACKSPACE  DEL     │
│  INSERT  HOME  END  │
│  PAGEUP  PAGEDOWN   │
│─────────────────────│
│ 【导航键】          │
│  UP  DOWN  LEFT  RIGHT │
│─────────────────────│
│ 【功能键】          │
│  F1 F2 F3 F4 F5 F6  │
│  F7 F8 F9 F10 F11 F12 │
│─────────────────────│
│ 【符号键】          │
│  PLUS MINUS EQUAL   │
│  COMMA PERIOD SLASH │
│  ...               │
│─────────────────────│
│ 【数字键盘】         │
│  NUMPAD0~9  ...     │
└─────────────────────┘
```

---

## Room 存储

### TypeConverter

```kotlin
class QuickSendConverters {
    @TypeConverter
    fun fromSegmentList(segments: List<ContentSegment>): String {
        return Json.encodeToString(segments)
    }

    @TypeConverter
    fun toSegmentList(json: String): List<ContentSegment> {
        return Json.decodeFromString(json)
    }
}
```

### Database

```kotlin
@Database(entities = [QuickSendEntry::class], version = 1)
@TypeConverters(QuickSendConverters::class)
abstract class QuickSendDatabase : RoomDatabase() {
    abstract fun quickSendDao(): QuickSendDao
}
```

### DAO

主查询直接返回全量数据 (上限 500 条，无需分页):

```kotlin
@Dao
interface QuickSendDao {
    @Query("SELECT * FROM quicksend ORDER BY useCount DESC, updatedAt DESC")
    fun allEntries(): List<QuickSendEntry>

    @Query("SELECT * FROM quicksend WHERE id = :id")
    suspend fun get(id: Long): QuickSendEntry?

    @Insert
    suspend fun insert(entry: QuickSendEntry): Long

    @Update
    suspend fun update(entry: QuickSendEntry)

    @Query("UPDATE quicksend SET useCount = useCount + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementUseCount(id: Long, updatedAt: Long)

    @Query("UPDATE quicksend SET useCount = :count, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateUseCount(id: Long, count: Int, updatedAt: Long)

    @Delete
    suspend fun delete(entry: QuickSendEntry)

    @Query("SELECT COUNT(*) FROM quicksend")
    suspend fun count(): Int
}
```

---

## 边界示例汇总

| segments | sendMode | 执行效果 |
|----------|----------|---------|
| `[type1=CTRL, type1=SHIFT, type1=DEL]` | COMBINATION | 同时按下 Ctrl+Shift+Del |
| `[type1=CTRL, type1=SHIFT, type1=DEL]` | SEQUENCE | 依次 Ctrl↑↓ Shift↑↓ Del↑↓ |
| `[type0=你好世界]` | COMBINATION | commitText("你好世界") |
| `[type0=你好世界]` | SEQUENCE | commitText("你")→...→commitText("界") |
| `[type1=TAB]` | COMBINATION | 单独按下 Tab |
| `[type1=ENTER]` | SEQUENCE | 按下 Enter |
| `[type1=SHIFT, type1=TAB]` | COMBINATION | Shift+Tab (反缩进) |
| `[type1=RALT]` | COMBINATION | 单独按下右 Alt |
| `[type0=#include, type1=ENTER]` | SEQUENCE | 逐个字符 `#include` → 按 Enter |
| `[type0=#include, type1=ENTER]` | COMBINATION | commitText("#include") → 按 Enter |
| `[type1=LALT, type1=TAB]` | COMBINATION | Alt+Tab (窗口切换, 仅左Alt) |
| `[type1=CTRL, type1=PLUS]` | COMBINATION | Ctrl++ (放大) |
| `[type1=CTRL, type1=MINUS]` | COMBINATION | Ctrl+- (缩小) |
