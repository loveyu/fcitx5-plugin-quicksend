# 远端 ASR 服务端规范（Rust）

基于 Sherpa-ONNX WebSocket 的服务端，接收 PCM 音频流返回流式识别结果。
支持 WS/WSS、Token 鉴权、会话并发控制与排队。

## 技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| HTTP/WS 框架 | `axum` + `axum-extra` | 异步、生态成熟、原生 WS 支持 |
| WS 实现 | `tokio-tungstenite` / `axum::extract::ws` | axum 内置 WS，无需额外依赖 |
| ASR 引擎 | `sherpa-rs` (C FFI) | sherpa-onnx 官方 Rust binding |
| 异步运行时 | `tokio` (multi-thread) | 全链路 async |
| TLS | `rustls` + `axum-server` | WSS 模式，可用自签证书 |
| 配置 | `toml` + `serde` | 命令行参数覆盖配置文件 |
| 日志 | `tracing` + `tracing-subscriber` | 结构化日志，支持 JSON 输出 |

## 配置

### 命令行

```bash
asr-server \
  --bind 0.0.0.0:6008 \
  --tls-cert /path/to/cert.pem \
  --tls-key /path/to/key.pem \
  --model /path/to/sherpa-onnx-streaming-zipformer-zh-xlarge-int8-2025-06-30 \
  --auth-token "my-secret-token" \
  --max-sessions 2 \
  --num-threads 4
```

### 配置项

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `--bind` | `SocketAddr` | `0.0.0.0:6008` | 监听地址 |
| `--tls-cert` | `PathBuf` | 无 | TLS 证书（提供则启用 WSS，否则 WS） |
| `--tls-key` | `PathBuf` | 无 | TLS 私钥 |
| `--model` | `PathBuf` | **必填** | Sherpa 模型目录（含 encoder/decoder/joiner/tokens） |
| `--auth-token` | `String` | 无 | 鉴权 Token（不提供则跳过鉴权） |
| `--max-sessions` | `usize` | `2` | 并发 ASR 会话上限 |
| `--num-threads` | `i32` | CPU 核数 | ONNX 推理线程数 |
| `--decoding-method` | `String` | `greedy_search` | `greedy_search` 或 `modified_beam_search` |
| `--max-active-paths` | `i32` | `4` | beam search 活跃路径数 |
| `--endpoint-silence` | `f32` | `1.2` | 端点静音阈值（秒） |
| `--endpoint-max-utterance` | `f32` | `20.0` | 单句最长时长（秒） |
| `--sample-rate` | `i32` | `16000` | 期望音频采样率（客户端须匹配） |

## 鉴权

客户端通过 HTTP Header 传递 Token：

```
Authorization: Bearer <token>
```

或自定义 Header（客户端 `auth` query 参数中指定 Header 名和值）：

```
X-ASR-Token: <token>
```

- 服务端未配置 `--auth-token` 时，**跳过鉴权**（所有连接直接通过）。
- 配置了 `--auth-token` 时，连接建立后立即校验：不匹配 → HTTP `401 Unauthorized` 并关闭连接。
- 不要求 HTTPS 才能鉴权——WS 明文下 Token 只是简单访问控制，安全性由局域网隔离保证。

## 并发控制：忙则拒绝

```
┌─────────────┐
│  WS 连接 1   │──▶ 鉴权 ──▶ 获取槽位 ──▶ ASR 会话运行中
├─────────────┤
│  WS 连接 2   │──▶ 鉴权 ──▶ 获取槽位 ──▶ ASR 会话运行中
├─────────────┤
│  WS 连接 3   │──▶ 鉴权 ──▶ 槽位已满 ▶ HTTP 503 + 关闭
│                {"error":"busy","message":"All ASR slots occupied"}
├─────────────┤
│  WS 连接 4   │──▶ 鉴权失败 ▶ HTTP 401 + 关闭
└─────────────┘
```

- 槽位用 `tokio::sync::Semaphore` 控制（`max_sessions` 个许可）。
- 鉴权通过但 `try_acquire` 失败 → HTTP `503 Service Unavailable`，body 返回 JSON，立即关闭连接。
- **不排队**。客户端收到 503 后自动降级至下一个优先级的服务端（或本地模型）。
- 客户端主动断开 → 立即释放槽位。

## WebSocket 协议

### 帧格式

| 方向 | 类型 | 帧格式 | 说明 |
|------|------|--------|------|
| Client → Server | 音频 | **Binary** | PCM 16bit LE 单声道，16000Hz |
| Client → Server | 指令 | **Text** (JSON) | 控制命令 |
| Server → Client | 结果 | **Text** (JSON) | Partial / Final / Error |
| Server → Client | 状态 | **Text** (JSON) | ready / listening / pong |

### 客户端 → 服务端

#### 音频帧（Binary）

```
[ i16 LE samples... ]
```

- 采样率 16kHz，单声道，16bit 小端序
- 帧大小不限制（客户端自行决定缓冲策略，建议 100ms = 3200 bytes）
- 服务端内部累积到 100ms 再喂 Sherpa

#### 指令帧（Text JSON）

```json
{ "type": "start" }
```

通知服务端开始新的识别会话（重置 Sherpa stream），收到后发送 `{"type":"status","state":"listening"}`。

```json
{ "type": "finish" }
```

结束当前识别，服务端 flush 最终结果后重置 stream，但**不关闭连接**（连接可复用，下一句直接 `start`）。

```json
{ "type": "ping" }
```

心跳，服务端回复 `{"type":"pong"}`。建议每 30 秒一发，防止中间代理断开空闲连接。

### 服务端 → 客户端

#### 状态帧

```json
{ "type": "status", "state": "ready" }
{ "type": "status", "state": "listening" }
{ "type": "pong" }
```

#### Partial 帧

```json
{
  "type": "partial",
  "text": "今天天气",
  "segment": 0
}
```

#### Final 帧

```json
{
  "type": "final",
  "text": "今天天气真不错",
  "segment": 0,
  "tokens": ["今", "天", "天", "气", "真", "不", "错"],
  "timestamps": [0.0, 0.32, 0.48, 0.64, 0.96, 1.12, 1.36, 1.6]
}
```

#### Error 帧

```json
{
  "type": "error",
  "message": "ASR session error: ...",
  "fatal": false
}
```

- `fatal: true` → 服务端即将关闭连接。

## 会话生命周期

```
Client                        Server
  │                             │
  ├── WS connect ──────────────▶│ 鉴权
  │                             │  （槽满 → 503 关闭）
  │◀── {"type":"status", ──────┤
  │    "state":"ready"}         │
  │                             │
  ├── {"type":"start"} ────────▶│ 创建 Stream
  │◀── {"type":"status", ──────┤
  │    "state":"listening"}     │
  │                             │
  ├── [binary audio] ──────────▶│ acceptWaveform → decode
  │◀── {"type":"partial", ─────┤
  │    "text":"今天"}           │
  │◀── {"type":"partial", ─────┤
  │    "text":"今天天气"}       │
  │       ...                   │
  │                             │
  ├── {"type":"finish"} ───────▶│ inputFinished → drain
  │◀── {"type":"final", ───────┤
  │    "text":"今天天气真不错"} │
  │◀── {"type":"status", ──────┤
  │    "state":"ready"}         │ 重置 stream，等待下一句
  │                             │
  │    （可重复 start →         │
  │     finish 多轮）           │
  │                             │
  ├── WS close ────────────────▶│ 释放槽位
  │                             │
```

## 模型管理

- 服务端启动时加载一次 `OnlineRecognizer`，**全进程共享**。
- 每个会话创建独立 `OnlineStream`（Sherpa 支持多 stream 共享同一模型）。
- 720MB xlarge int8 模型在 16 核 x86 上 RTF ~0.3–0.5×，单流无压力；2 并发需核数充足。

## 部署建议

```bash
# 开发调试（明文 WS）
asr-server --model ./sherpa-onnx-streaming-zipformer-zh-xlarge-int8-2025-06-30

# 生产（WSS + Token）
asr-server \
  --bind 0.0.0.0:6008 \
  --tls-cert /etc/asr/cert.pem \
  --tls-key /etc/asr/key.pem \
  --model /data/models/zh-xlarge \
  --auth-token "$(cat /etc/asr/token)" \
  --max-sessions 2 \
  --num-threads 4
```

systemd unit:

```ini
[Unit]
Description=ASR WebSocket Server
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/asr-server \
  --bind 0.0.0.0:6008 \
  --model /data/models/zh-xlarge \
  --max-sessions 2 \
  --num-threads 4
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

## 错误处理

| 场景 | 行为 |
|------|------|
| 鉴权失败 | `401` + 关闭连接（不占槽位） |
| 槽位满 | `503` `{"error":"busy"}` + 关闭连接 |
| 模型加载失败 | 启动阶段 panic/exit（无法恢复） |
| ASR 运行时异常 | 发 `{"type":"error","fatal":false}`，重置 stream |
| 客户端非法帧 | 发 `{"type":"error","message":"..."}`，不关闭连接 |
| 客户端断连 | 释放槽位，队列中下一个出队 |
| 客户端空闲超时 | 60 秒无帧 → 服务端主动关闭 |
