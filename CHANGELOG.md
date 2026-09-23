# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)（SemVer）规范，格式为 `vMajor.Minor.Patch`。

- **Major**：协议不兼容变更（字段删除、类型变更、枚举值重排），需要经过开发者允许才可升级版本号，根据开发者主观判断
- **Minor**：新增消息类型、新增字段、新增枚举值
- **Patch**：注释修正、文档更新、依赖升级、代码生成优化

> Go 模块通过 git tag `go/vX.Y.Z` 管理版本，Java 模块通过 git tag `java/vX.Y.Z` 管理版本。

---

## Go

### v0.6.0 (2026-09-23)

#### 新增

- 客户端可取消/有界超时：`Client.ConnectContext` / `RegisterContext`（`Connect` / `Register` 保留为默认超时封装）
- 客户端会话重建：`Close`（幂等安全关闭）、`Reset`（关闭连接并重建心跳停止状态），支持重连循环复用同一实例
- 客户端配置：`WithToken`（L1 注册认证）、`WithErrorHandler`（心跳等后台错误回调）
- 帧写入串行化：新增写锁，避免心跳、注册、断开并发写同一连接导致帧交织

#### 修复

- `codec.WriteFrame` 处理底层短写（新增 `writeFull`），保证整帧写出不被截断
- master `RegisterHandler` 改为**先认证、后接管**：认证失败不再替换/关闭同 `runnerId` 的合法在线会话，也不再残留未认证会话
- master `SessionManager.RemoveIfPresent` 返回是否真正移除；连接退出仅在确实移除会话时才回调 `OnDisconnect`，修复旧连接断开误报节点离线
- 同一连接重复注册不再关闭自身

#### 测试

- 新增 `go/client` 测试：注册超时/取消、`Reset` 会话重建、`Close` 幂等、token 透传
- 新增 `go/codec` 测试：短写完整帧、零写入不挂死、帧往返、超长帧拒绝

### v0.5.0 (2026-08-25)

#### 新增

- 子节点认证：`RegisterRequest` 新增 `token` 字段（`proto/register.proto`），codec 补充 `BuildRegisterRequestWithToken`（L1 注册认证）
- 产物传输消息类型：`MessageType` 新增 `ARTIFACT_REQ = 12` / `ARTIFACT_DATA = 13` / `ARTIFACT_ACK = 14`（`proto/common.proto`）
- `ArtifactRequest/Chunk/Ack`（`proto/artifact.proto`）：产物分块传输，复用 Envelope 的 `request_id` 关联整次传输（`ArtifactChunk.transfer_id`）
- codec 层补充 `BuildArtifactRequest/Chunk/Ack`，分块与确认回填请求 `request_id`
- master 新增 `ArtifactHandler` 处理产物请求；可选 `ArtifactListener` 扩展接口（`OnArtifactRequest`），原 `Listener` 保持向后兼容

### v0.4.0 (2026-08-24)

#### 新增

- 容器状态/日志查询消息类型：`MessageType` 新增 `CONTAINER_STATUS_REQ = 8` / `CONTAINER_STATUS_RESP = 9`、`CONTAINER_LOGS_REQ = 10` / `CONTAINER_LOGS_RESP = 11`（`proto/common.proto`）
- `ContainerStatusRequest/Response`、`ContainerLogsRequest/Response`（`proto/query.proto`）：远程容器状态与日志查询，复用 Envelope 的 `request_id` 做请求/响应关联
- codec 层补充 `BuildContainerStatusRequest/Response`、`BuildContainerLogsRequest/Response`，响应构造支持回填请求 `request_id`
- master 新增 `ContainerStatusHandler` / `ContainerLogsHandler` 处理查询回执；可选 `QueryListener` 扩展接口（`OnContainerStatus` / `OnContainerLogs`），原 `Listener` 保持向后兼容

### v0.3.0 (2026-08-10)

#### 新增

- 任务下发消息类型：`MessageType` 新增 `TASK_DISPATCH_REQ = 6` / `TASK_DISPATCH_RESP = 7`（`proto/common.proto`）
- `TaskRequest` / `TaskResponse`（`proto/task.proto`）：任务执行请求与回执，业务配置收敛进 `config` map 字段
- codec 层补充 `BuildTaskDispatchRequest` / `BuildTaskDispatchResponse`

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

### v0.6.0 (2026-09-23)

#### 修复

- `RegisterHandler` 改为**先认证、后接管**：
  - 认证（`listener.onRegister`）在触碰会话注册表之前完成，错误 token / 未登记节点不再替换或关闭同 `runnerId` 的合法在线会话
  - 认证失败时先可靠写出失败响应再关闭连接（`ChannelFutureListener.CLOSE`），且不为该连接绑定会话身份，避免其断开时误报节点离线
  - 解析失败、缺少 `runner_id`、监听器异常均走同一拒绝路径
- 同一连接重复注册不再关闭自身（仅当旧会话属于另一条连接时才关闭）

#### 测试

- 新增 JUnit 5 测试依赖与 `RegisterHandlerTest`：覆盖「拒绝注册不影响在线节点」「合法重连只保留新会话且旧连接断开不误报离线」「同连接重复注册不自杀」「缺少 runner_id 被拒」「认证异常不接管会话」

### v0.5.0 (2026-08-25)

#### 新增

- 子节点认证：`RegisterRequest` 新增 `token` 字段（`proto/register.proto`），`ProtocolCodec.buildRegisterRequest` 增加带 token 重载（L1 注册认证）
- 产物传输消息类型：`MessageType` 新增 `ARTIFACT_REQ = 12` / `ARTIFACT_DATA = 13` / `ARTIFACT_ACK = 14`（`proto/common.proto`）
- `ArtifactRequest/Chunk/Ack`（`proto/artifact.proto`）：产物分块传输，复用 Envelope 的 `request_id` 关联整次传输（`ArtifactChunk.transfer_id`）
- `ProtocolCodec` 补充 `buildArtifactRequest/Chunk/Ack` 及对应解析方法，分块与确认回填请求 `request_id`
- `NexaMaster` 新增 `ArtifactHandler` 处理产物请求（校验会话身份后回调）
- `NexaMasterListener` 新增 `onArtifactRequest` 默认回调（携带请求 Envelope 与 session，供分块回发）

### v0.4.0 (2026-08-24)

#### 新增

- 容器状态/日志查询消息类型：`MessageType` 新增 `CONTAINER_STATUS_REQ = 8` / `CONTAINER_STATUS_RESP = 9`、`CONTAINER_LOGS_REQ = 10` / `CONTAINER_LOGS_RESP = 11`（`proto/common.proto`）
- `ContainerStatusRequest/Response`、`ContainerLogsRequest/Response`（`proto/query.proto`）：远程容器状态与日志查询，复用 Envelope 的 `request_id` 做请求/响应关联
- `ProtocolCodec` 补充 `buildContainerStatusRequest/Response`、`buildContainerLogsRequest/Response` 及对应解析方法，响应构造支持回填请求 `request_id`
- `NexaMaster` 新增 `ContainerStatusHandler` / `ContainerLogsHandler` 处理查询回执（handler 链可扩展注册）
- `NexaMasterListener` 新增 `onContainerStatus` / `onContainerLogs` 默认回调

### v0.2.0 (2026-08-10)

#### 新增

- 任务下发消息类型：`MessageType` 新增 `TASK_DISPATCH_REQ = 6` / `TASK_DISPATCH_RESP = 7`（`proto/common.proto`）
- `TaskRequest` / `TaskResponse`（`proto/task.proto`）：任务执行请求与回执，业务配置收敛进 `config` map 字段
- `NexaMaster` handler 链改为可扩展注册：`Builder.addHandler` / `registerHandler`
- `TaskResultHandler`：处理 `TASK_DISPATCH_RESP` 并回调 `NexaMasterListener.onTaskResult`
- `NexaMasterListener` 新增 `onTaskResult` 默认回调
- `ProtocolCodec` 补充 `buildTaskDispatchRequest` / `buildTaskDispatchResponse` 及对应解析方法

### v0.1.1 (2026-06-29)

#### 修复

- `MasterChannelHandler` 添加 `@Sharable` 注解，修复多连接共享 handler 导致 `ChannelPipelineException` 的问题

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
