package master

import (
	"context"
	"log/slog"
	"time"
)

// SessionSource 心跳监控所需的会话来源。
// *SessionManager 天然满足该接口；抽成接口便于测试注入「扫描期间被接管」的交错场景。
type SessionSource interface {
	// AllSessions 返回当前会话快照
	AllSessions() []*RunnerSession
	// RemoveIfPresent 仅当映射值仍为 expected 时移除，返回是否确实移除
	RemoveIfPresent(runnerId string, expected *RunnerSession) bool
}

// HeartbeatMonitor 心跳监控器，定期扫描并处理超时会话
type HeartbeatMonitor struct {
	sessions SessionSource
	listener Listener
	timeout  time.Duration
	interval time.Duration
	logger   *slog.Logger
}

// NewHeartbeatMonitor 创建心跳监控器
func NewHeartbeatMonitor(sessions SessionSource, listener Listener, timeout, interval time.Duration, logger *slog.Logger) *HeartbeatMonitor {
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

		// 先条件移除：仅当注册表中当前绑定的仍是这个过期会话时，本次才拥有通知权。
		// 若已被同 ID 新连接接管，则不通知（新节点保持在线），只清理过期旧连接。
		removedCurrent := m.sessions.RemoveIfPresent(session.RunnerId, session)

		m.logger.Warn("runner heartbeat timeout, closing",
			"runner_id", session.RunnerId,
			"timeout", m.timeout,
			"current_session", removedCurrent,
		)

		session.Close()

		if removedCurrent {
			notifyDisconnect(m.listener, session, "heartbeat_timeout")
		}
	}
}
