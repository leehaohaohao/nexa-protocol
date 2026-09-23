package master

import (
	"log/slog"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// RegisterHandler 处理 REGISTER_REQ 消息。
//
// 顺序约束：只有认证通过的新连接才能原子替换同 runnerId 的旧会话。
// 认证失败时不影响已在线的合法会话，且候选会话不写入注册表、
// connCtx.Session 保持空 runnerId，因此该连接断开不会误报节点离线。
type RegisterHandler struct {
	sessions *SessionManager
	listener Listener
	logger   *slog.Logger
}

func NewRegisterHandler(sessions *SessionManager, listener Listener, logger *slog.Logger) *RegisterHandler {
	return &RegisterHandler{sessions: sessions, listener: listener, logger: logger}
}

func (h *RegisterHandler) Type() messages.MessageType {
	return messages.MessageType_REGISTER_REQ
}

func (h *RegisterHandler) Handle(connCtx *ConnContext, env *messages.Envelope) {
	req := &messages.RegisterRequest{}
	if err := codec.UnmarshalMessage(env.GetPayload(), req); err != nil {
		h.logger.Error("failed to parse RegisterRequest", "error", err)
		return
	}

	runnerId := req.GetRunnerId()
	if runnerId == "" {
		h.logger.Warn("register request without runner_id")
		h.reject(connCtx, "", "runner_id is required")
		return
	}

	conn := connCtx.Session.Conn
	// 候选会话：此时尚未注册，认证失败不得影响任何现有会话
	candidate := NewRunnerSession(runnerId, conn, req.GetHostname(), req.GetIp(), req.GetVersion())

	// 1. 先认证（不触碰会话注册表）
	resp := h.listener.OnRegister(candidate, req)
	if resp == nil || !resp.GetSuccess() {
		reason := "register rejected"
		if resp != nil && resp.GetMessage() != "" {
			reason = resp.GetMessage()
		}
		h.logger.Warn("register rejected", "runner_id", runnerId, "reason", reason)
		h.reject(connCtx, runnerId, reason)
		return
	}

	// 2. 认证通过后才接管：原子替换同 runnerId 的旧会话
	old := h.sessions.Register(candidate)
	if old != nil && old.Conn != conn {
		h.logger.Info("runner reconnected, closing old session", "runner_id", runnerId)
		// 旧连接退出时，handleConn 的清理会因条件移除失败而不再回调 OnDisconnect
		old.Close()
	}

	connCtx.Session = candidate

	// 3. 发送成功响应
	respEnv := codec.BuildRegisterResponse(runnerId, true, resp.GetMessage())
	candidate.Send(respEnv)
}

// reject 拒绝注册：先尽力写出失败响应，再关闭连接以终止该连接的读循环。
func (h *RegisterHandler) reject(connCtx *ConnContext, runnerId, reason string) {
	respEnv := codec.BuildRegisterResponse(runnerId, false, reason)
	_ = connCtx.Session.Send(respEnv)
	connCtx.Session.Close()
}
