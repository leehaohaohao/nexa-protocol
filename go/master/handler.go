package master

import (
	"log/slog"

	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// Handler 消息处理器接口，每种 MessageType 实现一个独立 Handler
type Handler interface {
	Type() messages.MessageType
	Handle(connCtx *ConnContext, envelope *messages.Envelope)
}

// ConnContext 连接上下文，持有当前连接的 session 信息
type ConnContext struct {
	Session *RunnerSession
}

// MessageDispatcher 消息分发器，根据 MessageType 路由到对应 Handler
type MessageDispatcher struct {
	handlers map[messages.MessageType]Handler
	logger   *slog.Logger
}

// NewMessageDispatcher 创建分发器，注册所有 Handler
func NewMessageDispatcher(handlers []Handler, logger *slog.Logger) *MessageDispatcher {
	m := make(map[messages.MessageType]Handler, len(handlers))
	for _, h := range handlers {
		m[h.Type()] = h
	}
	return &MessageDispatcher{
		handlers: m,
		logger:   logger,
	}
}

// Dispatch 根据消息类型分发到对应 Handler
func (d *MessageDispatcher) Dispatch(connCtx *ConnContext, envelope *messages.Envelope) {
	handler, ok := d.handlers[envelope.GetType()]
	if !ok {
		d.logger.Warn("no handler for message type", "type", envelope.GetType())
		return
	}

	handler.Handle(connCtx, envelope)
}
