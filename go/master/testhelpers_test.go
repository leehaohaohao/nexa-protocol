package master

import (
	"bytes"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// ---- 内存连接替身：记录写入并可断言关闭 ----

type mockAddr struct{}

func (mockAddr) Network() string { return "mock" }
func (mockAddr) String() string  { return "mock" }

// mockConn 实现 net.Conn，Write 记录数据，Close 可断言，不阻塞
type mockConn struct {
	mu     sync.Mutex
	writes bytes.Buffer
	closed bool
}

func newMockConn() *mockConn { return &mockConn{} }

func (c *mockConn) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, errors.New("connection closed")
	}
	return c.writes.Write(p)
}

func (c *mockConn) Read([]byte) (int, error) { return 0, io.EOF }

func (c *mockConn) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.closed = true
	return nil
}

func (c *mockConn) LocalAddr() net.Addr                { return mockAddr{} }
func (c *mockConn) RemoteAddr() net.Addr               { return mockAddr{} }
func (c *mockConn) SetDeadline(time.Time) error        { return nil }
func (c *mockConn) SetReadDeadline(time.Time) error    { return nil }
func (c *mockConn) SetWriteDeadline(time.Time) error   { return nil }

func (c *mockConn) isClosed() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.closed
}

func (c *mockConn) bytesWritten() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.writes.Len()
}

// ---- 记录型 listener：实现全部可选扩展接口 ----

type disconnectEvent struct {
	runnerId string
	reason   string
	session  *RunnerSession
}

type recordingListener struct {
	validTokens map[string]bool

	mu          sync.Mutex
	heartbeats  []string
	statuses    []string
	logs        []string
	artifacts   []string
	disconnects []disconnectEvent
}

func newRecordingListener(tokens ...string) *recordingListener {
	valid := make(map[string]bool, len(tokens))
	for _, t := range tokens {
		valid[t] = true
	}
	return &recordingListener{validTokens: valid}
}

func (l *recordingListener) OnRegister(session *RunnerSession, req *messages.RegisterRequest) *messages.RegisterResponse {
	if l.validTokens[req.GetToken()] {
		return &messages.RegisterResponse{Success: true, Message: "ok"}
	}
	return &messages.RegisterResponse{Success: false, Message: "invalid token"}
}

func (l *recordingListener) OnHeartbeat(session *RunnerSession, req *messages.HeartbeatRequest) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.heartbeats = append(l.heartbeats, session.RunnerId)
}

func (l *recordingListener) OnDisconnect(runnerId, reason string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.disconnects = append(l.disconnects, disconnectEvent{runnerId: runnerId, reason: reason})
}

// OnDisconnectSession 实现 SessionDisconnectListener（带会话身份）
func (l *recordingListener) OnDisconnectSession(session *RunnerSession, reason string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.disconnects = append(l.disconnects, disconnectEvent{
		runnerId: session.RunnerId, reason: reason, session: session,
	})
}

func (l *recordingListener) OnContainerStatus(session *RunnerSession, resp *messages.ContainerStatusResponse) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.statuses = append(l.statuses, session.RunnerId)
}

func (l *recordingListener) OnContainerLogs(session *RunnerSession, resp *messages.ContainerLogsResponse) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.logs = append(l.logs, session.RunnerId)
}

func (l *recordingListener) OnArtifactRequest(session *RunnerSession, req *messages.ArtifactRequest) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.artifacts = append(l.artifacts, session.RunnerId)
}

func (l *recordingListener) heartbeatCount() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.heartbeats)
}

func (l *recordingListener) statusCount() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.statuses)
}

func (l *recordingListener) logCount() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.logs)
}

func (l *recordingListener) artifactCount() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.artifacts)
}

func (l *recordingListener) disconnectsFor(runnerId string) []disconnectEvent {
	l.mu.Lock()
	defer l.mu.Unlock()
	var result []disconnectEvent
	for _, e := range l.disconnects {
		if e.runnerId == runnerId {
			result = append(result, e)
		}
	}
	return result
}

func (l *recordingListener) disconnectCount(runnerId string) int {
	return len(l.disconnectsFor(runnerId))
}

func (l *recordingListener) reset() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.heartbeats = nil
	l.statuses = nil
	l.logs = nil
	l.artifacts = nil
	l.disconnects = nil
}

// ---- 会话构造与老化辅助 ----

// ageHeartbeat 将会话心跳时间拨回过去，用于构造「旧会话过期、新会话新鲜」场景
func ageHeartbeat(session *RunnerSession, d time.Duration) {
	session.lastHeartbeatTime.Store(time.Now().Add(-d).UnixMilli())
}

// registeredSession 构造一个已注册到 sessions 的会话
func registeredSession(sessions *SessionManager, runnerId string, conn net.Conn) *RunnerSession {
	session := NewRunnerSession(runnerId, conn, runnerId+"-host", "127.0.0.1", "test")
	sessions.Register(session)
	return session
}
