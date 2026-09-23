package master

import (
	"log/slog"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// DisconnectHandler 处理 DISCONNECT_REQ 消息。
//
// 只能条件移除发送方自身当前会话：依据报文 runnerId 删除会话会让任意连接摘掉他人节点。
// 且仅当确实移除了当前绑定的会话时才通知 listener，与心跳超时、连接退出共用
// 「仅条件移除成功才通知」规则，避免重复通知。
type DisconnectHandler struct {
	sessions *SessionManager
	listener Listener
	logger   *slog.Logger
}

func NewDisconnectHandler(sessions *SessionManager, listener Listener, logger *slog.Logger) *DisconnectHandler {
	return &DisconnectHandler{sessions: sessions, listener: listener, logger: logger}
}

func (h *DisconnectHandler) Type() messages.MessageType {
	return messages.MessageType_DISCONNECT_REQ
}

func (h *DisconnectHandler) Handle(connCtx *ConnContext, env *messages.Envelope) {
	req := &messages.DisconnectRequest{}
	if err := codec.UnmarshalMessage(env.GetPayload(), req); err != nil {
		h.logger.Error("failed to parse DisconnectRequest", "error", err)
		if connCtx != nil && connCtx.Session != nil {
			connCtx.Session.Close()
		}
		return
	}

	session, ok := resolveSession(h.sessions, connCtx)
	if !ok {
		h.logger.Warn("disconnect from unregistered or superseded connection, not touching any session")
		if connCtx != nil && connCtx.Session != nil {
			connCtx.Session.Close()
		}
		return
	}

	if !matchesRunnerId(session, req.GetRunnerId()) {
		h.logger.Warn("disconnect runner_id mismatch, ignored",
			"declared", req.GetRunnerId(), "session", session.RunnerId)
		session.Close()
		return
	}

	reason := req.GetReason()
	if reason == "" {
		reason = "client_disconnect"
	}

	// 仅条件移除自身当前会话；已被新连接接管时不触碰新会话
	if h.sessions.RemoveIfPresent(session.RunnerId, session) {
		h.logger.Info("runner disconnected", "runner_id", session.RunnerId, "reason", reason)
		notifyDisconnect(h.listener, session, reason)
	}

	session.Close()
}
