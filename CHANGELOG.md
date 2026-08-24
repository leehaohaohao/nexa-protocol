# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)（SemVer）规范，格式为 `vMajor.Minor.Patch`。

- **Major**：协议不兼容变更（字段删除、类型变更、枚举值重排），需要经过开发者允许才可升级版本号，根据开发者主观判断
- **Minor**：新增消息类型、新增字段、新增枚举值
- **Patch**：注释修正、文档更新、依赖升级、代码生成优化

> Go 模块通过 git tag `go/vX.Y.Z` 管理版本，Java 模块通过 git tag `java/vX.Y.Z` 管理版本。

---

## Go

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
