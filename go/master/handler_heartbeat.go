package master

import (
	"log/slog"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// HeartbeatHandler 处理 HEARTBEAT_REQ 消息。
//
// 会话取自连接绑定的会话（connCtx.Session），报文中的 runnerId 仅作一致性检查：
// 未注册连接、已被同 ID 新连接接管的旧连接、冒用他人 runnerId 的心跳都会被拒绝，
// 既不刷新任何节点的心跳时间，也不污染负载。
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

	session, ok := resolveSession(h.sessions, connCtx)
	if !ok {
		h.logger.Warn("heartbeat from unregistered or superseded connection, ignored")
		return
	}

	if !matchesRunnerId(session, req.GetRunnerId()) {
		h.logger.Warn("heartbeat runner_id mismatch, ignored",
			"declared", req.GetRunnerId(), "session", session.RunnerId)
		return
	}

	// 仅刷新发送方自身会话，并只向该会话回包
	session.UpdateHeartbeat()

	respEnv := codec.BuildHeartbeatResponse(session.RunnerId)
	session.Send(respEnv)

	h.listener.OnHeartbeat(session, req)
}
