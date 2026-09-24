# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)（SemVer）规范，格式为 `vMajor.Minor.Patch`。

- **Major**：协议不兼容变更（字段删除、类型变更、枚举值重排），需要经过开发者允许才可升级版本号，根据开发者主观判断
- **Minor**：新增消息类型、新增字段、新增枚举值
- **Patch**：注释修正、文档更新、依赖升级、代码生成优化

> Go 模块通过 git tag `go/vX.Y.Z` 管理版本，Java 模块通过 git tag `java/vX.Y.Z` 管理版本。

---

## Go

### v0.6.2 (2026-09-23)

#### 修复

- **统一断开事件所有权，消除漏通知竞态**：超时监控、主动断开、连接退出三条路径统一为
  「**谁成功条件移除当前会话，谁负责通知恰好一次**」
  - `NexaMaster.cleanupConnection`（连接退出清理，从 `handleConn` 的 defer 中抽出并可直接测试）：
    不再凭会话的 `timedOut` 标记推断「心跳监控器已经通知」而跳过。此前若监控器先 `MarkTimedOut`、
    连接退出路径抢先完成条件移除，双方都会跳过通知，导致会话已离线却收不到掉线事件
  - 成功移除者按会话状态上报原因：已标记超时 → `heartbeat_timeout`，否则 → `connection_lost`
  - `HeartbeatMonitor`：CAS 标记超时仅用于标记状态与上报原因，**不作为「已通知」的依据**

#### 测试

- 新增确定性交错测试 `TestTimeoutAndConnectionCloseInterleavingNotifiesOnce`：用真实
  `cleanupConnection` 固定「标记超时 → 连接退出移除 → 监控器移除失败」顺序，断言恰好一次通知、
  原因为 `heartbeat_timeout`、事件携带会话身份、会话只被移除一次

### v0.6.1 (2026-09-23)

#### 修复

- **已注册消息一律以连接绑定的会话为准**（与 Java v0.6.1 对齐；新增内部校验入口 `resolveSession` / `matchesRunnerId`）：
  `HeartbeatHandler`、`DisconnectHandler`、`ContainerStatusHandler`、`ContainerLogsHandler`、`ArtifactHandler`
  统一要求「连接已注册 + 该会话仍是当前会话」（新增 `SessionManager.IsCurrent`），
  报文 runnerId / `Envelope.source_id` 仅作一致性检查
  - `HeartbeatHandler`：由「按报文 runnerId 查会话」改为使用连接绑定会话，仅刷新并回包给发送方自身会话；
    未注册连接、已被接管的旧连接、冒用他人 runnerId 的心跳一律拒绝
  - `DisconnectHandler`：由「按报文 runnerId 无条件 `Remove`」改为**条件移除自身当前会话**，
    且仅确认移除时才通知，无法再借此摘掉他人节点
  - 回执类与产物 handler：按连接会话解析并核对声明身份
- `HeartbeatMonitor`：先 `RemoveIfPresent` 条件移除，**仅在确认移除当前会话时**才通知一次超时断开；
  被接管的过期旧连接只清理连接、不通知新节点掉线。会话来源抽为 `SessionSource` 接口
  （`*SessionManager` 天然满足），便于注入「扫描期间被接管」的交错场景测试
- 连接退出清理改用 `notifyDisconnect`，与超时、主动断开共用「仅条件移除成功才通知」规则
- 新增可选 `SessionDisconnectListener` 扩展接口（`OnDisconnectSession`）：断开事件携带会话身份，
  便于区分「旧会话迟到断开」与「当前会话断开」；未实现时回退旧签名 `OnDisconnect`，原 `Listener` 保持兼容

#### 测试

- 新增 `go/master` 测试：未注册连接与冒用身份的心跳、主动断开、状态/日志回执、产物请求均被拒绝；
  接管后旧连接消息全部失效；正常路径不回退
- `HeartbeatMonitor` 测试：超时通知一次、重复扫描不重复、**扫描期间被接管不通知**、
  超时与连接退出不双重通知、旧 Listener 兼容、并发接管交错 200 轮不变量

### v0.6.0 (2026-09-23)

#### 新增

- 客户端可取消/有界超时：`Client.ConnectContext` / `RegisterContext`（`Connect` / `Register` 保留为默认超时封装）
- 客户端会话重建：`Close`（幂等安全关闭）、`Reset`（关闭连接并重建心跳停止状态），支持重连循环复用同一实例
- 客户端配置：`WithToken`（L1 注册认证）、`WithErrorHandler`（心跳等后台错误回调）
- 帧写入串行化：新增写锁，避免心跳、注册、断开并发写同一连接导致帧交织

#### 修复

- `Client.RegisterContext` / `Register` 收到主节点拒绝（`success=false`，如 token 错误、节点未登记）时返回错误，不再当作注册成功继续运行
- `codec.WriteFrame` 处理底层短写（新增 `writeFull`），保证整帧写出不被截断
- master `RegisterHandler` 改为**先认证、后接管**：认证失败不再替换/关闭同 `runnerId` 的合法在线会话，也不再残留未认证会话
- master `SessionManager.RemoveIfPresent` 返回是否真正移除；连接退出仅在确实移除会话时才回调 `OnDisconnect`，修复旧连接断开误报节点离线
- 同一连接重复注册不再关闭自身

#### 测试

- 新增 `go/client` 测试：注册超时/取消、注册被拒返回错误、`Reset` 会话重建、`Close` 幂等、token 透传
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

### v0.6.2 (2026-09-23)

#### 修复

- **统一断开事件所有权，消除漏通知竞态**（复核发现的剩余竞态 D.1）：超时监控、主动断开、
  连接退出三条路径统一为「**谁成功条件移除当前会话，谁负责通知恰好一次**」
  - `MasterChannelHandler.channelInactive`：不再凭 `session.isTimedOut()` 推断
    「心跳监控器已经通知」而跳过。此前 `HeartbeatMonitor.doCheck()` 先 `markTimedOut()` 再
    `removeIfPresent()`，若 `channelInactive` 恰在两者之间抢先移除，它会因超时标记为真而跳过回调、
    监控器随后因移除失败也不回调 → 会话已离线但掉线事件**漏发**
  - 成功移除者按会话状态上报原因：已标记超时 → `heartbeat_timeout`，否则 → `connection_lost`
  - `HeartbeatMonitor`：CAS 标记超时仅用于标记状态与上报原因，**不作为「已通知」的依据**

#### 测试

- 新增可控同步点的确定性交错测试 `timeoutAndChannelInactiveInterleavingNotifiesExactlyOnce`：
  用真实 `MasterChannelHandler.channelInactive` 固定「标记超时 → 连接退出移除 → 监控器移除失败」
  顺序，断言恰好一次通知、原因为 `heartbeat_timeout`、事件携带会话身份、会话只被移除一次

### v0.6.1 (2026-09-23)

#### 修复

- **已注册消息一律以发送 channel 绑定的会话为准**（专项修复 A；新增统一入口 `SessionResolver`）：
  `SessionResolver` 从 `ctx.channel()` 取得注册后绑定的 runnerId，查 `SessionManager` 并要求当前会话
  channel 与本连接**完全相同**，同时集中 `nexa.runnerId` 属性常量。
  `HeartbeatHandler`、`DisconnectHandler`、`TaskResultHandler`、`ContainerStatusHandler`、`ContainerLogsHandler`、
  `ArtifactHandler` 全部改用它。未注册连接、已被同 ID 新连接接管的旧连接发出的消息
  一律拒绝：不刷新心跳、不回业务响应、不移除会话、不触发业务回调
  - `HeartbeatHandler`：仅刷新并回包给**发送方自身会话**；报文 runnerId 降级为一致性检查，
    伪造他人 runnerId 的心跳不再刷新任何节点、不污染负载
  - `DisconnectHandler`：由「按报文 runnerId 无条件移除」改为**条件移除自身当前会话**，
    且仅确认移除时才通知，无法再借此摘掉他人节点
  - 回执类 handler：按 channel 解析会话并核对回执中的 runnerId，伪造回执不再污染其他节点结果
  - `ArtifactHandler`：保留 channel 校验并与统一规则对齐，以 `Envelope.source_id` 做一致性检查
- **超时事件只处理被确认移除的那次会话**（专项修复 B）：
  - `HeartbeatMonitor`：先 `removeIfPresent(runnerId, session)` 条件移除，**仅成功移除当前映射时**
    才通知一次超时断开；已被新会话接管时只清理过期旧连接，不通知新节点掉线
  - `MasterChannelHandler.channelInactive`：与超时、主动断开共用「仅条件移除成功才通知」规则，
    消除双重通知
- 新增 `NexaMasterListener.onDisconnect(RunnerSession, String)` 默认回调（携带会话身份，可据此区分会话代次）；
  默认委托旧签名 `onDisconnect(String, String)`，旧实现无需改动即可继续工作

#### 测试

- 新增 `SessionIdentityTest`（13 用例）：未注册连接 / 已注册连接冒用他人 runnerId 的心跳、主动断开、
  任务回执、状态与日志回执、产物请求均被拒绝且不影响受害者会话；接管后旧连接全部消息失效；
  新会话心跳正常
- 新增 `HeartbeatMonitorTest`（7 用例）：超时通知一次、重复扫描不重复通知、被接管会话只清理不通知、
  超时与 `channelInactive` 不双重通知、正常断开通知 `connection_lost`、旧 API 兼容、
  超时扫描与新连接接管并发交错（200 轮不变量）
- 测试支持：`EnvelopeFixtures`（消息构造）、`RecordingMasterListener`（按会话身份记录回调）

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
