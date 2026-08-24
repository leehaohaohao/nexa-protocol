package master

import (
	"log/slog"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// QueryListener 可选的状态/日志查询监听器扩展。
// Listener 保持向后兼容，业务方若需接收查询回执，实现本接口即可。
type QueryListener interface {
	Listener
	OnContainerStatus(session *RunnerSession, resp *messages.ContainerStatusResponse)
	OnContainerLogs(session *RunnerSession, resp *messages.ContainerLogsResponse)
}

// ContainerStatusHandler 处理 CONTAINER_STATUS_RESP 消息（runner 查询回执）
type ContainerStatusHandler struct {
	sessions *SessionManager
	listener Listener
	logger   *slog.Logger
}

func NewContainerStatusHandler(sessions *SessionManager, listener Listener, logger *slog.Logger) *ContainerStatusHandler {
	return &ContainerStatusHandler{sessions: sessions, listener: listener, logger: logger}
}

func (h *ContainerStatusHandler) Type() messages.MessageType {
	return messages.MessageType_CONTAINER_STATUS_RESP
}

func (h *ContainerStatusHandler) Handle(connCtx *ConnContext, env *messages.Envelope) {
	resp := &messages.ContainerStatusResponse{}
	if err := codec.UnmarshalMessage(env.GetPayload(), resp); err != nil {
		h.logger.Error("failed to parse ContainerStatusResponse", "error", err)
		return
	}

	ql, ok := h.listener.(QueryListener)
	if !ok {
		h.logger.Warn("listener does not implement QueryListener, ignore container status", "runner_id", resp.GetRunnerId())
		return
	}

	ql.OnContainerStatus(connCtx.Session, resp)
}
