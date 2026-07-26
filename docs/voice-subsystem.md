# 语音子系统

基于 Sherpa-ONNX 的**本地流式中文识别**，浮层驱动，识别结果跨进程注入 host 输入框。

## 启动与生命周期

```
host 语音按钮
   └─▶ startForegroundService(VoiceOverlayService, action=START)
          VoiceOverlayService（前台服务，foregroundServiceType=microphone，满足 Android 14 后台录音）
            1) onCreate → startForegroundCompat（建通知渠道 + 前台通知）
            2) onStartCommand(START) → 绑定 host IQuickSendService（与 QuickSendOverlayService 同 action）
            3) showOverlay() → 弹浮层（标题/关闭/实时文本/状态/完成·停止按钮）
            4) evaluateAndStart():
                 缺 RECORD_AUDIO → 提示并给「去设置」按钮
                 模型未就绪        → 提示并给「去设置」按钮
                 否则              → startVoice() 建 VoiceController 并 ctrl.start()
          会话结束（onSessionEnd）→ mainHandler.post { stopSelf() }
```

- 启动由 host 触发；本服务 `exported=true` 且 `permission=${fcitxAppId}.permission.PLUGIN`（签名级），仅同签名 host 可拉起。
- `onDestroy` 顺序：`controller.destroy()` → 移除浮层 → 解绑 host → `scope.cancel()` → `stopForeground`。

## 编排：VoiceController

`VoiceController` 串起识别器与文本注入，`VoiceUiState` 流驱动浮层 UI：

| 状态 | 含义 | 文本注入 |
|------|------|----------|
| `Idle` | 空闲/会话结束 | — |
| `Listening` | 已开始录音，尚无结果 | — |
| `Partial(text)` | 流式中间结果 | `setComposingText(text)`（写组合区） |
| `Finishing` | 正在收尾 | — |
| `Error(msg)` | 出错 | — |
| `NotReady` | 模型未就绪 | — |

- `start()`：先 `VoiceModelManager.isReady` 校验模型；建 `SherpaRecognizer`，`collect` 其 `events` 流；识别器初始化（加载模型/AudioRecord）较重，放 `Dispatchers.IO` 调 `rec.start()`，失败置 `Error`。
- `finish()`：触发 `recognizer.stop()`（flush 最终结果）。
- `cancel()`：`recognizer.cancel()`（丢弃）并 `setComposingText("")` 清空组合区。
- `destroy()`：`collectJob.cancel()` + `recognizer.releaseNow()`（同步强制释放，幂等）+ `scope.cancel()`。
- **Final 路径**：`RecognitionEvent.Final` → 先经 `refiner.refine`（默认 `NoOpRefiner` 原样返回）→ `commitText(refined, -1)`。
- 所有 IPC 调用都 `withContext(Dispatchers.IO)`：host 端可能把 `setComposingText` 派发到 IMS 主线程而阻塞。

## 识别器：SherpaRecognizer（核心，最容易踩坑）

- **录音**：16kHz 单声道 PCM16，每 100ms（`sampleRate/10` ≈ 1600 采样）读一帧；转 float（÷32768）喂 `OnlineStream.acceptWaveform`；`isReady` 则 `decode`；结果变化才发 `Partial`。
- **配置**：`OnlineRecognizerConfig` + `OnlineTransducerModelConfig`（encoder/decoder/joiner + tokens），`numThreads=2`，`enableEndpoint=true`（端点/VAD 检测）。
- **收尾**：`stop` 置 `commitFinal=true` → 循环退出后 `inputFinished()` + 尽力 `decode` → 取 `getResult` 发 `Final`；`cancel` 置 `commitFinal=false` → 直接清理不产结果。

### ⚠️ native 线程铁律（防 use-after-free）

**所有原生对象（`OnlineRecognizer` / `OnlineStream` / `AudioRecord`）只在唯一的 `nativeThread`（`Thread("sherpa-native")`）上创建、使用、释放。** `start`/`stop`/`cancel`/`releaseNow` 仅翻转 `@Volatile` 标志（`running` / `commitFinal`）并 `join` 该线程，**绝不跨线程直接接触原生对象**。

- `runNativeSession(ready)`：在 nativeThread 内建对象 → 录音循环 → 按 `commitFinal` 收尾 → `finally { cleanup() }`（仅在此线程释放原生句柄）。
- `awaitNativeThread()` / `releaseNow()`：`join(2_000)` 等 nativeThread 退出（~100ms 内）；超时仅 warning 不强杀。
- 历史教训：曾因在识别循环仍在 `acceptWaveform/decode` 时释放原生句柄，导致 `acceptWaveform` 处 native SIGSEGV（见 commit `e35f5a0`）。改 native 后若再动这块，务必保持「单线程接触原生对象」不变式。
- 初始化用 `CompletableDeferred<Boolean>`：模型/AudioRecord 建好才 `complete(true)`；`start` 在 `ready.await()` 处等，失败抛 `IllegalStateException`（`VoiceController` 捕获置 `Error`）。

## 模型管理：VoiceModelManager

- **目录**：应用专用外部目录 `sherpa/zh-14m/`（`getExternalFilesDir(null)` 退化到 `filesDir`）。
- **就绪判定**：`SherpaModelFiles.resolve(dir, names)` 能解析出 encoder/decoder/joiner/tokens 四文件即就绪。
- **下载**：`download(context, baseUrl, names, proxy)` —— OkHttp 先 HEAD 汇总各文件大小（不可得则进度不可知），逐文件流式写到 `*.part`，完成后 rename；全部成功后校验并置 `Ready`/`Failed`。状态经 `DownloadState` 流（`Idle/Downloading(percent)/Ready/Failed`）。
- **默认源**：HuggingFace `csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`；用户可在设置页改镜像 base URL。
- `cancel()` 取消下载并回 `Idle`；`delete()` 删模型文件（含 `.part`）；`refresh()` 在设置页 `onResume` 刷新就绪态。
- 文件名（encoder/decoder/joiner/tokens）默认 zh 14M int8，可在设置页改 —— `QuickSendPrefs` 的 `VOICE_NAME_*` 键。

## 网络/代理

- `ProxyConfig.fromUri(uri)`：单 URI 字符串解析为代理结构。`http(s)://` → HTTP 代理；`socks/socks4/socks5://` → SOCKS 代理；支持 `user:pass@host:port`；空串/非法 → `NONE`。默认端口 HTTP=8080、SOCKS=1080。
- `VoiceHttp.client(proxy)`：按 `ProxyConfig` 构 OkHttp（仅用于模型下载；识别是本地、不走网络）。
- 旧版多字段代理键（`QuickSendPrefs.VOICE_PROXY_*_LEGACY`）仅用于迁移到 `VOICE_PROXY_URI`，新代码不再写入。

## 日志

`VoiceLog`（`VoiceLog.init` 在 `PluginApplication.onCreate` 调用）统一打点到应用专用外部目录文件，设置页可经 `FileProvider`（`${applicationId}.fileprovider`，路径见 `res/xml/file_paths.xml`）分享调试日志。Tag 例：`VoiceCtrl` / `SherpaRec` / `VoiceOverlay` / `VoiceModel`。

## 扩展点（占位，待实现）

- `RecognizerProvider`：Phase 1 仅本地 Sherpa；在线 Provider（OpenAI / Deepgram / Google / Azure / 阿里云 / 腾讯云 / 自定义 WebSocket）留空。
- `TextRefiner`：Phase 1 仅 `NoOpRefiner`；大模型润色（自动标点 / 去口头禅 / 数字转换 / 中英空格等）留空。
- `SpeechRecognizer`：识别器抽象接口（`events: Flow<RecognitionEvent>` + `start/stop/cancel`），`SherpaRecognizer` 是其实现。
