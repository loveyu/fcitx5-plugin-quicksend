# 语音子系统

基于 Sherpa-ONNX 的**本地流式中文识别**，浮层驱动，识别结果跨进程注入 host 输入框。

## 启动与生命周期

```
host 语音按钮
   └─▶ startForegroundService(VoiceOverlayService, action=START)
          VoiceOverlayService（前台服务，foregroundServiceType=microphone，满足 Android 14 后台录音）
            1) onCreate → startForegroundCompat（建通知渠道 + 前台通知）
            2) onStartCommand(START) → 绑定 host IQuickSendService（与 QuickSendOverlayService 同 action）
            3) showOverlay() → 弹浮层（标题/关闭/实时文本/状态/[暂停/退格/完成]按钮）
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
| `Initializing` | 模型首次加载或流/录音创建中 | — |
| `Listening` | 已开始录音，尚无结果 | — |
| `Partial(text)` | 流式中间结果 | `setComposingText(text)`（写组合区） |
| `Paused(text)` | 暂停录音，保留 stream、组合区文本与叠加层显示 | — |
| `Finishing` | 正在收尾 | — |
| `Error(msg)` | 出错 | — |
| `NotReady` | 模型文件未下载 | — |

- `start()`：首次调用时经 `SherpaModelHolder.getOrLoad(modelDir, names, config)` 获取共享 `OnlineRecognizer`（按 config 签名缓存，参数变更时自动释放并重载）；建 `SherpaRecognizer` 走 `Dispatchers.IO`；失败置 `Error`。若当前为 `Paused` 则直接 `resumeRecording()`（零延迟恢复）。
- `pause()`：`recognizer.pauseRecording()`，停止 AudioRecord 但保留 `OnlineStream` 不销毁；**不清空**组合区文本，状态变为 `Paused`。
- `finish()`：触发 `recognizer.stop()`（flush 最终结果），会话结束。
- `close()`：`recognizer.cancel()`（丢弃）并 `setComposingText("")` 清空组合区，会话结束。
- `backspace()`：**流式阶段**递增 `partialBackspaceOffset`，从 `rawPartialText` 末尾逐字截断，重发 `setComposingText(adjusted)` 同步更新叠加层与输入框组合区。**提交后**受 `committedVoiceCharCount` 追踪，发送 `KEYCODE_DEL`（每次删一个语音提交的字符）。
- `destroy()`：`collectJob.cancel()` + `recognizer.releaseNow()`（同步强制释放 stream/AudioRecord，不释放共享模型）+ `scope.cancel()`。
- **Final 路径**：`RecognitionEvent.Final` → 先经 `refiner.refine`（默认 `NoOpRefiner` 原样返回）→ `commitText(refined, -1)` → 成功后 `committedVoiceCharCount += refined.length`。
- 退格仅删除语音提交的字符，避免误删用户手动输入的内容。
- 所有 IPC 调用都 `withContext(Dispatchers.IO)`：host 端可能把 `setComposingText` 派发到 IMS 主线程而阻塞。

## 识别器：SherpaRecognizer（核心，最容易踩坑）

- **模型不内嵌**：构造函数接受 `OnlineRecognizer`（由 `SherpaModelHolder` 提供），不再自行加载模型文件。
- **录音**：16kHz 单声道 PCM16，每 100ms（`sampleRate/10` ≈ 1600 采样）读一帧；转 float（÷32768）喂 `OnlineStream.acceptWaveform`；`isReady` 则 `decode`；结果变化才发 `Partial`。
- **配置**：由 `SherpaModelHolder` 构建 `OnlineRecognizerConfig` + `OnlineTransducerModelConfig`（encoder/decoder/joiner + tokens），`numThreads=2`，`enableEndpoint=true`（端点/VAD 检测）。
- **暂停/恢复**：`pauseRecording()` 置 `@Volatile paused=true` → 录音循环中停止 AudioRecord 并 sleep(100)；`resumeRecording()` 置 `paused=false` → 重启 AudioRecord 继续同一 `OnlineStream`。**不销毁任何原生对象**。
- **收尾**：`stop` 置 `commitFinal=true` → 循环退出后 `inputFinished()` + 尽力 `decode` → 取 `getResult` 发 `Final`；`cancel` 置 `commitFinal=false` → 直接清理不产结果。
- `releaseNow()` 仅释放 stream + AudioRecord，**不释放共享的 `OnlineRecognizer`**（由 `SherpaModelHolder` 管理）。

### ⚠️ native 线程铁律（防 use-after-free）

**所有 stream/record 原生对象只在唯一的 `nativeThread`（`Thread("sherpa-native")`）上创建、使用、释放。** `start`/`stop`/`cancel`/`releaseNow` 仅翻转 `@Volatile` 标志（`running` / `commitFinal` / `paused`）并 `join` 该线程，**绝不跨线程直接接触原生对象**。

- `runNativeSession(ready)`：在 nativeThread 内建 stream + AudioRecord → 录音循环（含 pause 逻辑）→ 按 `commitFinal` 收尾 → `finally { cleanup() }`（仅在此线程释放 stream/record）。
- `awaitNativeThread()` / `releaseNow()`：`join(2_000)` 等 nativeThread 退出（~100ms 内）；超时仅 warning 不强杀。
- 历史教训：曾因在识别循环仍在 `acceptWaveform/decode` 时释放原生句柄，导致 `acceptWaveform` 处 native SIGSEGV（见 commit `e35f5a0`）。改 native 后若再动这块，务必保持「单线程接触原生对象」不变式。
- 初始化用 `CompletableDeferred<Boolean>`：stream/AudioRecord 建好才 `complete(true)`；`start` 在 `ready.await()` 处等，失败抛 `IllegalStateException`（`VoiceController` 捕获置 `Error`）。

## 模型持有者：SherpaModelHolder（进程级单例）

模型文件加载为 `OnlineRecognizer` 只需一次；`RecognitionConfig` 变更时自动重载：

- `getOrLoad(modelDir, names, config)`：`@Volatile` 快速路径（检查 modelDir + config 签名） → 变更则 `synchronized` 双重检查 + 释放旧模型 + `withContext(Dispatchers.IO)` 重新加载。
- `config.toSignature()` 作为缓存键，包含 7 个参数：`decodingMethod`、`maxActivePaths`、`blankPenalty`、`endpointSilence`、`endpointMaxUtterance`、`numThreads`、`provider`。
- 加载代码 `SherpaModelFiles.resolve()` + `OnlineRecognizerConfig`（含 `EndpointConfig`/`EndpointRule`）+ `new OnlineRecognizer(config)`。
- `release()`：同步释放，仅应在插件进程销毁时调用。
- 共享模型后，`SherpaRecognizer` 的 `cleanup()` / `releaseNow()` 均不触碰此实例。

## 模型管理：VoiceModelManager

- **目录**：应用专用外部目录 `sherpa/zh-large-2025/`（`getExternalFilesDir(null)` 退化到 `filesDir`）。
- **就绪判定**：`SherpaModelFiles.resolve(dir, names)` 能解析出 encoder/decoder/joiner/tokens 四文件即就绪。
- **下载**：`download(context, baseUrl, names, proxy)`
  - URL 以 `.tar.bz2` 结尾 → `downloadAndExtractTarBz2`：下载单文件归档，进度 0-100%（含"解压中…"阶段），用 commons-compress 解 bzip2 + tar，跳过顶层目录提取 4 个模型文件。
  - 否则 → `downloadIndividualFiles`：旧版逐文件下载（兼容 HuggingFace base URL），文件名带 `· n/m` 进度标签。
  - 状态经 `DownloadState` 流（`Idle/Downloading(percent, label)/Ready/Failed`）。
- **默认源**：GitHub Releases `k2-fsa/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2`；设置页可改 URL（切镜像/旧模型）。
- `cancel()` 取消下载并回 `Idle`；`delete()` 删模型文件（含 `.part`）；`refresh()` 在设置页 `onResume` 刷新就绪态。
- 文件名（encoder/decoder/joiner/tokens）默认 zh Large int8 2025-06，可在设置页改 —— `QuickSendPrefs` 的 `VOICE_NAME_*` 键。

## 网络/代理

- `ProxyConfig.fromUri(uri)`：单 URI 字符串解析为代理结构。`http(s)://` → HTTP 代理；`socks/socks4/socks5://` → SOCKS 代理；支持 `user:pass@host:port`；空串/非法 → `NONE`。默认端口 HTTP=8080、SOCKS=1080。
- `VoiceHttp.client(proxy)`：按 `ProxyConfig` 构 OkHttp（仅用于模型下载；识别是本地、不走网络）。
- 旧版多字段代理键（`QuickSendPrefs.VOICE_PROXY_*_LEGACY`）仅用于迁移到 `VOICE_PROXY_URI`，新代码不再写入。

## 日志

`VoiceLog`（`VoiceLog.init` 在 `PluginApplication.onCreate` 调用）统一打点到应用专用外部目录文件，设置页可经 `FileProvider`（`${applicationId}.fileprovider`，路径见 `res/xml/file_paths.xml`）分享调试日志。Tag 例：`VoiceCtrl` / `SherpaRec` / `VoiceOverlay` / `VoiceModel`。

## 识别参数：RecognitionConfig

设置页"识别参数"区域提供 7 个可调参数，储存在 `QuickSendPrefs` 的 `VOICE_*` 键中，由 `VoiceOverlayService.startVoice()` 读取构造 `RecognitionConfig` 传给 `SherpaModelHolder`：

| 参数 | prefs key | 默认值 | 说明 |
|------|-----------|--------|------|
| `decodingMethod` | `voice_decoding_method` | `greedy_search` | 解码方式：`greedy_search` 最快；`modified_beam_search` 多路径搜索更准且支持热词 |
| `maxActivePaths` | `voice_max_active_paths` | `4` | 仅 beam search 生效，越大越准越慢 |
| `blankPenalty` | `voice_blank_penalty` | `0.0` | blank 惩罚系数：>0 降低漏字，<0 减少多字 |
| `endpointSilence` | `voice_endpoint_silence` | `1.2` | 端点静音阈值（秒），检测到语音后连续静音超此值自动结束 |
| `endpointMaxUtterance` | `voice_endpoint_max_utter` | `20.0` | 单句最长时长（秒），超时强制结束 |
| `numThreads` | `voice_num_threads` | `2` | ONNX 推理线程数，建议不超过大核数 |
| `provider` | `voice_provider` | `cpu` | 推理后端：`cpu`（稳定）或 `xnnpack`（部分设备更快） |

- 每个参数旁有 **?** 图标，点击弹出说明对话框（参数描述 + 默认值 + 推荐值）。
- "重置默认"按钮同步清除识别参数 prefs。
- 参数变更后需要重新触发模型加载（`SherpaModelHolder` 按 `config.toSignature()` 检测差异并自动重载）。

## 扩展点（占位，待实现）

- `RecognizerProvider`：Phase 1 仅本地 Sherpa；在线 Provider（OpenAI / Deepgram / Google / Azure / 阿里云 / 腾讯云 / 自定义 WebSocket）留空。
- `TextRefiner`：Phase 1 仅 `NoOpRefiner`；大模型润色（自动标点 / 去口头禅 / 数字转换 / 中英空格等）留空。
- `SpeechRecognizer`：识别器抽象接口（`events: Flow<RecognitionEvent>` + `start/stop/cancel`），`SherpaRecognizer` 是其实现。
