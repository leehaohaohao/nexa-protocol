package master

import (
	"log/slog"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// HeartbeatHandler 处理 HEARTBEAT_REQ 消息
type HeartbeatHandler struct {
	sessions *SessionManager
	listener Listener
	logger   *slog.Logger
}

func NewHeartbeatHandler(sessions *SessionManager, listener Listener, logger *slog.Logger) *HeartbeatHandler {
	return &HeartbeatHandler{sessions: sessions, listener: listener, logger: logger}
}

func (h *HeartbeatHandler) Type() messages.MessageType {
	return messages.MessageType_HEARTBEAT_REQ
}

func (h *HeartbeatHandler) Handle(connCtx *ConnContext, env *messages.Envelope) {
	req := &messages.HeartbeatRequest{}
	if err := codec.UnmarshalMessage(env.GetPayload(), req); err != nil {
		h.logger.Error("failed to parse HeartbeatRequest", "error", err)
		return
	}

	session, ok := h.sessions.Get(req.GetRunnerId())
	if !ok {
		h.logger.Warn("heartbeat from unknown runner", "runner_id", req.GetRunnerId())
		return
	}

	session.UpdateHeartbeat()

	respEnv := codec.BuildHeartbeatResponse(req.GetRunnerId())
	session.Send(respEnv)

	h.listener.OnHeartbeat(session, req)
}
