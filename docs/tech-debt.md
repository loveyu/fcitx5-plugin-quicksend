# 技术债与历史坑（活文档）

> 已知问题、历史踩坑、设计权衡的集中记录。**活文档**——修复或情况变化后更新，新增坑及时补录。
> 每条尽量附：**现象 / 根因 / 处理 / 位置 / 参考**。目的是让后续开发（含 AI）不重蹈覆辙。
> 语音管线细节见 [voice-subsystem.md](voice-subsystem.md)；组件与 IPC 见 [architecture.md](architecture.md)；高频操作速查见 [ai-dev-playbook.md](ai-dev-playbook.md)。

## 已修复的历史坑

### 1. native SIGSEGV（use-after-free）→ native 线程铁律

- **现象**：本地识别过程中，`acceptWaveform` 处触发 native SIGSEGV 崩溃。
- **根因**：识别循环仍在使用 stream/AudioRecord 等原生对象时，另一条线程释放了原生句柄（use-after-free）。
- **处理**：确立「**所有原生对象只在唯一的 `nativeThread` 上创建、使用、释放**」不变式。`stop`/`cancel`/`releaseNow` 仅翻转 `@Volatile` 标志（`running`/`commitFinal`/`paused`）并 `join` nativeThread，**绝不跨线程直接接触原生对象**。
- **位置**：`SherpaRecognizer`（`runNativeSession` / `awaitNativeThread` / `releaseNow`）。
- **参考**：commit `e35f5a0`；[voice-subsystem.md](voice-subsystem.md) §native 线程铁律。
- **⚠️ 改 native 相关代码前必读**：保持单线程接触原生对象不变式，否则 SIGSEGV 必现。

### 2. 切换模式初始化真空期（本次修复）

- **现象**：语音浮层点顶部「强制本地」从网络切本地，若本地模型首次加载，切换后数秒内仍显示 `[N]正在聆听`，实际网络已断、本地仍在加载，用户误以为网络还在识别。
- **根因**：`switchToLocalMode` 流程为 `voiceMode=LOCAL → teardownCurrentController()（cancel collectJob，UI 不再被 state 驱动）→ IO 线程加载模型（首次数秒）→ 加载完才 createAndStartController`。teardown 后到新 controller 接管前是 UI 真空期，状态文本冻结在切换前的 `[N]正在聆听`；且模型加载被提前到 controller 创建之前，`VoiceController.start()` 的 `Initializing` 状态一闪而过，用户看不到。初始本地启动（`launchRecognizer` 路径）无此问题——其模型加载在 `start()` 内部、`Initializing` 之后。
- **处理**：`switchToLocalMode` 与 `switchToRemoteMode` 在 `teardownCurrentController()` 之后**立即** `runOnUiThread { updateUi(VoiceUiState.Initializing) }`，显示 `[L]/[NL]/[N] 正在初始化…` 直到新 controller 的 state 接管。
- **位置**：`VoiceOverlayService.switchToLocalMode` / `switchToRemoteMode`。
- **参考**：[ai-dev-playbook.md](ai-dev-playbook.md) §3。

### 3. 远端鉴权 / 满载静默回退本地 → 不静默回退

- **现象**：远端 Token 错误 / 过期或服务端 503 满载时，旧逻辑一律静默回退本地，用户无感知，误以为本地正常工作。
- **根因**：远端错误未分类，统统计为「远端不可用 → 回退本地」。
- **处理**：引入 `ErrorKind`（`RemoteAuth`/`RemoteOverload`/`Generic`）。`RemoteSpeechRecognizer.classifyFailure` 依 HTTP 状态码（401/503）与服务端 JSON `code` 字段（`auth`/`overload`）分类；`VoiceOverlayService.updateUi(Error)` 对 `RemoteAuth`/`RemoteOverload` **不回退**，给明确文案 + Toast；仅 `Generic`（TCP 不通等）自动回退本地（`[NL]`，红色 N）。用户仍可点「强制本地」手动切换。同一 `RemoteAsrException` 既被 `start()` 抛出、又经事件通道下发，确保两路分类一致无竞态。
- **位置**：`RecognitionEvent.kt`（`ErrorKind`/`RemoteAsrException`）、`RemoteSpeechRecognizer.classifyFailure` / `onFailure`、`VoiceOverlayService.updateUi`。
- **参考**：commit `37a9f0b`。

### 4. 「完成」按钮线程阻塞主线程

- **现象**：点语音「完成」按钮时 UI 卡顿。
- **根因**：`stop()` 内 `join` native 线程是阻塞调用，而 `VoiceController.finish()`/`close()` 在主线程发起，直接 `join` 会卡 UI。
- **处理**：`awaitNativeThread()` 用 `withContext(Dispatchers.IO)` 包裹 `join(2_000)`（本地 Sherpa 与远端 RemoteASR 一致）；超时仅 warning 不强杀。
- **位置**：`SherpaRecognizer.awaitNativeThread` / `RemoteSpeechRecognizer.awaitNativeThread`。
- **参考**：commit `37a9f0b`。

### 5. R8 混淆致本地模型加载失败

- **现象**：release 构建开启 R8 混淆后，本地 Sherpa 模型加载失败。
- **根因**：Sherpa-ONNX native 反射 / JNI 绑定被混淆破坏。
- **处理**：关闭 R8 混淆修复本地模型加载；同时新增强制本地开关与模型生命周期管理。
- **位置**：`build.gradle.kts`（minify 配置）、`SherpaModelHolder`。
- **参考**：commit `25b6118`。
- **⚠️ 若将来要重新启用混淆**：必须为 Sherpa-ONNX（及 `com.k2fsa.sherpa.onnx.*`）添加完整的 keep 规则并验证本地识别可用，否则必崩。

## 设计权衡（非 bug，改动前需知晓）

- **三路独立绑定 host**：`MainService` 反向绑定（填 `RemoteServiceHolder`）+ `QuickSendOverlayService` / `VoiceOverlayService` 各自绑定。代价是三处 `bindService` 逻辑重复；收益是插件 APK 更新后 host 尚未重连 `MainService` 时，悬浮按钮 / 语音仍可用各自连接。不要为「去重」合并它们。
- **Sherpa AAR 不入库**：`libs/*.aar` gitignore（~40MB），构建期下载。代价是首次 / CI 依赖网络；收益是仓库轻。被墙环境见 [build-and-release.md](build-and-release.md) §Sherpa-ONNX AAR。
- **无 universal APK**：ABI 拆分控单包体积（Sherpa native 库随 ABI 拆开）。
- **模型进程级单例**：`SherpaModelHolder` 持 `OnlineRecognizer` 常驻内存，插件不销毁不释放；`RecognitionConfig` 变更按 `toSignature()` 自动重载。代价是占用内存（数十 MB）；收益是避免每次语音会话重载（~1-5s）。
- **远端失败默认不静默回退**（鉴权/满载）：用「明确提示」换「用户感知」，避免误以为本地正常。
- **Compose 仅限远端设置页**：引入 `androidx.compose.material3` 只为新建的多后端设置页（列表 + 抽屉 + 拖拽）。`PluginActivity`/`VoiceSettingsActivity`/`AppearanceActivity`/`LogSettingsActivity`/`EditEntryDialog` 与两个悬浮模块仍用 XML/编程式 View，未迁移。代价是两套 UI 栈并存；收益是远端页复杂交互快速落地、零回归风险。
- **拖拽排序原生实现**：远端列表用 `detectDragGesturesAfterLongPress` + 固定行高 + `graphicsLayer` 位移自实现，不引入第三方 reorderable 库。代价是无自动滚动（后端数量少，可接受）；收益是零新增依赖、镜像源不受影响。
- **腾讯签名自带 Base64**：`TencentV2Signing` 自带 RFC4648 base64 而非用 `android.util.Base64`（minSdk 24）/`java.util.Base64`（API 26）。代价是多 ~20 行代码；收益是纯 JVM 可单测、避开两个 Base64 的 API 版本两难。

## 已知待办 / 待观察

- **远端 ASR 已多后端化**（本次重构）：原单后端 `RemoteSpeechRecognizer` + 三键 prefs（`voice_remote_enabled/url/token`，**已丢弃不迁移**）改为 `RemoteBackend`（sealed：streaming-asr-server / tencent-asr-v1 / tencent-asr-v2）+ `RemoteBackendStore`（JSON 数组）+ 链式回退。V1（通用引擎，`16k_zh`）与 V2（仅大模型引擎）同址同签名，公共字段抽到 `TencentAsrBackend` 接口。`voice-subsystem.md` §远端 ASR / §扩展点 已同步。[voice-subsystem.md](voice-subsystem.md) §扩展点 的「过时」待办随之关闭。
- **⚠️ Final = 会话结束，多句必须累积**（腾讯 V1/V2 踩坑）：`VoiceController.handle(Final)` 提交后必 `endSession()`→`stopSelf`，且 `stop()` 只下发一次 Final。早期 V2 实现每句 `sentence_type==1` 就 `trySend(Final)+markFinal`，导致**首句即终止会话 + stop 重复提交**（V2 因 4004 未跑通没暴露）。修正：稳态句只 `appendStable` 累积到 `stableText` 并发 Partial，会话结束（`final==1`/超时软结束）才 `markFinal` 一次性提交，与 Sherpa/streaming-asr-server「单 Final」语义对齐。详见 [voice-subsystem.md](voice-subsystem.md) §远端 ASR 的「Final 铁律」。
- **无测试源码**：`src/test` / `src/androidTest` 为空壳（仅声明 junit）。已补 `TencentV2SigningTest`（V2 签名串格式 + HMAC-SHA1/Base64 与 JDK 对拍）与 `TencentAsrV1SigningTest`（V1 文档示例签名串格式 + filter_punc 字典序）。`SendActionBuilder`（纯算法）、`ProxyConfig.fromUri`、`KeyNameMapping` 等仍适合补单测。
- **README/文档历史引用**：设计期文档（`docs/fcitx5-plugin-quicksend/*`）已删除，引用已于本次清理。
