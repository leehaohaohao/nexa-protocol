package master

import (
	"log/slog"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// ContainerLogsHandler 处理 CONTAINER_LOGS_RESP 消息（runner 查询回执）。
//
// 会话取自发送连接绑定的会话，并核对回执声明的 runnerId，
// 避免伪造回执污染其他节点的日志查询结果。
type ContainerLogsHandler struct {
	sessions *SessionManager
	listener Listener
	logger   *slog.Logger
}

func NewContainerLogsHandler(sessions *SessionManager, listener Listener, logger *slog.Logger) *ContainerLogsHandler {
	return &ContainerLogsHandler{sessions: sessions, listener: listener, logger: logger}
}

func (h *ContainerLogsHandler) Type() messages.MessageType {
	return messages.MessageType_CONTAINER_LOGS_RESP
}

func (h *ContainerLogsHandler) Handle(connCtx *ConnContext, env *messages.Envelope) {
	resp := &messages.ContainerLogsResponse{}
	if err := codec.UnmarshalMessage(env.GetPayload(), resp); err != nil {
		h.logger.Error("failed to parse ContainerLogsResponse", "error", err)
		return
	}

	session, ok := resolveSession(h.sessions, connCtx)
	if !ok {
		h.logger.Warn("container logs from unregistered or superseded connection, ignored")
		return
	}

	if !matchesRunnerId(session, resp.GetRunnerId()) {
		h.logger.Warn("container logs runner_id mismatch, ignored",
			"declared", resp.GetRunnerId(), "session", session.RunnerId)
		return
	}

	ql, ok := h.listener.(QueryListener)
	if !ok {
		h.logger.Warn("listener does not implement QueryListener, ignore container logs",
			"runner_id", session.RunnerId)
		return
	}

	ql.OnContainerLogs(session, resp)
}
