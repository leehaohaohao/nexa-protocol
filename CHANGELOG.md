# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)（SemVer）规范，格式为 `vMajor.Minor.Patch`。

- **Major**：协议不兼容变更（字段删除、类型变更、枚举值重排），需要经过开发者允许才可升级版本号，根据开发者主观判断
- **Minor**：新增消息类型、新增字段、新增枚举值
- **Patch**：注释修正、文档更新、依赖升级、代码生成优化

> Go 模块通过 git tag `go/vX.Y.Z` 管理版本，Java 模块通过 git tag `java/vX.Y.Z` 管理版本。

---

## Go

### v0.2.0 (2026-06-25)

#### 新增

- TCP 客户端 SDK（`go/client/`）
  - 函数式选项配置：`WithRunnerId`、`WithHostname`、`WithIP`、`WithVersion`、`WithHeartbeatInterval`
  - `Connect` / `Register` / `StartHeartbeat` / `Disconnect` 完整生命周期
- Master 主节点（`go/master/`）
  - `NexaMaster`：TCP 服务端，goroutine-per-connection 模型
  - `RunnerSession`：会话管理，原子心跳时间 + CAS 超时标记
  - `SessionManager`：读写锁保护的会话注册表，支持条件移除（重连安全）
  - `HeartbeatMonitor`：定期扫描超时会话，CAS 单赢家处理
  - `Listener` 接口：`OnRegister` / `OnHeartbeat` / `OnDisconnect` 事件回调
  - `MessageDispatcher` + `Handler` 接口：消息分发注册中心模式
  - 函数式选项配置：`WithHost`、`WithPort`、`WithHeartbeatTimeout`、`WithHeartbeatInterval`、`WithLogger`
- codec 层补充 `BuildRegisterResponse` / `BuildHeartbeatResponse`

### v0.1.0 (2026-06-22)

#### 初始版本

- 定义基础通信协议（proto3）
- `Envelope` 通用信封消息：version、type、request_id、source_id、target_id、timestamp、payload
- `MessageType` 消息类型枚举：REGISTER、HEARTBEAT、DISCONNECT
- `RegisterRequest` / `RegisterResponse`：Runner 注册（runner_id、hostname、ip、version）
- `HeartbeatRequest` / `HeartbeatResponse`：心跳上报（runner_id、running_tasks、cpu_usage、memory_usage）
- `DisconnectRequest`：断开连接（runner_id、reason）
- 生成 Go protobuf 代码（`go/messages/`）

---

## Java

### v0.1.0 (2026-06-25)

#### 初始版本

- 定义基础通信协议（proto3）
- `Envelope` 通用信封消息：version、type、request_id、source_id、target_id、timestamp、payload
- `MessageType` 消息类型枚举：REGISTER、HEARTBEAT、DISCONNECT
- `RegisterRequest` / `RegisterResponse`：Runner 注册（runner_id、hostname、ip、version）
- `HeartbeatRequest` / `HeartbeatResponse`：心跳上报（runner_id、running_tasks、cpu_usage、memory_usage）
- `DisconnectRequest`：断开连接（runner_id、reason）
- 编解码层
  - `FrameCodec`：阻塞 I/O 帧读写（4 字节大端序长度前缀）
  - `ProtocolCodec`：Envelope 构建/解析，各消息类型的便捷 Builder 和 Parser
- TCP 客户端 SDK（`client/`）
  - `NexaClient`：阻塞式 TCP 客户端，支持注册、心跳、断开
- Master 主节点（`master/`）
  - `NexaMaster`：基于 Netty 的 TCP 服务端，Builder 模式配置
  - `RunnerSession`：会话对象，`AtomicLong` 心跳时间 + `AtomicBoolean` CAS 超时标记
  - `SessionManager`：`ConcurrentHashMap` 会话注册表，支持原子替换和条件移除
  - `HeartbeatMonitor`：`ScheduledExecutorService` 定期扫描超时会话
  - `NexaMasterListener` 接口：`onRegister` / `onHeartbeat` / `onDisconnect` 事件回调
  - `MessageDispatcher` + `MessageHandler` 接口：消息分发注册中心模式
  - Netty 编解码：`NexaFrameDecoder` / `NexaFrameEncoder`
- Spring Boot 自动配置（`master/autoconfigure/`）
  - `@EnableNexaMaster` 注解激活
  - `NexaMasterProperties`：`nexa.master.*` 配置项（host、port、heartbeatTimeout 等）
  - `NexaMasterAutoConfiguration`：自动装配 Master 和默认 Listener
