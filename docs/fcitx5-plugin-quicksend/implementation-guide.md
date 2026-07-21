# 快捷发送插件 - 实现指南

> 基于 fcitx5-android 现有架构分析，确保每一个实现步骤都有据可依。

---

## 1. 存储方案: Room Database

**理由**: 需要 CRUD + 排序（按使用次数倒序）+ 计数器持久化。SharedPreferences 不适合结构化列表数据，文件存储（如 QuickPhrase）不适合排序查询。Room 已有成熟实践（剪贴板 ClipboardDatabase）。

**参考**: `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/db/`

### 1.1 ContentSegment 值类型

```kotlin
// app/src/main/java/org/fcitx/fcitx5/android/data/quicksend/ContentSegment.kt

@Serializable
data class ContentSegment(
    /** 0=普通文本, 1=特殊键, 2=预留, 3=预留 */
    val type: Int,
    /** type=0: 文本原文; type=1: 特殊键规范化名称（如 "CTRL", "SHIFT", "DEL"） */
    val content: String
)
```

使用 `kotlinx.serialization` 的 `@Serializable` 注解，直接通过 `Json.encodeToString()` / `Json.decodeFromString()` 序列化。

### 1.2 实体

```kotlin
// app/src/main/java/org/fcitx/fcitx5/android/data/quicksend/db/QuickSendEntry.kt

@Entity(tableName = "quicksend")
data class QuickSendEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String = "",                   // 显示名称（选填）
    val segments: List<ContentSegment>,        // 内容段列表（JSON 存储）
    val sendMode: Int = 0,                     // 0=COMBINATION, 1=SEQUENCE
    val useCount: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)
```

### 1.3 TypeConverter

```kotlin
// app/src/main/java/org/fcitx/fcitx5/android/data/quicksend/db/QuickSendConverters.kt

class QuickSendConverters {
    @TypeConverter
    fun fromSegmentList(segments: List<ContentSegment>): String =
        Json.encodeToString(segments)

    @TypeConverter
    fun toSegmentList(json: String): List<ContentSegment> =
        Json.decodeFromString(json)
}
```

### 1.4 DAO

```kotlin
// app/src/main/java/org/fcitx/fcitx5/android/data/quicksend/db/QuickSendDao.kt

@Dao
interface QuickSendDao {
    @Query("SELECT * FROM quicksend ORDER BY useCount DESC, updatedAt DESC")
    fun allEntries(): List<QuickSendEntry>  // 全量加载，无分页（上限500条）

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

### 1.5 Database

```kotlin
// app/src/main/java/org/fcitx/fcitx5/android/data/quicksend/db/QuickSendDatabase.kt

@Database(entities = [QuickSendEntry::class], version = 1)
@TypeConverters(QuickSendConverters::class)
abstract class QuickSendDatabase : RoomDatabase() {
    abstract fun quickSendDao(): QuickSendDao
}
```

### 1.6 Manager 单例

**参考**: `ClipboardManager` (`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt`)

```kotlin
// app/src/main/java/org/fcitx/fcitx5/android/data/quicksend/QuickSendManager.kt

object QuickSendManager : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private var db: QuickSendDatabase? = null
    private var dao: QuickSendDao? = null

    private val _items = MutableStateFlow<List<QuickSendEntry>>(emptyList())
    val items: StateFlow<List<QuickSendEntry>> = _items.asStateFlow()

    fun init(context: Context) {
        db = Room.databaseBuilder(context, QuickSendDatabase::class.java, "quicksend_db")
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        dao = db.quickSendDao()
        launch { reload() }
    }

    private suspend fun reload() {
        _items.value = dao?.allEntries() ?: emptyList()
    }

    suspend fun add(segments: List<ContentSegment>, sendMode: Int, label: String = ""): Boolean {
        if (dao!!.count() >= 500) return false
        val now = System.currentTimeMillis()
        dao!!.insert(QuickSendEntry(
            label = label, segments = segments, sendMode = sendMode,
            createdAt = now, updatedAt = now
        ))
        reload()
        return true
    }

    suspend fun update(id: Long, label: String, segments: List<ContentSegment>, sendMode: Int, useCount: Int) {
        dao!!.update(QuickSendEntry(
            id = id, label = label, segments = segments,
            sendMode = sendMode, useCount = useCount,
            updatedAt = System.currentTimeMillis()
        ))
        reload()
    }

    suspend fun incrementUse(id: Long) {
        dao!!.incrementUseCount(id, System.currentTimeMillis())
        reload()
    }

    suspend fun delete(id: Long) {
        dao!!.get(id)?.let { dao!!.delete(it) }
        reload()
    }
}
```

`init()` 在 `FcitxInputMethodService.onCreate()` 中调用。

---

## 2. 特殊键名 ↔ KeyCode 映射表

**参考**: `FcitxKeyMapping`（`app/build/generated/ksp/.../FcitxKeyMapping.kt`）+ `KeyEvent`

type=1 的 `content` 存储大写规范化名称。以下为完整映射（可直接在代码中实现为 `Map<String, Int>`）:

### 修饰键（Modifiers，支持左右区分）

| content 存储值 | KeyEvent | 说明 |
|---|---|---|
| `SHIFT` | `KEYCODE_SHIFT_LEFT` | 默认左 |
| `RSHIFT` | `KEYCODE_SHIFT_RIGHT` | |
| `CTRL` | `KEYCODE_CTRL_LEFT` | 默认左 |
| `RCTRL` | `KEYCODE_CTRL_RIGHT` | |
| `ALT` | `KEYCODE_ALT_LEFT` | 默认左 |
| `RALT` | `KEYCODE_ALT_RIGHT` | |
| `META` | `KEYCODE_META_LEFT` | Win/Super 键，默认左 |
| `RMETA` | `KEYCODE_META_RIGHT` | |

### 控制键

| content 存储值 | KeyEvent |
|---|---|
| `TAB` | `KEYCODE_TAB` |
| `CAPS` | `KEYCODE_CAPS_LOCK` |
| `ESC` | `KEYCODE_ESCAPE` |
| `ENTER` | `KEYCODE_ENTER` |
| `SPACE` | `KEYCODE_SPACE` |
| `BACKSPACE` | `KEYCODE_DEL` |
| `DEL` | `KEYCODE_FORWARD_DEL` |
| `INSERT` | `KEYCODE_INSERT` |
| `HOME` | `KEYCODE_MOVE_HOME` |
| `END` | `KEYCODE_MOVE_END` |
| `PAGEUP` | `KEYCODE_PAGE_UP` |
| `PAGEDOWN` | `KEYCODE_PAGE_DOWN` |
| `PRINTSCREEN` | `KEYCODE_SYSRQ` |
| `SCROLLLOCK` | `KEYCODE_SCROLL_LOCK` |
| `PAUSE` | `KEYCODE_BREAK` |
| `NUMLOCK` | `KEYCODE_NUM_LOCK` |
| `MENU` | `KEYCODE_MENU` |

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

### 符号键（作为按键事件发送）

| content 存储值 | KeyEvent |
|---|---|
| `PLUS` | `KEYCODE_PLUS` |
| `MINUS` | `KEYCODE_MINUS` |
| `EQUAL` | `KEYCODE_EQUALS` |
| `COMMA` | `KEYCODE_COMMA` |
| `PERIOD` | `KEYCODE_PERIOD` |
| `SLASH` | `KEYCODE_SLASH` |
| `ASTERISK` | `KEYCODE_STAR` |
| `SEMICOLON` | `KEYCODE_SEMICOLON` |
| `APOSTROPHE` | `KEYCODE_APOSTROPHE` |
| `GRAVE` | `KEYCODE_GRAVE` |
| `BACKSLASH` | `KEYCODE_BACKSLASH` |
| `LEFTBRACKET` | `KEYCODE_LEFT_BRACKET` |
| `RIGHTBRACKET` | `KEYCODE_RIGHT_BRACKET` |
| `POUND` | `KEYCODE_POUND` |
| `AT` | `KEYCODE_AT` |

### 数字键盘

| content 存储值 | KeyEvent |
|---|---|
| `NUMPAD0` ~ `NUMPAD9` | `KEYCODE_NUMPAD_0` ~ `KEYCODE_NUMPAD_9` |
| `NUMPAD_DIVIDE` | `KEYCODE_NUMPAD_DIVIDE` |
| `NUMPAD_MULTIPLY` | `KEYCODE_NUMPAD_MULTIPLY` |
| `NUMPAD_SUBTRACT` | `KEYCODE_NUMPAD_SUBTRACT` |
| `NUMPAD_ADD` | `KEYCODE_NUMPAD_ADD` |
| `NUMPAD_DOT` | `KEYCODE_NUMPAD_DOT` |
| `NUMPAD_ENTER` | `KEYCODE_NUMPAD_ENTER` |

### 字母/数字（作为按键事件发送）

| content 存储值 | KeyEvent |
|---|---|
| `A` ~ `Z` | `KEYCODE_A` ~ `KEYCODE_Z` |
| `0` ~ `9` | `KEYCODE_0` ~ `KEYCODE_9` |

### 关键辅助函数

```kotlin
object KeyNameMapping {
    // 完整 keyName → KeyEvent KEYCODE 映射
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
        // ... 完整的键名映射
    }

    // 修饰键集合（用于 COMBINATION 模式判断）
    private val modifierKeys: Set<String> = setOf("SHIFT", "RSHIFT", "CTRL", "RCTRL", "ALT", "RALT", "META", "RMETA")

    fun keyCodeOf(name: String): Int? = keyCodeMap[name]

    fun isModifier(name: String): Boolean = name in modifierKeys
}
```

---

## 3. 发送逻辑

### 3.1 段合并与发送动作构建

```kotlin
sealed class SendAction {
    /** 组合键同时按下 */
    data class KeyCombination(val modifiers: List<Int>, val mainKey: Int) : SendAction()
    /** 按下并释放一个键 */
    data class KeyPress(val keyCode: Int) : SendAction()
    /** 提交一段文本 */
    data class Text(val text: String) : SendAction()
}
```

### 3.2 COMBINATION 模式合并

```
遍历 segments 列表:
  收集连续的 modifier type=1 段为 modifiers 列表
  遇到第一个非 modifier 的 type=1 段作为 mainKey
  后续所有 type=0 段合并为一个 Text（保留顺序）
  后续 type=1 段回退为单个 KeyPress

示例:
  [CTRL, SHIFT, DEL]              → KeyCombination([CTRL,SHIFT], DEL)
  [CTRL, PLUS]                    → KeyCombination([CTRL], PLUS)
  [你好世界]                        → Text("你好世界")
  [CTRL, SHIFT, DEL, 确认删除]     → KeyCombination([CTRL,SHIFT], DEL) + Text("确认删除")
  [CTRL, A]                       → KeyCombination([CTRL], A)
  [TAB]                           → KeyCombination([], TAB)
  [CTRL, SHIFT, DEL, BACKSPACE]   → KeyCombination([CTRL,SHIFT], DEL) + KeyPress(BACKSPACE)
```

**执行顺序**:
1. 按下 modifiers 的 down 事件（顺序无关）
2. 按下 mainKey down + mainKey up
3. 逆序释放 modifiers 的 up 事件
4. 依次执行后续 SendAction

### 3.3 SEQUENCE 模式合并

```
遍历 segments 列表，每个段独立转换:
  type=1 且是 modifier → KeyPress
  type=1 且非 modifier → KeyPress
  type=0                → 拆分为逐字符 Text

示例:
  [CTRL, SHIFT, DEL]            → KeyPress(CTRL), KeyPress(SHIFT), KeyPress(DEL)
  [CTRL, PLUS]                  → KeyPress(CTRL), KeyPress(PLUS)
  [你好世界]                      → Text("你"), Text("好"), Text("世"), Text("界")
  [CTRL, SHIFT, DEL, 确认]       → KeyPress(CTRL), KeyPress(SHIFT), KeyPress(DEL), Text("确"), Text("认")
  [Hi]                          → Text("H"), Text("i")
```

### 3.4 发送执行

**参考**: `FcitxInputMethodService.sendCombinationKeyEvents()` (行 494-513), `sendDownKeyEvent()` (行 455), `sendUpKeyEvent()` (行 471)

```kotlin
// FcitxInputMethodService 中新增
fun executeQuickSend(item: QuickSendEntry) {
    when (item.sendMode) {
        0 -> executeCombination(item.segments)
        1 -> executeSequence(item.segments)
    }
    QuickSendManager.incrementUse(item.id)
}

private fun executeCombination(segments: List<ContentSegment>) {
    val actions = buildCombinationActions(segments)
    val eventTime = SystemClock.uptimeMillis()

    for (action in actions) {
        when (action) {
            is SendAction.KeyCombination -> {
                var metaState = 0
                action.modifiers.forEach { code ->
                    metaState = metaState or metaStateOf(code)
                    sendDownKeyEvent(eventTime, code)
                }
                sendDownKeyEvent(eventTime, action.mainKey, metaState)
                sendUpKeyEvent(eventTime, action.mainKey, metaState)
                action.modifiers.reversed().forEach { sendUpKeyEvent(eventTime, it) }
            }
            is SendAction.KeyPress -> {
                sendDownUpKeyEvents(action.keyCode)
            }
            is SendAction.Text -> {
                commitText(action.text)
            }
        }
    }
}

private fun executeSequence(segments: List<ContentSegment>) {
    for (segment in segments) {
        when (segment.type) {
            0 -> segment.content.forEach { char -> commitText(char.toString()) }
            1 -> {
                KeyNameMapping.keyCodeOf(segment.content)?.let { sendDownUpKeyEvents(it) }
            }
        }
    }
}
```

---

## 4. UI: 快捷发送列表窗口

### 4.1 Window 实现

**参考**: `StatusAreaWindow`（RecyclerView 网格布局）、`TextEditingWindow`（简单窗口）

```kotlin
class QuickSendWindow : InputWindow.ExtendedInputWindow<QuickSendWindow>() {
    private val theme by manager.theme()
    private val service: FcitxInputMethodService by manager.must()
    private val windowManager: InputWindowManager by manager.must()

    override val title: String get() = context.getString(R.string.quick_send)

    override fun onCreateView(): View {
        // RecyclerView + LinearLayoutManager
        // 数据: QuickSendManager.items (StateFlow)
        // 每个 item 行:
        //   左侧: label（优先）或 segments 渲染（type=1 带边框高亮）
        //   右侧: 使用次数 + 模式图标
        // 点击 → service.executeQuickSend(item)
    }

    override fun onAttached() { /* 收集 StateFlow */ }
    override fun onDetached() { /* 停止收集 */ }
}
```

### 4.2 工具栏按钮

**参考**: `ButtonsBarUi`、`KawaiiBarComponent`

- `ButtonsBarUi` 新增 `quickSendButton`（图标 `ic_quick_send.xml`）
- `KawaiiBarComponent` 绑定: `quickSendButton.setOnClickListener { windowManager.attachWindow(QuickSendWindow()) }`

---

## 5. 设置页面

### 5.1 导航路由

**参考**: `SettingsRoute`

```kotlin
data object QuickSend : SettingsRoute()
```

在 `SettingsRoute.createGraph()` 中添加 `composable<SettingsRoute.QuickSend> { QuickSendSettingsFragment() }`。

### 5.2 设置页面

自定义 Fragment + RecyclerView，不使用 ManagedPreferenceFragment。

每行显示:
- `label`（优先）或 segments 渲染（type=1 段带边框）
- 发送模式标签
- 使用次数（可点击编辑）
- 删除按钮
- 底部: "添加" 按钮

排序: `useCount DESC`（由 DAO 保证）。

### 5.3 段编辑器（核心 UI）

使用横向 FlowLayout，每个 segment 渲染为一个独立的 View:

```
┌─────────────────────────────────────────────────────┐
│ 显示名称(选填): [_______________________________]   │
│                                                      │
│ 发送内容:                                            │
│ ┌──────────────────────────────────────────────────┐ │
│ │ [CTRL]  [SHIFT]  [DEL]  确认删除           [+特殊键] │ │
│ └──────────────────────────────────────────────────┘ │
│                                                      │
│ 发送模式: ○ 一起发送  ● 单个发送                      │
│ 使用次数: [___3___]                                   │
│                                                      │
│              [取消]              [保存]               │
└─────────────────────────────────────────────────────┘
```

- type=0 段: 直接显示文本，可编辑（点击进入文本编辑模式）
- type=1 段: 带边框 Chip 显示 `[CTRL]`，边框背景色区分于普通文本
- 尾部 `[+特殊键]` 按钮: 点击弹出分组下拉菜单，选中后插入 type=1 段
- 每个段右上方 × 可单独删除
- 键盘输入: 在当前选中的 type=0 段末尾追加字符；如果无选中段或选中 type=1 段，新建 type=0 段

### 5.4 特殊键下拉菜单分组

```
┌──────────────────────┐
│ 【修饰键】            │
│  SHIFT  RSHIFT        │
│  CTRL   RCTRL         │
│  ALT    RALT          │
│  META   RMETA         │
├──────────────────────┤
│ 【控制键】            │
│  TAB  CAPS  ESC       │
│  ENTER  SPACE         │
│  BACKSPACE  DEL       │
│  INSERT  HOME  END    │
│  PAGEUP  PAGEDOWN     │
├──────────────────────┤
│ 【导航键】            │
│  UP  DOWN  LEFT  RIGHT│
├──────────────────────┤
│ 【功能键】            │
│  F1~F12              │
├──────────────────────┤
│ 【符号键】            │
│  PLUS  MINUS  EQUAL   │
│  COMMA  PERIOD  SLASH │
│  ...                 │
├──────────────────────┤
│ 【数字键盘】          │
│  NUMPAD0~9  ...      │
└──────────────────────┘
```

选中后: 在 FlowLayout 当前光标位置插入一个新的 type=1 ContentSegment。

---

## 6. 显示策略

| 条件 | 列表/Widnow 显示 |
|------|----------------|
| `label` 非空 | 直接显示 `label` 文本 |
| `label` 为空 | 遍历 segments: type=0 直接显示原文; type=1 带边框显示键名 `CTRL` |
| 每行右侧 | 使用次数 + 发送模式图标（组合/序列） |

---

## 7. 新增/修改文件清单

### 7.1 新增文件

| 文件路径 | 用途 |
|---------|------|
| `data/quicksend/ContentSegment.kt` | 内容段值类型 |
| `data/quicksend/KeyNameMapping.kt` | 特殊键名 → KeyEvent KEYCODE 映射 |
| `data/quicksend/QuickSendManager.kt` | 数据管理器单例 |
| `data/quicksend/db/QuickSendEntry.kt` | Room 实体 |
| `data/quicksend/db/QuickSendDao.kt` | Room DAO |
| `data/quicksend/db/QuickSendDatabase.kt` | Room Database |
| `data/quicksend/db/QuickSendConverters.kt` | Room TypeConverter |
| `data/quicksend/SendAction.kt` | 发送动作 sealed class + 合并逻辑 |
| `input/quicksend/QuickSendWindow.kt` | IME 列表窗口 |
| `input/quicksend/QuickSendAdapter.kt` | 列表 RecyclerView Adapter |
| `ui/main/settings/quicksend/QuickSendSettingsFragment.kt` | 设置页面 |
| `ui/main/settings/quicksend/QuickSendEditDialog.kt` | 段编辑器对话框 |
| `ui/main/settings/quicksend/SegmentEditorView.kt` | 段编辑器自定义 View (FlowLayout) |
| `res/drawable/ic_quick_send.xml` | 工具栏图标 |
| `res/values/strings.xml` (新增条目) | 字符串资源 |

### 7.2 修改文件

| 文件 | 修改内容 |
|------|---------|
| `input/bar/ui/idle/ButtonsBarUi.kt` | 新增 `quickSendButton` |
| `input/bar/KawaiiBarComponent.kt` | 绑定点击事件 |
| `input/FcitxInputMethodService.kt` | `init QuickSendManager` + `executeQuickSend()` |
| `ui/main/settings/SettingsRoute.kt` | 新增 `QuickSend` 路由 |
| `ui/main/settings/advanced/AdvancedSettingsFragment.kt` | 添加设置入口 |

---

## 8. 关键边界处理

| 场景 | 处理方式 |
|------|---------|
| segments 为空 | 保存时校验拒绝 |
| type=0 content 含换行 | 按原样存储，发送时 commitText 按原样提交 |
| type=1 内容不在映射表中 | 发送时跳过该段（记录日志） |
| 超过 500 条上限 | add() 返回 false，UI 提示 |
| COMBINATION 模式无 modifier | mainKey = 第一个非 modifier 的 type=1 段，或回退为文本 |
| COMBINATION 模式只有 type=0 | 直接合并为 Text 提交 |
| SEQUENCE 模式下 type=0 | 逐字符 commitText |
| 数据库版本升级 | fallbackToDestructiveMigrationOnDowngrade |
| UI 中的长文本截断 | label 或 segments 渲染超 40 字符截断 |

---

## 9. 参考文件速查

| 参考文件 | 关键模式 |
|---------|---------|
| `ClipboardManager.kt` | Room 初始化/CRUD/协程 |
| `ClipboardDatabase.kt` | Room Database |
| `ClipboardEntry.kt` | Room Entity |
| `ClipboardDao.kt` | Room DAO |
| `StatusAreaWindow.kt` | ExtendedInputWindow + RecyclerView |
| `TextEditingWindow.kt` | ExtendedInputWindow 简单窗口 |
| `PickerWindow.kt` | ExtendedInputWindow + ViewPager |
| `ButtonsBarUi.kt` | 工具栏按钮定义 |
| `KawaiiBarComponent.kt` | 按钮事件绑定 |
| `FcitxInputMethodService.kt:494` | sendCombinationKeyEvents() |
| `FcitxInputMethodService.kt:455` | sendDownKeyEvent/sendUpKeyEvent |
| `InputWindow.kt` | Window 基类 |
| `InputWindowManager.kt` | Window 管理器 |
| `SettingsRoute.kt` | 导航路由注册 |
| `FcitxKeyMapping.kt` (generated) | 键名→KeyCode 常量 |
