package master

import (
	"context"
	"log/slog"
	"time"
)

// HeartbeatMonitor 心跳监控器，定期扫描并处理超时会话
type HeartbeatMonitor struct {
	sessions  *SessionManager
	listener  Listener
	timeout   time.Duration
	interval  time.Duration
	logger    *slog.Logger
}

// NewHeartbeatMonitor 创建心跳监控器
func NewHeartbeatMonitor(sessions *SessionManager, listener Listener, timeout, interval time.Duration, logger *slog.Logger) *HeartbeatMonitor {
	return &HeartbeatMonitor{
		sessions: sessions,
		listener: listener,
		timeout:  timeout,
		interval: interval,
		logger:   logger,
	}
}

// Start 启动心跳监控，通过 ctx 控制生命周期
func (m *HeartbeatMonitor) Start(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(m.interval)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				m.doCheck()
			}
		}
	}()
}

func (m *HeartbeatMonitor) doCheck() {
	for _, session := range m.sessions.AllSessions() {
		if session.timedOut.Load() {
			continue
		}

		if !session.IsExpired(m.timeout) {
			continue
		}

		if !session.MarkTimedOut() {
			continue
		}

		m.logger.Warn("runner heartbeat timeout",
			"runner_id", session.RunnerId,
			"timeout", m.timeout,
		)

		session.Close()
		m.sessions.RemoveIfPresent(session.RunnerId, session)
		m.listener.OnDisconnect(session.RunnerId, "heartbeat_timeout")
	}
}
