package master

import (
	"log/slog"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// RegisterHandler 处理 REGISTER_REQ 消息
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

	conn := connCtx.Session.Conn
	runnerId := req.GetRunnerId()

	session := NewRunnerSession(runnerId, conn, req.GetHostname(), req.GetIp(), req.GetVersion())

	old := h.sessions.Register(session)
	if old != nil {
		h.logger.Info("runner reconnected, closing old session", "runner_id", runnerId)
		old.Close()
	}

	connCtx.Session = session

	resp := h.listener.OnRegister(session, req)

	respEnv := codec.BuildRegisterResponse(runnerId, resp.GetSuccess(), resp.GetMessage())
	session.Send(respEnv)
}
