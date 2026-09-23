package master

import "github.com/leehaohaohao/nexa-protocol/go/messages"

// Listener Master 事件监听接口
type Listener interface {
	OnRegister(session *RunnerSession, req *messages.RegisterRequest) *messages.RegisterResponse
	OnHeartbeat(session *RunnerSession, req *messages.HeartbeatRequest)
	OnDisconnect(runnerId, reason string)
}

// SessionDisconnectListener 可选的断开事件扩展：携带会话身份。
//
// 旧签名 OnDisconnect(runnerId, reason) 无法区分「旧会话迟到断开」与「当前会话断开」；
// 业务方实现本接口即可按会话代次处理。原 Listener 保持向后兼容，无需改动。
type SessionDisconnectListener interface {
	Listener
	OnDisconnectSession(session *RunnerSession, reason string)
}

// notifyDisconnect 优先使用带会话身份的扩展回调，否则回退旧签名
func notifyDisconnect(listener Listener, session *RunnerSession, reason string) {
	if sl, ok := listener.(SessionDisconnectListener); ok {
		sl.OnDisconnectSession(session, reason)
		return
	}
	listener.OnDisconnect(session.RunnerId, reason)
}

// DefaultListener 默认监听器，空操作实现
type DefaultListener struct{}

func (d *DefaultListener) OnRegister(session *RunnerSession, req *messages.RegisterRequest) *messages.RegisterResponse {
	return &messages.RegisterResponse{Success: true, Message: "ok"}
}

func (d *DefaultListener) OnHeartbeat(session *RunnerSession, req *messages.HeartbeatRequest) {}

func (d *DefaultListener) OnDisconnect(runnerId, reason string) {}
