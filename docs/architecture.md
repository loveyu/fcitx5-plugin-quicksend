# 架构

fcitx5-android 插件协议下的独立 APK。两个功能（QuickSend / 语音）都经 AIDL IPC 跨进程驱动 host。

## 插件协议入口

- `AndroidManifest.xml` 声明 `<meta-data android:name="org.fcitx.fcitx5.android.plugin" android:resource="@xml/plugin"/>`，`plugin.xml` 给出 `apiVersion` / `domain=quicksend` / `hasService=true`。
- `${fcitxAppId}` 占位符按 buildType 注入：debug=`org.fcitx.fcitx5.android.debug`，release=`org.fcitx.fcitx5.android`（见 `build.gradle.kts` 的 `manifestPlaceholders` 与 `FCITX_APP_ID` BuildConfig）。
- `PluginActivity` 响应 `${fcitxAppId}.plugin.MANIFEST`，是 host 「插件管理」里的设置入口。
- `MainService` 响应 `${fcitxAppId}.plugin.SERVICE`，权限 `${fcitxAppId}.permission.PLUGIN`（host 授予）。

## 双向 IPC 绑定

```
host (fcitx5-android)                         插件 (本 APK)
─────────────────────                         ────────────────
bindService(.plugin.SERVICE, perm.PLUGIN) ──▶ MainService.onBind()
                                                  │ 1) OverlayRestarter.startIfEnabled()
                                                  │ 2) 反向 bindService("${fcitxAppId}.quicksend.IPC",
                                                  │      perm.IPC=signature) ──▶ host IQuickSendService
                                                  │ 3) RemoteServiceHolder.service = proxy
                                                  ▼
                                          返回 Messenger(主线程 Handler).binder 给 host
```

- `IQuickSendService` / `IInputWindowStateListener` 的 AIDL 在插件 `src/main/aidl/org/fcitx/fcitx5/android/common/ipc/`，包名/接口**必须与 host 侧同名同包**（binder 按 descriptor 匹配）。
- `IQuickSendService` 方法：`commitText(text,cursor)` / `sendKeyDownUpKey(keyCode,metaState)` / `sendKeyCombination(keyCode,alt,ctrl,shift,meta)` / `setComposingText` / `finishComposingText` / `register|unregisterInputWindowStateListener`。**全部由 host 实现；host 未实现则远程调用失败**（`QuickSendExecutor` 捕获并记 warning）。

### 谁绑定 host？三路独立连接

`RemoteServiceHolder`（由 `MainService` 反向绑定填入）只是其中一路。`QuickSendOverlayService` 和 `VoiceOverlayService` 各自独立 `bindService(...quicksend.IPC)`，**不依赖 `RemoteServiceHolder`**。原因：插件 APK 更新后 host 可能尚未重新绑定 `MainService`，此时悬浮按钮/语音仍要能用各自的连接。`QuickSendExecutor.execute(entry, service)` 默认取 `RemoteServiceHolder.service`，但悬浮窗调用时显式传入自己的连接。

## 组件职责

| 类 | 角色 |
|----|------|
| `PluginApplication` | 进程入口：`QuickSendManager.init`、`VoiceLog.init`、`OverlayRestarter.startIfEnabled` |
| `PluginActivity` | 设置入口；全量条目列表（按 `useCount` 倒序）、增删改、悬浮开关、跳转语音设置 |
| `MainService` | 被 host bind 的插件 Service；反向绑定 host；自带 overlay 自恢复 |
| `RemoteServiceHolder` | 持有 `IQuickSendService` 代理（`@Volatile`）的全局单例 |
| `QuickSendOverlayService` | 软键盘可见时显示边缘按钮；订阅 `IInputWindowStateListener` 控制显隐；点开列表→发送。需 `SYSTEM_ALERT_WINDOW` |
| `VoiceOverlayService` | 语音前台服务（microphone 类型）；绑定 host、校验权限/模型、驱动 `VoiceController`；支持暂停/恢复/退格 |
| `OverlayRestarter` | 解决 APK 更新后系统清除 `QuickSendOverlayService` 重启计划的问题；在进程创建与 MainService 绑定两个时机自恢复 |

### 语音子系统概要

详见 [`voice-subsystem.md`](voice-subsystem.md)。关键组件：

| 类 | 角色 |
|----|------|
| `VoiceController` | 编排识别会话，暴露 `VoiceUiState` 流（`Idle/Initializing/Listening/Partial/Paused/Finishing/Error/NotReady`），管理 compose/text 注入与退格追踪 |
| `SherpaModelHolder` | 进程级单例，`OnlineRecognizer` 只加载一次，插件不销毁则常驻内存 |
| `SherpaRecognizer` | 按预加载模型创建 stream + AudioRecord，暂停/恢复保留 stream 零延迟 |
| `VoiceModelManager` | 模型文件下载/校验（HuggingFace，可换源/代理） |
| `VoiceLog` | 调试日志，2MB 自动轮转 |

## 数据层（QuickSend）

- 实体 `QuickSendEntry`（表 `quicksend`）：`id/label/segments/sendMode/useCount/createdAt/updatedAt`。
  - `sendMode`：`MODE_COMBINATION=0`（一起发送）/`MODE_SEQUENCE=1`（单个发送）。
  - `segments: List<ContentSegment>`，`ContentSegment(type, content)`：`TYPE_TEXT=0`（原文）/`TYPE_KEY=1`（大写规范化键名，如 `CTRL`）。`type=2/3` 预留。
- `QuickSendConverters`：`List<ContentSegment>` ↔ JSON 字符串（`kotlinx.serialization`）。
- `QuickSendDao`：主查询 `allEntries()` 全量、按 `useCount DESC, updatedAt DESC` 排序；`incrementUseCount`/`updateUseCount` 维护计数；`count()` 用于 500 上限校验。
- `QuickSendManager`（`object`，自带 `CoroutineScope`）：`init(context)` 建库；暴露 `items: StateFlow<List<QuickSendEntry>>`；所有写操作后 `reload()`。`add()` 超 500 条返回 false。
- `KeyNameMapping`：键名 ↔ `KeyEvent.KEYCODE_*` 全表 + 别名 + UI 分组（`groups`），含修饰键/控制键/导航键/功能键/符号键/数字键盘/字母数字七大类。映射表直接在 `KeyNameMapping.kt` 代码中定义。

## 发送链路

```
QuickSendEntry.segments
   └─▶ SendActionBuilder.build(segments, sendMode)   // 纯算法，不发送
          COMBINATION: 连续 modifier(type1) 合修饰键列表 → 首个非 modifier(type1) 作主键
                       → 修饰键就位时单字符 type0 也提升为主键([CTRL]c→Ctrl+C) → 其余 type0 合 Text
          SEQUENCE:    type1→KeyPress ; type0→逐字符 Text
          ▼ List<SendAction>
   └─▶ QuickSendExecutor.execute(entry, service?)
          KeyCombination → remote.sendKeyCombination(...)
          KeyPress       → remote.sendKeyDownUpKey(code, 0)
          Text           → remote.commitText(text, -1)
          成功 → QuickSendManager.incrementUse(id)
```

`decodeModifiers` 把修饰键 KEYCODE 列表塌缩为 alt/ctrl/shift/meta 四布尔（忽略左右），配合 `sendKeyCombination` 的布尔参数。

## 悬浮按钮可见性

`QuickSendOverlayService.onCreate` 绑定 host 并 `registerInputWindowStateListener`。host 在 `onWindowShown/onWindowHidden` 时 `oneway` 回调（binder 线程）→ `mainHandler.post` 到主线程 `showButton/hideButton/hideList`。不轮询、不常驻按钮。`onStartCommand` 返回 `START_STICKY` 保活（仅监听，按钮按需显隐）。`ACTION_HIDE` 触发 `stopSelf`。

## UI 层（`ui/`）

- `EditEntryDialog`：段编辑器（FlowLayout + 特殊键分组下拉 `KeyPicker`），`SegmentFormatter` 负责 label/段渲染显示。
- `QuickSendAdapter`：RecyclerView 条目列表。
- `FlowLayout`：横向自动换行布局，承载内容段 chip。
- 资源：`res/values` 与 `res/values-night` 双色，悬浮窗虽是 Service 上下文但随系统 uiMode 切日夜。

## 跨进程文本注入

所有「写进输入框」都走 IPC：普通文本 `commitText`，语音组合态 `setComposingText`/`finishComposingText`。插件进程内不直接持有编辑器。`VoiceController` 把 IPC 调用切到 `Dispatchers.IO`（host 端可能派发到 IMS 主线程阻塞）。
