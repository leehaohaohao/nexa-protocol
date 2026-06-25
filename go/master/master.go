package master

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"sync"
	"time"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
	"google.golang.org/protobuf/proto"
)

const (
	defaultHost              = "0.0.0.0"
	defaultPort              = 9090
	defaultHeartbeatTimeout  = 30 * time.Second
	defaultHeartbeatInterval = 5 * time.Second
)

// Option 函数式选项
type Option func(*NexaMaster)

// WithHost 设置监听地址
func WithHost(host string) Option {
	return func(m *NexaMaster) { m.host = host }
}

// WithPort 设置监听端口
func WithPort(port int) Option {
	return func(m *NexaMaster) { m.port = port }
}

// WithHeartbeatTimeout 设置心跳超时时间
func WithHeartbeatTimeout(d time.Duration) Option {
	return func(m *NexaMaster) { m.heartbeatTimeout = d }
}

// WithHeartbeatInterval 设置心跳检查间隔
func WithHeartbeatInterval(d time.Duration) Option {
	return func(m *NexaMaster) { m.heartbeatInterval = d }
}

// WithLogger 设置日志器
func WithLogger(logger *slog.Logger) Option {
	return func(m *NexaMaster) { m.logger = logger }
}

// NexaMaster 主节点
type NexaMaster struct {
	host              string
	port              int
	listener          Listener
	sessions          *SessionManager
	heartbeat         *HeartbeatMonitor
	logger            *slog.Logger
	heartbeatTimeout  time.Duration
	heartbeatInterval time.Duration

	tcpListener net.Listener
	wg          sync.WaitGroup
	ctx         context.Context
	cancel      context.CancelFunc
}

// New 创建主节点
func New(listener Listener, opts ...Option) *NexaMaster {
	ctx, cancel := context.WithCancel(context.Background())

	m := &NexaMaster{
		host:              defaultHost,
		port:              defaultPort,
		listener:          listener,
		sessions:          NewSessionManager(),
		logger:            slog.Default(),
		heartbeatTimeout:  defaultHeartbeatTimeout,
		heartbeatInterval: defaultHeartbeatInterval,
		ctx:               ctx,
		cancel:            cancel,
	}

	for _, opt := range opts {
		opt(m)
	}

	m.heartbeat = NewHeartbeatMonitor(m.sessions, listener, m.heartbeatTimeout, m.heartbeatInterval, m.logger)
	return m
}

// Start 启动主节点
func (m *NexaMaster) Start() error {
	addr := fmt.Sprintf("%s:%d", m.host, m.port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("listen %s: %w", addr, err)
	}
	m.tcpListener = ln

	m.heartbeat.Start(m.ctx)

	m.logger.Info("nexa master started", "addr", addr)

	m.wg.Add(1)
	go m.acceptLoop()

	return nil
}

func (m *NexaMaster) acceptLoop() {
	defer m.wg.Done()

	for {
		conn, err := m.tcpListener.Accept()
		if err != nil {
			select {
			case <-m.ctx.Done():
				return
			default:
				m.logger.Error("accept error", "error", err)
				continue
			}
		}

		m.wg.Add(1)
		go func() {
			defer m.wg.Done()
			m.handleConn(conn)
		}()
	}
}

func (m *NexaMaster) handleConn(conn net.Conn) {
	defer conn.Close()

	var currentSession *RunnerSession

	defer func() {
		if currentSession != nil {
			m.sessions.RemoveIfPresent(currentSession.RunnerId, currentSession)
			if !currentSession.timedOut.Load() {
				m.listener.OnDisconnect(currentSession.RunnerId, "connection_lost")
			}
		}
	}()

	for {
		data, err := codec.ReadFrame(conn)
		if err != nil {
			return
		}

		env, err := codec.UnmarshalEnvelope(data)
		if err != nil {
			m.logger.Error("unmarshal envelope error", "error", err)
			return
		}

		switch env.GetType() {
		case messages.MessageType_REGISTER_REQ:
			session := m.handleRegister(conn, env)
			if session == nil {
				return
			}
			currentSession = session

		case messages.MessageType_HEARTBEAT_REQ:
			if !m.handleHeartbeat(currentSession, env) {
				return
			}

		case messages.MessageType_DISCONNECT_REQ:
			m.handleDisconnect(currentSession, env)
			currentSession = nil
			return

		default:
			m.logger.Warn("unknown message type", "type", env.GetType())
		}
	}
}

func (m *NexaMaster) handleRegister(conn net.Conn, env *messages.Envelope) *RunnerSession {
	req := &messages.RegisterRequest{}
	if err := codec.UnmarshalMessage(env.GetPayload(), req); err != nil {
		m.logger.Error("unmarshal register request error", "error", err)
		return nil
	}

	session := NewRunnerSession(req.GetRunnerId(), conn, req.GetHostname(), req.GetIp(), req.GetVersion())

	old := m.sessions.Register(session)
	if old != nil {
		old.Close()
	}

	resp := m.listener.OnRegister(session, req)

	respEnv := codec.BuildRegisterResponse(req.GetRunnerId(), resp.GetSuccess(), resp.GetMessage())
	if !session.Send(respEnv) {
		m.sessions.RemoveIfPresent(session.RunnerId, session)
		return nil
	}

	m.logger.Info("runner registered",
		"runner_id", req.GetRunnerId(),
		"hostname", req.GetHostname(),
		"ip", req.GetIp(),
	)

	return session
}

func (m *NexaMaster) handleHeartbeat(session *RunnerSession, env *messages.Envelope) bool {
	if session == nil {
		return false
	}

	req := &messages.HeartbeatRequest{}
	if err := codec.UnmarshalMessage(env.GetPayload(), req); err != nil {
		m.logger.Error("unmarshal heartbeat request error", "error", err)
		return false
	}

	session.UpdateHeartbeat()

	respEnv := codec.BuildHeartbeatResponse(session.RunnerId)
	if !session.Send(respEnv) {
		return false
	}

	m.listener.OnHeartbeat(session, req)
	return true
}

func (m *NexaMaster) handleDisconnect(session *RunnerSession, env *messages.Envelope) {
	if session == nil {
		return
	}

	req := &messages.DisconnectRequest{}
	if err := proto.Unmarshal(env.GetPayload(), req); err != nil {
		m.logger.Error("unmarshal disconnect request error", "error", err)
	}

	m.sessions.Remove(session.RunnerId)
	session.Close()

	reason := req.GetReason()
	if reason == "" {
		reason = "client_disconnect"
	}

	m.logger.Info("runner disconnected", "runner_id", session.RunnerId, "reason", reason)
	m.listener.OnDisconnect(session.RunnerId, reason)
}

// Shutdown 优雅关闭主节点
func (m *NexaMaster) Shutdown() {
	m.cancel()

	if m.tcpListener != nil {
		m.tcpListener.Close()
	}

	for _, session := range m.sessions.AllSessions() {
		session.Close()
	}

	m.wg.Wait()
	m.logger.Info("nexa master stopped")
}

// Broadcast 向所有会话广播消息
func (m *NexaMaster) Broadcast(env *messages.Envelope) {
	for _, session := range m.sessions.AllSessions() {
		session.Send(env)
	}
}

// SendTo 向指定 runner 发送消息
func (m *NexaMaster) SendTo(runnerId string, env *messages.Envelope) bool {
	session, ok := m.sessions.Get(runnerId)
	if !ok {
		return false
	}
	return session.Send(env)
}

// SessionCount 返回当前会话数量
func (m *NexaMaster) SessionCount() int {
	return m.sessions.Size()
}
