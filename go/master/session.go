package master

import (
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
	"google.golang.org/protobuf/proto"
)

// RunnerSession 代表一个已连接的 Runner 会话
type RunnerSession struct {
	RunnerId         string
	Conn             net.Conn
	Hostname         string
	IP               string
	Version          string
	lastHeartbeatTime atomic.Int64
	timedOut          atomic.Bool
	mu                sync.Mutex // 保护 conn 写操作
}

// NewRunnerSession 创建新的会话
func NewRunnerSession(runnerId string, conn net.Conn, hostname, ip, version string) *RunnerSession {
	s := &RunnerSession{
		RunnerId: runnerId,
		Conn:     conn,
		Hostname: hostname,
		IP:       ip,
		Version:  version,
	}
	s.lastHeartbeatTime.Store(time.Now().UnixMilli())
	return s
}

// UpdateHeartbeat 更新心跳时间，仅在未超时时更新
func (s *RunnerSession) UpdateHeartbeat() {
	if !s.timedOut.Load() {
		s.lastHeartbeatTime.Store(time.Now().UnixMilli())
	}
}

// MarkTimedOut CAS 标记超时，仅首次返回 true
func (s *RunnerSession) MarkTimedOut() bool {
	return s.timedOut.CompareAndSwap(false, true)
}

// IsExpired 判断是否超过超时时间
func (s *RunnerSession) IsExpired(timeout time.Duration) bool {
	last := s.lastHeartbeatTime.Load()
	return time.Now().UnixMilli()-last > timeout.Milliseconds()
}

// Send 发送 Envelope 到连接
func (s *RunnerSession) Send(env *messages.Envelope) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.Conn == nil {
		return false
	}

	data, err := proto.Marshal(env)
	if err != nil {
		return false
	}

	if err := codec.WriteFrame(s.Conn, data); err != nil {
		return false
	}
	return true
}

// Close 关闭连接
func (s *RunnerSession) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.Conn != nil {
		s.Conn.Close()
		s.Conn = nil
	}
}
