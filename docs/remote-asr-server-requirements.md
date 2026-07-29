# 远端 ASR 服务端健壮性需求

面向远端语音识别（WebSocket）**服务端**的健壮性改进清单。客户端实现见
[`remote-asr-client.md`](remote-asr-client.md)，协议消息沿用其中的
`status / partial / final / error` 约定。

## 背景

线上日志（`app.log`）暴露的远端问题，几乎都源于服务端行为，最终触发客户端
"判远端失败 → 回退本地"，并在历史版本中导致 `makeLocalRecognizer` 崩溃。客户端已做
容错（见末尾"客户端已做的兼容"），但根因仍需服务端配合。

## 现状问题（来自 app.log）

| 现象 | 根因 | 影响 |
|------|------|------|
| `server error: idle timeout (fatal=true)` | 服务端把"空闲超时"标为致命错误 | 用户说话停顿即被判远端不可用，误回退本地 |
| `ws failure: null`（`EOFException`） | 连接被对端异常关闭（未发 close 帧） | 客户端无法区分"正常结束"与"链路故障" |

## 服务端需求

### R1. idle / 超时不得标 `fatal`
- 用户说话间停顿是常态，空闲超时属于**业务级结束**，不是服务端不可用。
- 空闲超时应：发送 `final`（含已识别文本，可为空）正常结束本轮，或发可恢复的
  非 fatal 提示后软关闭；**不得** `fatal=true`。
- `fatal=true` 仅保留给服务端真正不可用的场景：模型加载失败、内部异常、鉴权失败等。

### R2. 错误分类与可重试标记
- `error` 消息应结构化，新增 `code` 与 `retry` 字段，便于客户端区分处理：
  ```json
  {"type":"error","code":"idle","message":"idle timeout","fatal":false,"retry":true}
  ```
- 建议 `code` 取值：`idle` / `connection` / `auth` / `internal` / `overload`。
- `fatal` 语义对齐：仅 `connection`（链路断）、`auth`、`internal` 为 fatal；
  `idle`、`overload` 等可恢复。

### R3. 连接稳定性
- 避免裸断连（直接 TCP/RST 导致客户端 `EOFException`）；尽量先发 `close` 帧。
- 支持 WebSocket `ping/pong` 心跳探活，主动检测并清理半开连接。
- 异常断连前尽量补发一次 `final`，避免本轮已识别内容丢失。

### R4. 空闲阈值合理且可配
- idle 阈值不应过短（说话间正常停顿不应触发）；建议默认 ≥ 与客户端本地端点
  （`endpointSilence`，默认约 20s 量级）相当的量级。
- 支持客户端在 `start` 时携带 idle 配置（如
  `{"type":"start","idle_seconds":N}`），由服务端按需采纳。

### R5. 鉴权失败明确化
- Token 无效/过期返回 `{"code":"auth","fatal":true}`，客户端据此提示"请检查远端
  Token 配置"，而非静默回退本地。

### R6. 状态机稳定
- `start → listening → partial* → final`（→ 可选 idle 软关）流转稳定；
- 避免重复下发 `ready` / `listening`；`final` 后清理本轮状态，准备接受下一轮
  `start`。

## 客户端已做的兼容（供服务端知晓）

- `RemoteSpeechRecognizer`：收到 `fatal` 且文案含 `idle`/`timeout` 时，按**可恢复超时**
  处理——以最近 `partial` 文本软结束本轮（发 `final`），不再判远端不可用、不回退本地。
- WebSocket `onFailure` → 发 `Error` 事件 → 客户端在本地模型就绪时自动回退本地。
- `VoiceOverlayService` 协程作用域加 `CoroutineExceptionHandler`：任何未捕获异常
  记日志 + Toast，不使进程崩溃、不致整个语音 UI 挂掉。

> 服务端落地 R1/R2 后，客户端的 `isRecoverableTimeout` 文案兜底可改为按 `code` 判断，
> 文档保留兜底逻辑作为旧服务端的兼容降级。
