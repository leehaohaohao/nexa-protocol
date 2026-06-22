# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)（SemVer）规范，格式为 `vMajor.Minor.Patch`。

- **Major**：协议不兼容变更（字段删除、类型变更、枚举值重排），需要经过开发者允许才可升级版本号
- **Minor**：新增消息类型、新增字段、新增枚举值
- **Patch**：注释修正、文档更新、依赖升级、代码生成优化

> Go 模块通过 git tag `go/vX.Y.Z` 管理版本，Java 模块通过 git tag `java/vX.Y.Z` 管理版本。

---

## Go

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

（待补充）
