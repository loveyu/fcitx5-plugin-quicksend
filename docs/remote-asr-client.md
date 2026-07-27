# 远端 ASR 客户端规范（Android）

从本地 Sherpa 模型扩展到远端 WebSocket 服务器的多后端优先级调度方案。

## 核心概念：DNS URL

```
ws://10.11.11.123:6008/sherpa?priority=10&auth=token123&codec=pcm&timeout=30
```

| Query 参数 | 类型 | 必填 | 默认 | 说明 |
|------------|------|------|------|------|
| `priority` | `int` | 否（第一个作为默认） | — | 越小越优先；本地模型优先级 = 100；不建议 > 100 |
| `auth` | `string` | 否 | — | 鉴权 Token，以 `Authorization: Bearer <auth>` 头发送 |
| `codec` | `string` | 否 | `pcm` | 音频编码：`pcm` / `opus`（Phase 2） |
| `sample_rate` | `int` | 否 | `16000` | 采样率（暂只支持 16000） |
| `timeout` | `int` | 否 | `30` | 连接/读写超时秒数 |

## 优先级模型

```
优先级值越小 → 优先级越高（10 > 100）

本地模型 prio=100  ──────────────────────────┐
                                              │
远端 ws://10.0.0.1:6008?priority=10 ──── 最高 ├──▶ 排序 → 尝试连接
远端 ws://10.0.0.2:6008?priority=20 ──── 次之 │
                                              │
（本地模型未配置则不参与排序）               ──┘

连接成功 → 使用该后端
连接失败/断开 → 按优先级尝试下一个
```

- 本地模型优先级固定 = 100。
- 同 priority 值时按配置顺序（先配的优先）。
- 优先级**不是排序的第一参数**——URL 在配置列表中的位置决定同 priority 的先后。
- 一般不建议设 >100 的远端（会排在本地模型之后，几乎不会被选中除非本地不可用）。

## 配置存储

`QuickSendPrefs` 新增键：

| Key | 类型 | 默认 | 说明 |
|-----|------|------|------|
| `voice_remote_urls` | `String` | `""` | 换行分隔的 DNS URL 列表 |
| `voice_local_enabled` | `boolean` | `true` | 是否启用本地模型 |

设置页新增"远端服务器"区域，一个多行 EditText，格式：

```
ws://192.168.1.100:6008/sherpa?priority=10&auth=mytoken
wss://asr.example.com:6008/sherpa?priority=20&auth=token2
```

若 URL 不合法（非 ws/wss scheme）→ 设置页 Toast 提示。

## 架构

```
                    ┌─────────────────────────────┐
                    │        QuickSendPrefs        │
                    │  voice_remote_urls           │
                    │  voice_local_enabled         │
                    └─────────────┬───────────────┘
                                  │ 读取
                    ┌─────────────▼───────────────┐
                    │    RemoteServerRegistry      │
                    │  parse URLs → List<Server>   │
                    │  sort by priority            │
                    └─────────────┬───────────────┘
                                  │
         ┌────────────────────────┼────────────────────────┐
         │                        │                        │
  ┌──────▼──────┐         ┌──────▼──────┐         ┌──────▼──────┐
  │ LocalSherpa │         │ RemoteSherpa│         │ RemoteSherpa│
  │ Recognizer  │         │ Recognizer  │         │ Recognizer  │
  │ (prio=100)  │         │ (prio=10)   │         │ (prio=20)   │
  └─────────────┘         └─────────────┘         └─────────────┘
         │                        │                        │
         └────────────────────────┼────────────────────────┘
                                  │
                    ┌─────────────▼───────────────┐
                    │     SpeechRecognizer        │
                    │  (统一接口，屏蔽后端差异)     │
                    └─────────────────────────────┘
```

## RemoteServerRegistry

```kotlin
data class Server(
    val url: String,           // ws://host:port/path
    val priority: Int,         // 越小越优先
    val auth: String?,         // Token
    val codec: String,         // "pcm"
    val sampleRate: Int,       // 16000
    val timeout: Int           // 30s
)

class RemoteServerRegistry(context: Context) {
    fun servers(): List<Server>   // 解析 prefs，按 priority 排序
    fun hasLocal(): Boolean       // 本地模型是否已下载且启用
}
```

## 选择器：RecognizerSelector

```kotlin
class RecognizerSelector(
    private val registry: RemoteServerRegistry,
    private val localFactory: () -> SherpaRecognizer?,
    private val remoteFactory: (Server) -> RemoteSherpaRecognizer
) {
    suspend fun select(): SpeechRecognizer

    // 逻辑：
    // 1. 收集候选列表（同上）
    // 2. 按 priority 排序
    // 3. 依次尝试连接：
    //    - 连接成功 → 返回此 Recognizer
    //    - 连接失败/超时/503 busy/401 → 记录失败原因，尝试下一个
    // 4. 全部失败 → 抛异常（VoiceController 置 Error）
}
```

`VoiceOverlayService.startVoice()` 改为：
1. 创建 `RecognizerSelector`
2. `selector.select()` 获取 `SpeechRecognizer`
3. 传给 `VoiceController`（VoiceController 不再关心本地/远端）

## RemoteSherpaRecognizer（实现 SpeechRecognizer）

```kotlin
class RemoteSherpaRecognizer(
    private val server: Server
) : SpeechRecognizer {

    override val events: Flow<RecognitionEvent>

    suspend fun connect()           // 建立 WS 连接 + 鉴权
    override suspend fun start()    // 发送 {"type":"start"}，开始接收 Partial
    override suspend fun stop()     // 发送 {"type":"finish"}，等待 Final 后关闭
    override suspend fun cancel()   // 直接关闭 WS（不 flush）
}
```

### 内部状态机

```
Idle ──connect()──▶ Connecting ──鉴权通过──▶ Ready
                       │                        │
                       │ 鉴权失败                 │ start()
                       ▼                        ▼
                      Error               Listening
                                              │
                              ┌── audio binary frames
                              │   ◀── partial/final text frames
                              │
                          stop()/cancel()
                              │
                              ▼
                            Idle (释放连接)
```

### 音频发送

```
AudioRecord ──▶ RemoteSherpaRecognizer ──▶ WS BinaryFrame ──▶ Server

每帧 100ms PCM 16kHz = 3200 bytes，直接 binary send（无额外编码）
```

### 断线重连与自动降级

- RemoteSherpaRecognizer 检测到 WS `onFailure` / `onClosing` / 503 busy：
  1. 发送 `RecognitionEvent.Error`（非 fatal，通知 UI）
  2. `VoiceController` 捕获 → 切换状态为 Error
  3. 用户点"继续" → VoiceOverlayService 重新调用 `selector.select()`
  4. selector 从当前失败的 server **之后**开始尝试（跳过已失败的，降级到下一优先级）
  5. 若全部远端失败 → 尝试本地模型；本地成功则无缝恢复

## 连接超时与心跳

- `timeout` 参数控制 OkHttp 的 `connectTimeout` / `readTimeout`。
- 连接成功后每 30 秒发送 `{"type":"ping"}` 心跳。
- 30 秒未收到任何帧（含 pong）→ 视为断开，触发降级。

## 设置页变更

新增区域"远端服务器"：

```
┌─────────────────────────────────────┐
│ 远端服务器                           │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ ws://10.0.0.1:6008/sherpa?     │ │
│ │   priority=10&auth=mytoken     │ │
│ │                                 │ │
│ │ wss://asr.example.com:6008/    │ │
│ │   sherpa?priority=20           │ │
│ └─────────────────────────────────┘ │
│                                     │
│ [?] 每行一个 URL，换行分隔。          │
│     优先级数值越小越优先。            │
│     本地模型优先级=100。              │
└─────────────────────────────────────┘
```

多行 EditText + `?` 帮助图标（点击弹 dialog 说明 DNS URL 格式和参数）。

新增开关"启用本地模型"（默认开启），关闭后本地模型不参与优先级排序。

## 任务总结

| 阶段 | 任务 | 预估量 |
|------|------|--------|
| Phase 1 | `RemoteServerRegistry` + DNS URL 解析 | ~100 行 |
| Phase 1 | `RecognizerSelector` 优先级选择 | ~80 行 |
| Phase 1 | `RemoteSherpaRecognizer` WS 客户端 | ~250 行 |
| Phase 1 | VoiceOverlayService/VoiceController 适配 | ~50 行 |
| Phase 1 | 设置页 UI（多行 EditText + 本地开关） | ~80 行 |
| Phase 2 | 断线重连 + 心跳 + 超时 | ~100 行 |
| Phase 2 | Opus 编码（可选） | ~150 行 + 依赖 |

总计 ~550 行 Phase 1 + ~250 行 Phase 2 = ~800 行 Kotlin。
