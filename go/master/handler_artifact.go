package master

import (
	"log/slog"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// ArtifactListener 可选的产物请求监听器扩展。
// Listener 保持向后兼容，业务方需响应产物请求时实现本接口即可。
type ArtifactListener interface {
	Listener
	OnArtifactRequest(session *RunnerSession, req *messages.ArtifactRequest)
}

// ArtifactHandler 处理 ARTIFACT_REQ 消息（runner 产物请求）
type ArtifactHandler struct {
	sessions *SessionManager
	listener Listener
	logger   *slog.Logger
}

func NewArtifactHandler(sessions *SessionManager, listener Listener, logger *slog.Logger) *ArtifactHandler {
	return &ArtifactHandler{sessions: sessions, listener: listener, logger: logger}
}

func (h *ArtifactHandler) Type() messages.MessageType {
	return messages.MessageType_ARTIFACT_REQ
}

func (h *ArtifactHandler) Handle(connCtx *ConnContext, env *messages.Envelope) {
	req := &messages.ArtifactRequest{}
	if err := codec.UnmarshalMessage(env.GetPayload(), req); err != nil {
		h.logger.Error("failed to parse ArtifactRequest", "error", err)
		return
	}

	al, ok := h.listener.(ArtifactListener)
	if !ok {
		h.logger.Warn("listener does not implement ArtifactListener, ignore artifact request", "service_id", req.GetServiceId())
		return
	}

	al.OnArtifactRequest(connCtx.Session, req)
}
