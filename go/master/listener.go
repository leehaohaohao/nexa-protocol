package master

import "github.com/leehaohaohao/nexa-protocol/go/messages"

// Listener Master 事件监听接口
type Listener interface {
	OnRegister(session *RunnerSession, req *messages.RegisterRequest) *messages.RegisterResponse
	OnHeartbeat(session *RunnerSession, req *messages.HeartbeatRequest)
	OnDisconnect(runnerId, reason string)
}

// DefaultListener 默认监听器，空操作实现
type DefaultListener struct{}

func (d *DefaultListener) OnRegister(session *RunnerSession, req *messages.RegisterRequest) *messages.RegisterResponse {
	return &messages.RegisterResponse{Success: true, Message: "ok"}
}

func (d *DefaultListener) OnHeartbeat(session *RunnerSession, req *messages.HeartbeatRequest) {}

func (d *DefaultListener) OnDisconnect(runnerId, reason string) {}
