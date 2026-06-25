package master

import (
	"log/slog"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// DisconnectHandler 处理 DISCONNECT_REQ 消息
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
		return
	}

	runnerId := req.GetRunnerId()
	h.sessions.Remove(runnerId)

	reason := req.GetReason()
	if reason == "" {
		reason = "client_disconnect"
	}

	h.logger.Info("runner disconnected", "runner_id", runnerId, "reason", reason)
	h.listener.OnDisconnect(runnerId, reason)

	if connCtx.Session != nil {
		connCtx.Session.Close()
	}
}
