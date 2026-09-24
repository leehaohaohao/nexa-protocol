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

		// CAS 标记超时：仅用于标记会话状态与上报原因，不作为「已通知」的依据
		if !session.MarkTimedOut() {
			continue
		}

		// 事件所有权：谁成功条件移除当前会话，谁负责通知恰好一次。
		// - 移除成功 → 本路径通知（连接退出路径随后移除失败，不会重复通知）
		// - 移除失败 → 会话已被连接退出/主动断开移除，或已被新会话接管，本路径不通知
		// 若已被同 ID 新连接接管，则只清理过期旧连接，新节点保持在线且不被通知。
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
