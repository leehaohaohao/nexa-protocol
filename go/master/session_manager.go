package master

import "sync"

// SessionManager 会话管理器，线程安全地管理所有 RunnerSession
type SessionManager struct {
	mu       sync.RWMutex
	sessions map[string]*RunnerSession
}

// NewSessionManager 创建会话管理器
func NewSessionManager() *SessionManager {
	return &SessionManager{
		sessions: make(map[string]*RunnerSession),
	}
}

// Register 注册会话，原子替换同 runnerId 的旧会话，返回旧会话
func (m *SessionManager) Register(session *RunnerSession) *RunnerSession {
	m.mu.Lock()
	defer m.mu.Unlock()

	old := m.sessions[session.RunnerId]
	m.sessions[session.RunnerId] = session
	return old
}

// Remove 移除会话
func (m *SessionManager) Remove(runnerId string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	delete(m.sessions, runnerId)
}

// RemoveIfPresent 仅当当前映射值与 expected 相同时移除（重连安全）
func (m *SessionManager) RemoveIfPresent(runnerId string, expected *RunnerSession) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.sessions[runnerId] == expected {
		delete(m.sessions, runnerId)
	}
}

// Get 获取会话
func (m *SessionManager) Get(runnerId string) (*RunnerSession, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	s, ok := m.sessions[runnerId]
	return s, ok
}

// AllSessions 返回所有会话的快照
func (m *SessionManager) AllSessions() []*RunnerSession {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*RunnerSession, 0, len(m.sessions))
	for _, s := range m.sessions {
		result = append(result, s)
	}
	return result
}

// Size 返回当前会话数量
func (m *SessionManager) Size() int {
	m.mu.RLock()
	defer m.mu.RUnlock()

	return len(m.sessions)
}
